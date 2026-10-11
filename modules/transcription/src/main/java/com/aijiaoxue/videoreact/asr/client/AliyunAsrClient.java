package com.aijiaoxue.videoreact.asr.client;

import com.aijiaoxue.videoreact.asr.config.AsrProperties;
// 注意用 Jackson 3（tools.jackson）：Spring Boot 4 自动配置的是这个，
// 引入 com.fasterxml.jackson（Jackson 2）会因为容器里没有对应 bean 而启动失败。
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * 阿里云百炼「非实时语音识别」客户端。
 *
 * <p>单独一个实现类而不是改 {@code WandAsrClient}：走的虽然是"提交 + 轮询"两步，
 * 但每一层的形状都不同，硬塞进一个类会变成一堆 {@code if (provider == ...)}。
 *
 * <p><b>协议形状</b>（官方文档值，见 {@code docs/阿里云模型选型.md}）：
 * <ol>
 *   <li>提交：{@code POST {base}/services/audio/asr/transcription}，头 {@code X-DashScope-Async: enable}，
 *       体 {@code {"model":..., "input":{"file_urls":[...]}, "parameters":{...}}}；
 * <p><b>协议形状</b>（2026-09-15 用真实凭据实测，见 {@code docs/阿里云模型选型.md} §5）：
 * <ol>
 *   <li>提交：{@code POST {base}/services/audio/asr/transcription}，头 {@code X-DashScope-Async: enable}，
 *       体 {@code {"model":..., "input":{"file_urls":[...]}, "parameters":{...}}}；
 *       返回 {@code output.task_id}，状态一般是 {@code PENDING}；</li>
 *   <li>轮询：{@code GET {base}/tasks/{task_id}}，读 {@code output.task_status}
 *       （实测 {@code PENDING}/{@code RUNNING}/{@code SUCCEEDED}/{@code FAILED}）；</li>
 *   <li>取结果：{@code output.results[0].transcription_url} 指向<b>另一个 JSON 文件</b>，
 *       下载后读 {@code transcripts[].sentences[]}，其中
 *       {@code begin_time}/{@code end_time} 是<b>毫秒整数</b>。</li>
 * </ol>
 *
 * <p><b>五个必须小心的地方</b>（全部来自实测，不是文档推测）：
 * <ul>
 *   <li><b>结果要再下一次</b>。成功响应里没有文本，只有一个 URL。所以本客户端必须
 *       自己下载并解析——这与 TokenHub 下载 SRT 是同一类问题，但格式不同（JSON 而非 SRT）。
 *       结果链接<b>只有 24 小时</b>有效，因此不能"先存 URL 稍后再取"；</li>
 *   <li><b>不要混淆两级 end_time</b>。任务级 {@code output.end_time} 是字符串日期
 *       （如 {@code "2026-09-15 20:09:09.141"}），句子级 {@code end_time} 才是毫秒整数。
 *       按名字盲取会得到垃圾时间戳；</li>
 *   <li><b>{@code channel_id} 必须是数组</b>。发标量时提交照样返回 HTTP 200 + task_id，
 *       但任务会在异步阶段以 {@code "parameter.channel_id must be an array"} 失败。
 *       详见 {@link #buildParameters()}；</li>
 *   <li><b>成功响应里 {@code output} 嵌了一个同名的 {@code output}</b>，里面是重复的一份
 *       {@code results}/{@code transcription_url}。冗余但无害——关键是
 *       <b>有效路径是 {@code output.results}（外层），不是 {@code output.output.results}</b>；</li>
 *   <li><b>拒收内联音频</b>。{@link #acceptsInlineAudio()} 返回 false，
 *       {@code JobRunner} 因此必须先暂存再提交。这里再兜一道：
 *       真收到内联命令时直接抛不可重试的错，而不是发一个空 file_urls 让引擎去报下载失败。</li>
 * </ul>
 *
 * <p><b>任务级失败时，报错在 {@code output.code}/{@code output.message}</b>，不在 {@code results} 里：
 * 参数错误会给出可读原因（如 {@code parameter.channel_id must be an array}），
 * 但音频不可达只给一个光秃秃的 {@code SERVER_ERROR} 且没有 {@code message}。
 * 后者对排障几乎没有帮助，所以 {@link #failureReason} 会在这种情况下补一句
 * "可能是音频地址不可达"——这是实测观察到的唯一成因。
 */
public class AliyunAsrClient implements AsrProviderClient {
    @Override public boolean supportsSpeakerLabels(){return config.diarizationEnabled();}

    /** 提供方标识，写入 {@code transcription_job.provider}。 */
    public static final String PROVIDER = "ALIYUN_FILE_TRANS";

    private static final Logger log = LoggerFactory.getLogger(AliyunAsrClient.class);

    /** 提交任务。 */
    private static final String PATH_SUBMIT = "/services/audio/asr/transcription";

    /** 查询任务（前缀，后面拼 task_id）。 */
    private static final String PATH_TASKS = "/tasks/";

    /**
     * 不可重试的错误码/前缀。
     *
     * <p>判断依据是"重试一次会不会变成另一个结果"：
     * 鉴权、额度、参数、文件本身的问题都不会，网络与限流会。
     */
    private static final List<String> NON_RETRYABLE_PREFIXES = List.of(
            "InvalidParameter",
            "InvalidFile",
            "Arrearage",
            "BadRequest",
            "UnsupportedOperation",
            "DataInspectionFailed",
            "Throttling.RateQuota");

    /** 默认模型的音频上限（12 小时档）。 */
    private static final long MAX_MEDIA_BYTES = 2L * 1024L * 1024L * 1024L;

    private final AsrProperties.Aliyun config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Duration readTimeout;

    /**
     * 结果 JSON 下载器。抽成接缝的理由与 {@code WandAsrClient.srtDownloader} 相同：
     * 测试可以离线覆盖"成功但结果文件下载失败""结果 JSON 结构异常"这类分支，
     * 不需要联网、不消耗额度。
     */
    private final Function<String, String> resultDownloader;

    public AliyunAsrClient(AsrProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, null);
    }

    /**
     * @param properties       模块配置
     * @param objectMapper     Jackson 3
     * @param resultDownloader 自定义结果下载器；为 null 时使用内置 HTTP 实现
     */
    public AliyunAsrClient(
            AsrProperties properties,
            ObjectMapper objectMapper,
            Function<String, String> resultDownloader) {
        this.config = properties.provider().aliyun();
        this.objectMapper = objectMapper;
        this.readTimeout = Duration.ofSeconds(Math.max(30, properties.provider().readTimeoutSeconds()));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(5, properties.provider().connectTimeoutSeconds())))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.resultDownloader = resultDownloader != null ? resultDownloader : this::downloadText;
    }

    @Override
    public String providerName() {
        return PROVIDER;
    }

    @Override
    public boolean credentialsReady() {
        return config.apiKey() != null && !config.apiKey().isBlank();
    }

    @Override
    public List<String> configurationHints() {
        List<String> hints = new ArrayList<>();
        if (!credentialsReady()) {
            hints.add("百炼 API Key 未配置：请设置环境变量 DASHSCOPE_API_KEY"
                    + "（控制台：https://bailian.console.aliyun.com/ → API-KEY 管理）。");
        }
        // ⚠️ WorkspaceId 不再是必填项。
        // 曾在这里写过「缺它会 404/401」，那是照官方文档推的；2026-09-15 真机实测证明
        // 公共域名 dashscope.aliyuncs.com 直接接受 sk-ws- 形态的 Key。
        // 留着那句会把人引去配一个不影响可用性的东西——错误提示比没有提示更糟。
        if (config.workspaceId() == null || config.workspaceId().isBlank()) {
            hints.add("业务空间 ID 未配置（可选）：当前走公共域名 " + config.resolvedBaseUrl()
                    + "，实测可正常调用。专属域名官方称性能与稳定性更好，生产建议配置"
                    + "（设 DASHSCOPE_WORKSPACE_ID 后会自动改写为专属域名）。");
        }
        return List.copyOf(hints);
    }

    @Override
    public String modelIdentifier() {
        return config.model();
    }

    /**
     * 阿里云 filetrans 系列<b>只接受公网 URL</b>。
     *
     * @return false
     */
    @Override
    public boolean acceptsInlineAudio() {
        return false;
    }

    /**
     * 单文件上限 2GB（12 小时档）。
     *
     * <p>注意这是<b>提交地址指向的文件</b>的上限，不是原视频的上限——
     * 我们提交的是抽出的 16k 单声道 WAV，45 分钟约 86MB，远未触顶。
     */
    @Override
    public long maxInputUrlBytes() {
        return MAX_MEDIA_BYTES;
    }

    /**
     * 提交转写任务。
     *
     * @param command 提交参数；必须带 URL（由 {@code AudioStaging} 暂存而来）
     * @return 百炼 task_id（UUID 形态的字符串）
     * @throws AsrProviderException 调用失败
     */
    @Override
    public String submit(AsrSubmitCommand command) {
        if (!command.hasUrl()) {
            // 不发空 file_urls 去换一句引擎侧的下载失败：那会把"配置缺失"伪装成"文件问题"
            throw new AsrProviderException(PROVIDER, "InlineAudioNotSupported",
                    "阿里云非实时语音识别不接受内联音频，必须提供公网可访问的 URL。"
                            + "当前命令没有 URL，说明音频暂存（AudioStaging）没有生效。", false);
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", config.model());

        ObjectNode input = body.putObject("input");
        ArrayNode fileUrls = input.putArray("file_urls");
        fileUrls.add(command.audioUrl());

        ObjectNode parameters = buildParameters();
        body.set("parameters", parameters);
        if (command.languageHints() != null && !command.languageHints().isEmpty()) {
            ArrayNode hints = parameters.putArray("language_hints");
            command.languageHints().forEach(hints::add);
        }

        // 只打 host：暂存地址带签名查询串，禁止进日志
        log.info("提交阿里云转写任务: model={}, host={}, file={}",
                config.model(), safeHost(command.audioUrl()), command.audioFileName());

        HttpResponse<String> response = send(PATH_SUBMIT, body.toString());
        JsonNode root = parse(response.body());
        throwIfFailed(response.statusCode(), root, "提交任务");

        String taskId = text(root.path("output"), "task_id");
        if (taskId == null || taskId.isBlank()) {
            throw new AsrProviderException(PROVIDER, "EmptyTaskId",
                    "阿里云未返回 output.task_id，原始响应: " + abbreviate(response.body()), true);
        }
        log.info("阿里云任务已创建: taskId={}, status={}, requestId={}",
                taskId, text(root.path("output"), "task_status"), text(root, "request_id"));
        return taskId;
    }

    /**
     * 组装 {@code parameters}。
     *
     * <p><b>{@code channel_id} 必须是数组，不是标量</b>——这是真机实测踩出来的坑，
     * 而且踩法极其隐蔽（2026-09-15，见 {@code docs/阿里云模型选型.md} §5）：
     * <pre>
     * parameters: {"channel_id": 0}      → 提交 HTTP 200 + task_id（看起来成功）
     *                                    → 异步 FAILED: "parameter.channel_id must be an array"
     * parameters: {"channel_id": [0]}    → 提交 HTTP 200 → SUCCEEDED
     * </pre>
     * 官方 HTTP API 参数表里 {@code channel_id} 是 {@code list[int]}，
     * 但「结果说明」与常见示例都是单值语境，很容易照着写成标量。
     * <b>提交阶段完全看不出来</b>，只有轮询到 FAILED 才知道，所以加测试钉住这一条。
     *
     * <p><b>为什么即使为空也要发 {@code parameters}</b>：官方对<b>新版专属域名</b>明确写了
     * 「必须包含 parameters 对象…否则任务可正常提交，但识别将失败」。
     * 实测在公共域名 {@code dashscope.aliyuncs.com} 下省略整个 {@code parameters}
     * <b>也能成功</b>，但两种域名共用同一份代码，所以一律照更严的规则发。
     */
    private ObjectNode buildParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        // 数组，不是标量。写成 put(String, int) 会让每个任务在异步阶段失败。
        parameters.putArray("channel_id").add(config.channelId());
        if (config.vocabularyId() != null && !config.vocabularyId().isBlank()) {
            parameters.put("vocabulary_id", config.vocabularyId());
        }
        parameters.put("diarization_enabled", config.diarizationEnabled());
        if (config.diarizationEnabled() && config.speakerCount() >= 2) {
            // 官方：2–100 的整数参考值。实测传 1（越界）不报错也不生效，
            // 所以这里只发合规值，不靠服务端兜底。
            parameters.put("speaker_count", config.speakerCount());
        }
        return parameters;
    }

    /**
     * 查询任务状态与结果。
     *
     * <p>成功时还要再下载一次结果 JSON，见类注释。
     *
     * @param providerTaskId 百炼 task_id
     * @return 提供方状态；成功时 {@code sentences} 为带毫秒时间戳的分段
     * @throws AsrProviderException 调用失败
     */
    @Override
    public AsrProviderStatus describe(String providerTaskId) {
        JsonNode root = parse(fetchTaskJson(providerTaskId));
        JsonNode output = root.path("output");
        String statusText = text(output, "task_status");
        ProviderTaskStatus mapped = mapStatus(statusText);

        if (mapped != ProviderTaskStatus.SUCCESS) {
            // 注意失败信息可能挂在 results[0] 上，而不是 output 顶层
            return new AsrProviderStatus(providerTaskId, mapped, statusText, null, List.of(), null,
                    failureReason(output));
        }

        JsonNode firstResult = output.path("results").path(0);
        String subtaskStatus = text(firstResult, "subtask_status");
        if (subtaskStatus != null && !"SUCCEEDED".equalsIgnoreCase(subtaskStatus)) {
            // 官方：任务级 SUCCEEDED 只表示"至少一个子任务成功"，
            // 真正的结果要看 subtask_status。单文件场景下二者应当一致。
            return new AsrProviderStatus(providerTaskId, ProviderTaskStatus.FAILED, statusText,
                    null, List.of(), null, failureReason(output));
        }

        String transcriptionUrl = text(firstResult, "transcription_url");
        if (transcriptionUrl == null || transcriptionUrl.isBlank()) {
            log.warn("阿里云任务 {} 状态 SUCCEEDED，但没有 transcription_url；原始 output: {}",
                    providerTaskId, abbreviate(output.toString()));
            return new AsrProviderStatus(providerTaskId, ProviderTaskStatus.FAILED, statusText,
                    null, List.of(), null, "引擎返回成功但没有结果地址（transcription_url 缺失）");
        }

        String resultJson = resultDownloader.apply(transcriptionUrl);
        ParsedTranscript parsed = parseTranscript(resultJson);
        log.info("阿里云任务 {} 解析出 {} 条分段（结果 JSON {} 字节，音频 {} ms）",
                providerTaskId, parsed.sentences().size(),
                resultJson == null ? 0 : resultJson.length(), parsed.audioDurationMs());

        return new AsrProviderStatus(providerTaskId, ProviderTaskStatus.SUCCESS, statusText,
                parsed.text(), parsed.sentences(),
                parsed.audioDurationMs() == null ? null : parsed.audioDurationMs() / 1000.0,
                null);
    }

    /**
     * 取任务状态 JSON。
     *
     * <p>抽成 {@code protected} 接缝的理由与 {@code WandAsrClient.fetchTaskJson} 相同：
     * 便于测试用固定报文覆盖解析分支。
     *
     * <p><b>为什么是 GET</b>：API 参考页写的是 {@code GET /api/v1/tasks/{task_id}}，
     * 但仓库内早期笔记里出现过 {@code POST}。以 API 参考页为准，
     * 真机验证时若返回 405 就改这里——这是唯一需要改的地方。
     *
     * @param providerTaskId 任务 ID
     * @return 响应体
     */
    protected String fetchTaskJson(String providerTaskId) {
        return sendGet(PATH_TASKS + providerTaskId).body();
    }

    /**
     * 解析结果 JSON 文件。
     *
     * <p>结构（官方「识别结果说明」）：
     * <pre>
     * {
     *   "properties": {"original_duration_in_milliseconds": 3834, ...},
     *   "transcripts": [{
     *     "channel_id": 0, "text": "...",
     *     "sentences": [{"begin_time":100,"end_time":3820,"text":"...","speaker_id":0,"words":[...]}]
     *   }]
     * }
     * </pre>
     * 多音轨时会返回多个 {@code transcripts}，本模块只提交单声道，
     * 但仍按 {@code channel_id} 归并而不是只取第 0 条——否则一旦有人改了
     * ffmpeg 参数抽成双声道，结果会静默少一半。
     */
    private ParsedTranscript parseTranscript(String json) {
        JsonNode root = parse(json);
        JsonNode transcripts = root.path("transcripts");
        if (!transcripts.isArray() || transcripts.isEmpty()) {
            throw new AsrProviderException(PROVIDER, "EmptyTranscript",
                    "结果 JSON 里没有 transcripts 数组，原始内容: " + abbreviate(json), false);
        }

        List<ProviderSentence> sentences = new ArrayList<>();
        StringBuilder whole = new StringBuilder();
        for (JsonNode transcript : transcripts) {
            String channelText = text(transcript, "text");
            if (channelText != null) {
                whole.append(channelText);
            }
            JsonNode array = transcript.path("sentences");
            if (!array.isArray()) {
                continue;
            }
            for (JsonNode sentence : array) {
                Long begin = longValue(sentence, "begin_time");
                Long end = longValue(sentence, "end_time");
                Long speaker = longValue(sentence, "speaker_id");
                String text = text(sentence, "text");
                sentences.add(new ProviderSentence(text, null, null, begin, end, speaker, List.of()));
            }
        }

        if (sentences.isEmpty()) {
            // 不是异常：合法静音确实会得到空分段，契约要求标 NO_SPEECH 后仍算成功
            log.info("阿里云结果 JSON 不含任何分段（可能是静音音频）");
        } else if (sentences.stream().allMatch(s -> s.startMs() == null && s.endMs() == null)) {
            log.warn("阿里云结果 JSON 里没有解析到任何句子级时间戳，实际结构可能与预期不同。"
                    + "首个 sentences 元素原文: {}", abbreviate(firstSentenceJson(transcripts)));
        }

        Long audioDurationMs = longValue(root.path("properties"), "original_duration_in_milliseconds");
        return new ParsedTranscript(List.copyOf(sentences),
                whole.length() == 0 ? null : whole.toString(), audioDurationMs);
    }

    private static String firstSentenceJson(JsonNode transcripts) {
        for (JsonNode transcript : transcripts) {
            JsonNode array = transcript.path("sentences");
            if (array.isArray() && !array.isEmpty()) {
                return array.get(0).toString();
            }
        }
        return "(无)";
    }

    /**
     * 组装失败原因。
     *
     * <p><b>实测的两种失败形状</b>（2026-09-15）：
     * <ol>
     *   <li><b>任务级失败</b>——{@code output} 里直接有 {@code code} 与 {@code message}，
     *       形如 {@code "parameter.channel_id must be an array"}，可读、可定位。
     *       此时 <b>{@code results} 整个是 {@code null}</b>，所以先读 {@code results[0]} 会拿到空；</li>
     *   <li><b>子任务级失败</b>——任务级 {@code SUCCEEDED}，但 {@code results[0].subtask_status=FAILED}
     *       并带 {@code results[0].code/message}（如音频不可达）；
     *       {@code describe} 里已单独处理这一支。</li>
     * </ol>
     *
     * <p><b>为什么 {@code SERVER_ERROR} 要补一句解释</b>：音频不可达时阿里云只回
     * {@code {"code":"SERVER_ERROR"}}，<b>连 {@code message} 都没有</b>。
     * 主项目拿到这个码完全无从下手，而实测中它唯一出现的场景就是"引擎下载不到音频"
     * ——通常是预签名 URL 过期、或地址不是公网可达。所以这里如实补一句猜测，
     * 并明确标注是猜测而不是引擎原话。
     *
     * @param output 轮询响应的 {@code output} 节点
     * @return 失败原因；都取不到时返回 null
     */
    private String failureReason(JsonNode output) {
        JsonNode result = output.path("results").path(0);
        String reason = joinCodeAndMessage(text(result, "code"), text(result, "message"));
        if (reason != null) {
            return reason;
        }
        String code = text(output, "code");
        String message = text(output, "message");
        reason = joinCodeAndMessage(code, message);
        if (reason != null) {
            if ("SERVER_ERROR".equals(code)) {
                return reason + "（引擎未给出原因；实测该码出现在音频地址不可达时，"
                        + "通常是暂存地址已过期或不是公网可达）";
            }
            return reason;
        }
        return null;
    }

    /** 拼成 {@code code: message}；两者都为空时返回 null，只有一个时只用那一个。 */
    private static String joinCodeAndMessage(String code, String message) {
        boolean hasCode = code != null && !code.isBlank();
        boolean hasMessage = message != null && !message.isBlank();
        if (hasCode && hasMessage) {
            return code + ": " + message;
        }
        if (hasCode) {
            return code;
        }
        return hasMessage ? message : null;
    }

    /**
     * 状态映射。
     *
     * <p>官方枚举只有四个：{@code PENDING}/{@code RUNNING}/{@code SUCCEEDED}/{@code FAILED}。
     * <b>除 SUCCEEDED/FAILED 之外一律当作"仍在处理"</b>由轮询超时兜底，
     * 而不是猜一个没见过的值就把任务判死——这与 {@code WandAsrClient} 的策略一致，
     * 两家提供方的行为必须一样。
     *
     * @param status 原始状态
     * @return 语义状态
     */
    static ProviderTaskStatus mapStatus(String status) {
        if (status == null) {
            return ProviderTaskStatus.UNKNOWN;
        }
        return switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "SUCCEEDED", "SUCCESS" -> ProviderTaskStatus.SUCCESS;
            case "FAILED", "UNKNOWN" -> ProviderTaskStatus.FAILED;
            case "PENDING" -> ProviderTaskStatus.WAITING;
            case "RUNNING" -> ProviderTaskStatus.RUNNING;
            default -> ProviderTaskStatus.UNKNOWN;
        };
    }

    /**
     * 下载结果 JSON。预签名 URL 自带鉴权，<b>不要</b>带 Bearer 头。
     *
     * @param url 结果地址
     * @return 结果 JSON 文本
     * @throws AsrProviderException 下载失败
     */
    private String downloadText(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(readTimeout)
                    .header("Accept", "application/json, */*")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, BoundedBodyHandlers.utf8(8 * 1024 * 1024));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AsrProviderException(PROVIDER, "ResultDownloadFailed",
                        "下载转写结果 JSON 失败，HTTP " + response.statusCode()
                                + "（阿里云结果链接有效期 24 小时，过期后无法补取）", true);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AsrProviderException(PROVIDER, "Interrupted", "下载转写结果被中断", true, e);
        } catch (AsrProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new AsrProviderException(PROVIDER, e.getClass().getSimpleName(),
                    "下载转写结果 JSON 失败: " + e.getMessage(), true, e);
        }
    }

    private HttpResponse<String> send(String path, String jsonBody) {
        requireApiKey();
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.resolvedBaseUrl() + path))
                .timeout(readTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                // 异步任务标识：官方标注「请勿遗漏，否则无法提交任务」
                .header("X-DashScope-Async", "enable")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return execute(request, path);
    }

    private HttpResponse<String> sendGet(String path) {
        requireApiKey();
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.resolvedBaseUrl() + path))
                .timeout(readTimeout)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .GET()
                .build();
        return execute(request, path);
    }

    private HttpResponse<String> execute(HttpRequest request, String path) {
        try {
            HttpResponse<String> response = httpClient.send(request, BoundedBodyHandlers.utf8(8 * 1024 * 1024));
            log.debug("阿里云 {} -> HTTP {}", path, response.statusCode());
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AsrProviderException(PROVIDER, "Interrupted", "调用被中断: " + e.getMessage(), true, e);
        } catch (Exception e) {
            throw new AsrProviderException(PROVIDER,
                    e.getClass().getSimpleName(), "调用阿里云百炼失败: " + e.getMessage(), true, e);
        }
    }

    private void requireApiKey() {
        if (!credentialsReady()) {
            throw new AsrProviderException(PROVIDER, "MissingApiKey",
                    "百炼 API Key 未配置：请设置环境变量 DASHSCOPE_API_KEY", false);
        }
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new AsrProviderException(PROVIDER, "MalformedResponse",
                    "阿里云返回的不是合法 JSON: " + abbreviate(body), true, e);
        }
    }

    /**
     * 调用失败时抛异常。
     *
     * <p><b>与 {@code WandAsrClient} 的一处有意差异</b>：这里<b>不</b>把"任务级 FAILED"
     * 当调用成功返回。原因是轮询阶段的失败信息挂在 {@code output.results[0]} 上，
     * 由 {@link #describe} 负责转成 {@code FAILED} 状态；
     * 而提交阶段没有 {@code results}，能拿到的只有一次调用级错误。
     * 若两处都放行，提交阶段就会返回一个"状态未知但 HTTP 200"的响应，
     * 等于把失败吞掉。判定依据仍然是<b>响应体里的 code</b>而不是 HTTP 状态码，
     * 因为百炼在 HTTP 200 里也可能带上非空 {@code code}。
     *
     * @param httpStatus HTTP 状态码
     * @param root       响应根节点
     * @param action     调用动作，用于报错文案
     */
    private void throwIfFailed(int httpStatus, JsonNode root, String action) {
        String code = firstNonBlank(text(root, "code"),
                text(root.path("output"), "code"));
        String message = firstNonBlank(text(root, "message"),
                text(root.path("output"), "message"));

        boolean httpOk = httpStatus >= 200 && httpStatus < 300;
        if (httpOk && (code == null || code.isBlank())) {
            return;
        }

        String effectiveCode = code == null || code.isBlank() ? "HTTP_" + httpStatus : code;
        boolean retryable = isRetryable(httpStatus, effectiveCode);
        String full = "阿里云 %s 失败 [%s]: %s".formatted(action, effectiveCode,
                message == null || message.isBlank() ? "(无描述)" : message);
        log.warn("{}, retryable={}, http={}, requestId={}",
                full, retryable, httpStatus, text(root, "request_id"));
        throw new AsrProviderException(PROVIDER, effectiveCode, full, retryable);
    }

    /**
     * 是否可重试。
     *
     * <p>两类依据合起来判断：HTTP 状态码（网络与限流类可重试）与业务错误码前缀
     * （参数/文件/欠费类不可重试）。单看任何一类都会判错——
     * 例如 {@code InvalidFile.DownloadFailed} 是 HTTP 400，重试确实可能成功
     * （音频临时不可达），但它更可能是暂存地址真的失效了，所以按错误码归为不可重试，
     * 让主项目看到确定性结论而不是反复重试。
     *
     * @param httpStatus HTTP 状态码
     * @param code       业务错误码
     * @return 是否可重试
     */
    static boolean isRetryable(int httpStatus, String code) {
        if (code != null) {
            for (String prefix : NON_RETRYABLE_PREFIXES) {
                if (code.startsWith(prefix)) {
                    return false;
                }
            }
        }
        if (httpStatus == 401 || httpStatus == 403 || httpStatus == 404) {
            return false;
        }
        return httpStatus == 408 || httpStatus == 429 || httpStatus >= 500 || httpStatus < 200
                || (httpStatus >= 200 && httpStatus < 300);
    }

    private static String safeHost(String url) {
        if (url == null) {
            return "(none)";
        }
        try {
            String host = URI.create(url).getHost();
            return host == null ? "(unknown)" : host;
        } catch (RuntimeException e) {
            return "(unparsable)";
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }

    private static String text(JsonNode node, String field) {
        // 用 stringValue() 而不是已弃用的 asText()。两者在 Jackson 3 里字节码等价
        // （asText() 就是 stringValue() 的别名），但 asText() 已被标记 @Deprecated，
        // 保留它会持续产生编译警告。
        return node != null && node.hasNonNull(field) ? node.get(field).stringValue() : null;
    }

    private static Long longValue(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        JsonNode value = node.get(field);
        return value.isNumber() ? value.asLong() : null;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "(空)";
        }
        return text.length() <= 400 ? text : text.substring(0, 400) + "…";
    }

    /**
     * 结果 JSON 的解析产物。
     *
     * @param sentences      带毫秒时间戳的分段
     * @param text           整段文本
     * @param audioDurationMs 音频原始时长（毫秒），来自 {@code properties}
     */
    private record ParsedTranscript(
            List<ProviderSentence> sentences, String text, Long audioDurationMs) {
    }
}
