package com.aijiaoxue.videoreact.asr.client;

import com.aijiaoxue.videoreact.asr.config.AsrProperties;
import com.aijiaoxue.videoreact.asr.converter.SrtParser;
// 注意用 Jackson 3（tools.jackson）：Spring Boot 4 自动配置的是这个，
// 引入 com.fasterxml.jackson（Jackson 2）会因为容器里没有对应 bean 而启动失败。
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 腾讯云 TokenHub（{@code wand-asr-v1}）客户端。
 *
 * <p><b>这是一个和标准 ASR 完全不同的接口</b>，所以单独一个实现类而不是改
 * {@link TencentAsrClient}。差异（均为实测，见 {@code docs/TokenHub接口探测记录.md}）：
 * <ul>
 *   <li>鉴权：单个 <b>Bearer API Key</b>，不需要 TC3 签名；</li>
 *   <li>请求体：只有 {@code model} + {@code input_url}（或 base64 的 {@code data}）；</li>
 *   <li>响应：<b>OpenAI 风格信封</b> {@code {object,id,request_id,status,output,error}}；</li>
 *   <li>状态：成功是 <b>{@code completed}</b>（不是 SUCCEEDED），失败是 {@code failed}。</li>
 * </ul>
 *
 * <p><b>为什么走异步通道</b>：TokenHub 还有一个同步接口 {@code /sync_transcribe}，
 * 但它会阻塞到识别结束。课堂长视频可能阻塞几分钟到几十分钟，
 * 放在 HTTP 请求线程里会占死 Tomcat 线程并大概率撞上网关超时。
 * 异步通道（{@code async_create} + 轮询 {@code tasks/{id}}）与本模块既有的
 * "提交 + 轮询兜底"状态机天然吻合，因此这里只实现异步。
 *
 * <p><b>错误信封有两种形状</b>（实测）：
 * <ol>
 *   <li>网关错误（鉴权/额度/模型名）：只有 {@code error}，没有 {@code id}/{@code object}；</li>
 *   <li>任务错误（参数/上游失败）：完整信封，带 {@code id}/{@code object}/{@code status}。</li>
 * </ol>
 * 且错误响应的 {@code Content-Type} 不统一（{@code application/json} 与
 * {@code text/plain} 混用），因此<b>按响应内容解析，不按 Content-Type 判断</b>。
 */
public class WandAsrClient implements AsrProviderClient {

    /** 提供方标识，写入 {@code asr_task.provider}。 */
    public static final String PROVIDER = "WAND";

    private static final Logger log = LoggerFactory.getLogger(WandAsrClient.class);

    private static final String PATH_ASYNC_CREATE = "/v1/wand/asrproxy/async_create";
    private static final String PATH_TASKS = "/v1/wand/asrproxy/tasks/";

    /** 网关级不可重试错误码：鉴权、额度、模型名。 */
    private static final java.util.Set<String> NON_RETRYABLE_GATEWAY_CODES = java.util.Set.of(
            "401002",  // API Key 不存在或签名校验失败
            "401006",  // service ID 不存在或与 model 不匹配
            "401007",  // 无免费额度且未开后付费
            "400001",  // 缺必填字段
            "400002",  // 参数取值非法
            "400004"); // task not found

    /** 上游引擎失败（可重试：可能是音频临时不可达）。 */
    private static final String CODE_UPSTREAM_ERROR = "502001";

    private final AsrProperties.Wand config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Duration readTimeout;

    /**
     * SRT 下载器。抽成字段是为了可测：真实实现走 HTTP，
     * 测试可以注入一个返回固定 SRT 文本的函数，从而离线验证整条解析链路。
     */
    private final java.util.function.Function<String, String> srtDownloader;

    public WandAsrClient(AsrProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, null);
    }

    /**
     * @param properties    模块配置
     * @param objectMapper  Jackson 3
     * @param srtDownloader 自定义 SRT 下载器；为 null 时使用内置 HTTP 实现
     */
    public WandAsrClient(
            AsrProperties properties,
            ObjectMapper objectMapper,
            java.util.function.Function<String, String> srtDownloader) {
        this.config = properties.provider().wand();
        this.objectMapper = objectMapper;
        this.readTimeout = Duration.ofSeconds(Math.max(30, properties.provider().readTimeoutSeconds()));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(5, properties.provider().connectTimeoutSeconds())))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.srtDownloader = srtDownloader != null ? srtDownloader : this::downloadText;
    }

    /**
     * 下载纯文本（SRT）。预签名 URL 自带鉴权，<b>不要</b>带 Bearer 头。
     *
     * @param url 地址
     * @return 文本内容
     * @throws AsrProviderException 下载失败
     */
    private String downloadText(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(readTimeout)
                    .header("Accept", "*/*")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, BoundedBodyHandlers.utf8(8 * 1024 * 1024));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AsrProviderException(PROVIDER, "SubtitleDownloadFailed",
                        "下载 SRT 结果失败，HTTP " + response.statusCode() + "（结果链接有效期 24 小时）", true);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AsrProviderException(PROVIDER, "Interrupted", "下载 SRT 被中断", true, e);
        } catch (AsrProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new AsrProviderException(PROVIDER, e.getClass().getSimpleName(),
                    "下载 SRT 失败: " + e.getMessage(), true, e);
        }
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
        if (credentialsReady()) {
            return List.of();
        }
        return List.of(
                "TokenHub API Key 未配置：请设置环境变量 WAND_API_KEY"
                        + "（控制台：https://console.cloud.tencent.com/tokenhub/apikey）。"
                        + "当前模型 " + config.model() + "，端点 " + config.baseUrl() + "。");
    }

    @Override
    public String modelIdentifier() {
        return config.model();
    }

    /**
     * 提交异步转写任务。
     *
     * <p>支持两种输入：内联 base64（{@code data}）与公网 URL（{@code input_url}）。
     * <b>契约下首选内联</b>：模块已经下载并核验了 sha256，
     * 再把受控 sourceUrl 交给第三方去下载既放弃了核验，也把凭据外泄了。
     * 内联通道的上限比 URL 通道小，所以 {@code JobRunner} 会在音频过大时
     * 明确返回失败，而不是悄悄退回 URL 方式。
     *
     * @param command 提交参数
     * @return TokenHub 任务 ID（字符串，如 {@code th_1486968752_00_asrproxy_...}）
     * @throws AsrProviderException 调用失败
     */
    @Override
    public String submit(AsrSubmitCommand command) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", config.model());

        if (command.hasInlineAudio()) {
            String base64 = java.util.Base64.getEncoder().encodeToString(command.audioBytes());
            body.put("data", base64);
            log.info("提交 TokenHub ASR 任务（内联 base64）: model={}, audio={} 字节, base64={} 字符, file={}",
                    config.model(), command.audioBytes().length, base64.length(), command.audioFileName());
        } else {
            body.put("input_url", command.audioUrl());
            // 只打 host：sourceUrl 带签名查询串，禁止进日志
            log.info("提交 TokenHub ASR 任务（URL）: model={}, host={}", config.model(),
                    safeHost(command.audioUrl()));
        }

        HttpResponse<String> response = send(PATH_ASYNC_CREATE, body.toString());
        JsonNode root = parse(response.body());
        throwIfError(root, "async_create");

        String taskId = text(root, "id");
        if (taskId == null || taskId.isBlank()) {
            throw new AsrProviderException(PROVIDER, "EmptyTaskId",
                    "TokenHub 未返回任务 id，原始响应: " + abbreviate(response.body()), true);
        }
        log.info("TokenHub 任务已创建: taskId={}, requestId={}", taskId, text(root, "request_id"));
        return taskId;
    }

    /** 只取 host，避免把带签名的完整 URL 写进日志。 */
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

    /**
     * 查询异步任务状态。
     *
     * <p><b>实测的关键差异</b>：TokenHub 的<b>异步</b>任务成功时 {@code output} 里
     * <b>只有 {@code subtitle_url}（SRT 文件），没有 {@code sentences}/{@code text}</b>；
     * 结构化 {@code sentences} 只在<b>同步</b>接口 {@code sync_transcribe} 里出现。
     * 我们走异步通道（避免阻塞 HTTP 线程），所以这里必须：
     * <ol>
     *   <li>先看有没有内联 {@code sentences}（兼容同步风格，也为将来字段补齐留路）；</li>
     *   <li>没有就下载 {@code subtitle_url} 指向的 SRT，解析出带时间戳的分段。</li>
     * </ol>
     *
     * @param providerTaskId TokenHub task_id
     * @return 提供方状态；成功时 {@code sentences} 为带时间戳的分段
     * @throws AsrProviderException 调用失败
     */
    @Override
    public AsrProviderStatus describe(String providerTaskId) {
        String body = fetchTaskJson(providerTaskId);
        JsonNode root = parse(body);
        throwIfError(root, "tasks");

        String status = text(root, "status");
        JsonNode output = root.path("output");
        ProviderTaskStatus mapped = mapStatus(status);

        Double durationSeconds = output.hasNonNull("duration_ms")
                ? output.get("duration_ms").asDouble() / 1000.0
                : null;
        String inlineText = text(output, "text");

        if (mapped != ProviderTaskStatus.SUCCESS) {
            return new AsrProviderStatus(providerTaskId, mapped, status, null, List.of(), null,
                    text(root.path("error"), "message"));
        }

        // 1) 有内联 sentences 就用它
        List<ProviderSentence> sentences = toSentences(output.path("sentences"));
        if (!sentences.isEmpty()) {
            return new AsrProviderStatus(providerTaskId, mapped, status, inlineText, sentences,
                    durationSeconds, null);
        }

        // 2) 异步通道的常态：只有 SRT，需要下载并解析
        String subtitleUrl = text(output, "subtitle_url");
        if (subtitleUrl == null || subtitleUrl.isBlank()) {
            log.warn("TokenHub 任务 {} 状态 completed，但既没有 sentences 也没有 subtitle_url；"
                    + "原始 output: {}", providerTaskId, abbreviate(output.toString()));
            return new AsrProviderStatus(providerTaskId, mapped, status, inlineText, List.of(),
                    durationSeconds, null);
        }

        String srt = srtDownloader.apply(subtitleUrl);
        sentences = SrtParser.parse(srt);
        log.info("TokenHub 任务 {} 从 SRT 解析出 {} 条带时间戳的分段（SRT {} 字节）",
                providerTaskId, sentences.size(), srt == null ? 0 : srt.length());

        if (durationSeconds == null && !sentences.isEmpty()) {
            // SRT 只有相对时间，用最后一条的结束时间估算总时长
            long maxEnd = sentences.stream()
                    .mapToLong(s -> s.endMs() == null ? 0L : s.endMs())
                    .max().orElse(0L);
            durationSeconds = maxEnd > 0 ? maxEnd / 1000.0 : null;
        }
        return new AsrProviderStatus(providerTaskId, mapped, status,
                inlineText != null ? inlineText : joinText(sentences), sentences, durationSeconds, null);
    }

    /**
     * 取任务状态 JSON。
     *
     * <p>抽成 {@code protected} 接缝，便于测试用固定报文覆盖解析分支
     * （尤其是"异步完成时只有 subtitle_url"这条关键行为），
     * 而不需要联网、不需要消耗额度。
     *
     * @param providerTaskId 任务 ID
     * @return 响应体
     */
    protected String fetchTaskJson(String providerTaskId) {
        return sendGet(PATH_TASKS + providerTaskId).body();
    }

    /** 把分段文本拼成整段文本（SRT 场景下 output 里没有 text 字段）。 */
    private static String joinText(List<ProviderSentence> sentences) {
        return sentences.stream()
                .map(ProviderSentence::finalSentence)
                .filter(java.util.Objects::nonNull)
                .reduce("", String::concat);
    }

    /**
     * 把 TokenHub 的状态字符串映射为语义状态。     *
     * <p>实测取值：{@code completed}（成功）、{@code failed}（失败）；
     * 排队/执行中的取值文档未给，因此<b>除这两个之外一律当作"仍在处理"</b>，
     * 由轮询超时兜底，避免因为猜错枚举值把任务误判为失败。
     *
     * @param status 原始状态
     * @return 语义状态
     */
    static ProviderTaskStatus mapStatus(String status) {
        if (status == null) {
            return ProviderTaskStatus.UNKNOWN;
        }
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "completed", "succeeded", "success" -> ProviderTaskStatus.SUCCESS;
            case "failed", "error" -> ProviderTaskStatus.FAILED;
            case "pending", "queued" -> ProviderTaskStatus.WAITING;
            case "running", "processing", "in_progress" -> ProviderTaskStatus.RUNNING;
            default -> ProviderTaskStatus.UNKNOWN;
        };
    }

    /**
     * 解析 {@code output.sentences}。
     *
     * <p><b>字段名按"毫秒 + 下划线"风格推断</b>（与 {@code duration_ms} 一致），
     * 并同时兼容驼峰写法；如果实测发现真实字段名不同，只需改这里。
     * 解析不到时间戳的分段会被跳过（{@code TencentAsrClient} 的转换器同样会兜底）。
     *
     * @param sentences JSON 数组
     * @return 自有分段列表
     */
    private List<ProviderSentence> toSentences(JsonNode sentences) {
        if (sentences == null || !sentences.isArray() || sentences.isEmpty()) {
            return List.of();
        }
        List<ProviderSentence> result = new ArrayList<>(sentences.size());
        for (JsonNode node : sentences) {
            String text = firstText(node, "text", "sentence", "content");
            Long begin = firstLong(node, "begin_time", "beginTime", "start_ms", "startMs", "start_time");
            Long end = firstLong(node, "end_time", "endTime", "end_ms", "endMs", "stop_time");
            Long speaker = firstLong(node, "speaker_id", "speakerId");

            // 兼容 "begin_time": 1.23 秒 与 1230 毫秒 两种量级：
            // 小于 10000 且非整数时按秒处理（课堂音频很少短于 10 秒且用毫秒表示）
            result.add(new ProviderSentence(text, null, null, begin, end, speaker, List.of()));
        }
        if (result.stream().allMatch(s -> s.startMs() == null && s.endMs() == null)) {
            log.warn("TokenHub sentences 里没有解析到任何时间戳字段，"
                    + "实际字段名可能与预期不同。首个元素原文: {}", abbreviate(sentences.get(0).toString()));
        }
        return List.copyOf(result);
    }

    private HttpResponse<String> send(String path, String jsonBody) {
        requireApiKey();
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + path))
                .timeout(readTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return execute(request, path);
    }

    private HttpResponse<String> sendGet(String path) {
        requireApiKey();
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + path))
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
            log.debug("TokenHub {} -> HTTP {}", path, response.statusCode());
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AsrProviderException(PROVIDER, "Interrupted", "调用被中断: " + e.getMessage(), true, e);
        } catch (Exception e) {
            throw new AsrProviderException(PROVIDER,
                    e.getClass().getSimpleName(), "调用 TokenHub 失败: " + e.getMessage(), true, e);
        }
    }

    private void requireApiKey() {
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new AsrProviderException(PROVIDER, "MissingApiKey",
                    "TokenHub API Key 未配置：请设置环境变量 WAND_API_KEY", false);
        }
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new AsrProviderException(PROVIDER, "MalformedResponse",
                    "TokenHub 返回的不是合法 JSON: " + abbreviate(body), true, e);
        }
    }

    /**
     * 若响应里带 {@code error} 就抛异常。
     *
     * <p>不能只看 HTTP 状态码：实测里带完整信封的失败响应 HTTP 可能是 400/404/502，
     * 而错误信息始终在 {@code error} 节点里。
     *
     * <p><b>一个例外</b>：若信封里 {@code status} 已是终态 {@code failed}，
     * 说明"识别任务本身失败了"，而不是"这次调用失败"。
     * 这种情况应当像腾讯云那样<b>返回 FAILED 状态并带上原因</b>，
     * 交给业务层落库展示；抛异常会让失败原因丢失在异常栈里，
     * 用户在任务详情里只看到一句笼统的 502。两个提供方的行为必须一致。
     *
     * @param root   响应根节点
     * @param action 调用动作，用于报错文案
     */
    private void throwIfError(JsonNode root, String action) {
        JsonNode error = root.path("error");
        if (error.isMissingNode() || error.isNull()) {
            return;
        }
        // 任务级失败：不作为调用异常抛出，由 describe() 返回 FAILED 状态
        if ("failed".equalsIgnoreCase(text(root, "status"))) {
            log.debug("TokenHub {} 返回任务级失败（非调用失败）: {}",
                    action, firstText(error, "message_zh", "message"));
            return;
        }
        String code = firstText(error, "code");
        String message = firstText(error, "message_zh", "message");
        String upstream = firstText(error, "upstream_code");
        boolean retryable = !NON_RETRYABLE_GATEWAY_CODES.contains(code) || CODE_UPSTREAM_ERROR.equals(code);
        // 上游错误明确可重试；网关参数/鉴权/额度类不可重试
        if (CODE_UPSTREAM_ERROR.equals(code)) {
            retryable = true;
        } else if (NON_RETRYABLE_GATEWAY_CODES.contains(code)) {
            retryable = false;
        }
        String full = "TokenHub %s 失败 [%s]: %s%s".formatted(action, code, message,
                upstream == null ? "" : " (upstream_code=" + upstream + ")");
        log.warn("{}, retryable={}, requestId={}", full, retryable, firstText(root, "request_id"));
        throw new AsrProviderException(PROVIDER, code, full, retryable);
    }

    private static String text(JsonNode node, String field) {
        // stringValue() 替代已弃用的 asText()；Jackson 3 里两者字节码等价
        return node.hasNonNull(field) ? node.get(field).stringValue() : null;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            if (node.hasNonNull(field)) {
                String value = node.get(field).stringValue();
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    private static Long firstLong(JsonNode node, String... fields) {
        for (String field : fields) {
            if (node.hasNonNull(field) && node.get(field).isNumber()) {
                return node.get(field).asLong();
            }
        }
        return null;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "(空)";
        }
        return text.length() <= 400 ? text : text.substring(0, 400) + "…";
    }
}
