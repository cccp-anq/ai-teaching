package com.aijiaoxue.videoreact.asr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * ASR 模块全部可配置项，前缀 {@code videoreact.asr}。
 *
 * <p>对应环境变量见仓库根目录 {@code .env.example}。
 *
 * @param defaultAudioUrl 默认音频地址；请求未显式传 audioUrl 时使用，便于本地冒烟
 * @param provider        提供方选择（tencent / wand）与各自的连接参数
 * @param poll            轮询兜底配置（第一版不做 Callback）
 * @param engine          提交腾讯云标准 ASR 时使用的引擎参数
 * @param source          音源提交前探测配置
 * @param debug           联调调试开关
 */
@ConfigurationProperties(prefix = "videoreact.asr")
public record AsrProperties(
        String defaultAudioUrl,
        Provider provider,
        Poll poll,
        Engine engine,
        Source source,
        Debug debug) {

    /**
     * 提供方与 HTTP 调用参数。
     *
     * @param type                  提供方标识：{@code tencent}（标准 ASR）、{@code wand}（TokenHub）
     *                              或 {@code aliyun-filetrans}（阿里云百炼非实时语音识别）
     * @param endpoint              腾讯云标准 ASR 的云 API 域名
     * @param connectTimeoutSeconds 连接超时（秒）
     * @param readTimeoutSeconds    读超时（秒）
     * @param tencent               腾讯云标准 ASR 凭证（SecretId/SecretKey 签名）
     * @param wand                  TokenHub（wand-asr-v1）配置：Bearer Key，无需签名
     * @param aliyun                阿里云百炼非实时语音识别配置：Bearer Key + 业务空间专属域名
     */
    public record Provider(
            String type,
            String endpoint,
            int connectTimeoutSeconds,
            int readTimeoutSeconds,
            Tencent tencent,
            Wand wand,
            Aliyun aliyun) {
    }

    /**
     * 阿里云百炼「非实时语音识别」配置。
     *
     * <p>与另外两家的关键差别（均来自官方文档，<b>尚未真机验证</b>，
     * 见 {@code docs/阿里云模型选型.md} 的"文档值与实测值"一节）：
     * <ul>
     *   <li>端点是<b>业务空间专属域名</b> {@code https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api/v1}，
     *       没有真实 WorkspaceId 就调不通；旧域名 {@code dashscope.aliyuncs.com} 仍可用；</li>
     *   <li>流程是<b>提交 + 轮询</b>两步：{@code POST /services/audio/asr/transcription} →
     *       {@code GET /tasks/{task_id}}；</li>
     *   <li><b>拒收内联音频</b>，只接受公网可访问的 URL（官方 FAQ 原文明确）；</li>
     *   <li>成功时只给一个 {@code transcription_url}，结果是<b>另一个 JSON 文件</b>，
     *       需要再下载一次并解析 {@code transcripts[].sentences[]}；</li>
     *   <li>结果与下载链接<b>有效期 24 小时</b>，过期后查不到也下不了；</li>
     *   <li>任务级 {@code end_time} 是<b>字符串日期</b>，而句子级 {@code begin_time}/{@code end_time}
     *       才是<b>毫秒整数</b>——官方专门提示勿混淆，本客户端只读句子级。</li>
     * </ul>
     *
     * <p><b>实测踩到的坑（离线测试抓不到的那类）</b>：
     * <ul>
     *   <li>{@code parameters.channel_id} <b>必须是数组</b>。发标量时提交返回 HTTP 200 + task_id，
     *       任务却在异步阶段以 {@code "parameter.channel_id must be an array"} 失败——
     *       提交阶段完全看不出来。见 {@code AliyunAsrClient.buildParameters()}；</li>
     *   <li>缺 {@code X-DashScope-Async} 头返回的是 <b>403 {@code AccessDenied}</b>（不是 400），
     *       容易被误判成密钥问题；</li>
     *   <li>{@code language_hints} 与 {@code diarization_enabled} <b>实测都被接受且生效</b>，
     *       尽管官方参数表只把 {@code language_hints} 列在 paraformer-v2 名下（文档滞后）；</li>
     *   <li>{@code vocabulary_id} 传不存在的 ID <b>静默忽略</b>、任务照常成功——
     *       热词配错不会有任何提示；</li>
     *   <li>成功响应里 {@code output} <b>嵌了一个同名 {@code output}</b>（文档未提，冗余无害），
     *       有效路径是外层 {@code output.results}；{@code task_metrics} 实测不存在；</li>
     *   <li>音频不可达时只回 {@code {"code":"SERVER_ERROR"}}，<b>连 message 都没有</b>；</li>
     *   <li><b>WorkspaceId 不是必填项</b>：实测公共域名直接接受 {@code sk-ws-…} 形态的 Key。
     *       专属域名官方称性能更稳，生产建议配，但不配也能跑。</li>
     * </ul>
     *
     * @param workspaceId         业务空间 ID；<b>可选</b>。配了就用专属域名，不配走 {@code baseUrl}
     * @param baseUrl             基线地址，如 {@code https://dashscope.aliyuncs.com/api/v1}
     * @param apiKey              百炼 API Key（Bearer）
     * @param model               模型 ID；默认 {@code qwen-audio-3.0-asr-flash-filetrans}（12 小时档）
     * @param languageHints       语种提示；<b>为空表示整个字段都不发</b>。实测 filetrans 接受该参数
     * @param diarizationEnabled  说话人分离；<b>仅支持单声道</b>，多声道会被拒
     * @param speakerCount        说话人数参考值 2–100；0 表示不传、由算法自判
     * @param vocabularyId        定制热词表 ID；为空表示不传。<b>传错不会报错</b>
     * @param channelId           要识别的音轨索引，默认 0（即第一条）；发送时会包装成数组
     */
    public record Aliyun(
            String workspaceId,
            String baseUrl,
            String apiKey,
            String model,
            List<String> languageHints,
            boolean diarizationEnabled,
            int speakerCount,
            String vocabularyId,
            int channelId) {

        /** 北京地域专属域名的后缀。 */
        public static final String BEIJING_DOMAIN_SUFFIX = ".cn-beijing.maas.aliyuncs.com/api/v1";

        /**
         * 归一化：把 {@code language-hints: ""} 这种配置收敛成"不发该字段"。
         *
         * <p>这不是洁癖：从环境变量绑定列表时，空变量会得到一个<code>[""]</code>，
         * 直接发出去就是一条非法语种码，可能让引擎<b>拒掉整个任务</b>——
         * 而配置文件里那一行看起来完全是"留空即默认"的样子。
         * 同理把越界的说话人数夹回 2–100，避免一个手滑的配置变成运行期报错。
         */
        public Aliyun {
            if (languageHints != null) {
                List<String> cleaned = languageHints.stream()
                        .filter(hint -> hint != null && !hint.isBlank())
                        .map(String::trim)
                        .toList();
                languageHints = cleaned.isEmpty() ? null : cleaned;
            }
            if (speakerCount != 0 && diarizationEnabled) {
                speakerCount = Math.max(2, Math.min(100, speakerCount));
            }
        }

        /**
         * 解析出真正要用的基线地址。
         *
         * <p>单独一个方法而不是在构造里拼接：这样"没配 workspaceId 时用了哪个地址"
         * 是个可测的纯函数，而不用启动容器去观察。
         *
         * @return 基线地址，末尾不带斜杠
         */
        public String resolvedBaseUrl() {
            String base = baseUrl == null || baseUrl.isBlank()
                    ? "https://dashscope.aliyuncs.com/api/v1"
                    : baseUrl.trim();
            if (workspaceId != null && !workspaceId.isBlank()) {
                base = "https://" + workspaceId.trim() + BEIJING_DOMAIN_SUFFIX;
            }
            return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        }
    }

    /**
     * TokenHub（{@code wand-asr-v1}）配置。
     *
     * <p>与标准 ASR 的区别（实测，见 {@code docs/TokenHub接口探测记录.md}）：
     * <ul>
     *   <li>鉴权是单个 Bearer API Key，<b>不需要 TC3 签名</b>；</li>
     *   <li>请求体只有 {@code model} + {@code input_url}（或 base64 的 {@code data}）；</li>
     *   <li>有<b>同步</b>接口 {@code /sync_transcribe}，以及异步 {@code /async_create} + {@code /tasks/{id}}；</li>
     *   <li>响应是 OpenAI 风格信封：{@code object/id/request_id/status/error/output}；</li>
     *   <li>成功状态值为 {@code completed}，失败为 {@code failed}。</li>
     * </ul>
     *
     * <p><b>本模块走异步通道</b>（{@code async_create} + 轮询 {@code tasks/{id}}）：
     * 同步接口会阻塞到识别完成，课堂长视频可能阻塞几分钟到几十分钟，
     * 放在 HTTP 请求里会占死 Tomcat 线程并大概率触发网关超时；
     * 异步通道与本模块既有的"提交 + 轮询兜底"状态机天然吻合。
     *
     * @param baseUrl 基地址，如 {@code https://tokenhub.tencentmaas.com}
     * @param apiKey  Bearer API Key
     * @param model   模型/服务 ID，如 {@code wand-asr-v1}
     */
    public record Wand(String baseUrl, String apiKey, String model) {
    }

    /**
     * 腾讯云凭证。SecretKey 永不返回前端、永不提交 Git。
     *
     * @param secretId  腾讯云 SecretId
     * @param secretKey 腾讯云 SecretKey
     * @param region    地域，如 {@code ap-guangzhou}
     */
    public record Tencent(String secretId, String secretKey, String region) {
    }

    /**
     * 轮询兜底配置。
     *
     * <p>腾讯官方说明：录音文件识别为异步接口，最长 3 小时返回，多数情况 1 小时音频 1-3 分钟完成；
     * 识别结果在服务端仅保存 24 小时。因此轮询必须有上限，且需要区分“未完成”和“结果已过期”。
     *
     * @param enabled      是否启用兜底轮询
     * @param interval     扫描间隔
     * @param initialDelay 启动后首次扫描延迟
     * @param batchSize    单次扫描最多处理的任务数
     * @param timeout      超过该时长仍非终态则标记失败，避免任务永久卡在 PROCESSING
     */
    public record Poll(
            boolean enabled,
            Duration interval,
            Duration initialDelay,
            int batchSize,
            Duration timeout) {
    }

    /**
     * 提交腾讯云时使用的引擎参数。
     *
     * <p><b>约束（来自腾讯云 CreateRecTask 官方文档）</b>：
     * <ul>
     *   <li>{@code ChannelNum} 对 16k 音频只能为 1，<b>不可设为双声道</b>；</li>
     *   <li>{@code ResTextFormat=3} 才会返回“词级时间戳 + 标点 + 按标点分段”，本模块依赖该格式；</li>
     *   <li>{@code FilterDirty / FilterPunc / FilterModal / SentenceMaxLength / ConvertNumMode}
     *       官方标注“目前支持中文普通话引擎”，对 {@code 16k_zh_en_2.0} 是否生效<b>待实测</b>，详见 README「待验证项」。</li>
     * </ul>
     *
     * @param modelType         引擎模型类型，默认大模型 2.0 中英引擎
     * @param channelNum        识别声道数，16k 音频固定 1
     * @param resTextFormat     识别结果返回样式，固定 3
     * @param sourceType        音频来源，固定 0（音频 URL）
     * @param speakerDiarization 说话人分离，第一版固定 0（不开启）
     * @param convertNumMode    阿拉伯数字智能转换
     * @param filterDirty       脏词过滤
     * @param filterPunc        标点过滤
     * @param filterModal       语气词过滤
     * @param sentenceMaxLength 单标点最多字数，0 表示关闭
     */
    public record Engine(
            String modelType,
            long channelNum,
            long resTextFormat,
            long sourceType,
            long speakerDiarization,
            long convertNumMode,
            long filterDirty,
            long filterPunc,
            long filterModal,
            long sentenceMaxLength) {
    }

    /**
     * 音源提交前探测配置。
     *
     * <p><b>为什么需要这一层</b>：腾讯云 {@code CreateRecTask} 在 {@code SourceType=0} 时
     * 由腾讯服务器去下载音频，官方限制「时长 ≤ 5 小时、大小 ≤ 1GB」。
     * 课堂视频如果直接以 MP4 提交，45 分钟 1080p 录像常有 1.0–1.7GB，
     * 会撞上 1GB 上限。提交前先探测一次 {@code Content-Length}，
     * 就能在<b>花钱之前</b>给出明确报错，而不是等腾讯回一句模糊的下载失败。
     *
     * <p>探测失败（对方不支持 HEAD、无 Content-Length、网络不可达）时
     * <b>绝不阻断提交</b>，只是少一次提前拦截——否则会把"探测能力不足"
     * 变成"功能不可用"。
     *
     * @param probeBeforeSubmit      是否在提交前探测音源
     * @param probeTimeoutSeconds    探测超时（秒），要短，不能拖慢创建任务
     * @param maxFileSizeBytes       大小上限，默认 1GB（腾讯云 Url 方式上限）
     * @param maxDurationSeconds     时长上限，默认 5 小时（腾讯云 Url 方式上限）
     */
    public record Source(
            boolean probeBeforeSubmit,
            int probeTimeoutSeconds,
            long maxFileSizeBytes,
            long maxDurationSeconds) {

        /** 1GB：腾讯云 Url 方式的文件大小上限。 */
        public static final long TENCENT_URL_SIZE_LIMIT = 1024L * 1024L * 1024L;

        /** 5 小时：腾讯云 Url 方式的时长上限。 */
        public static final long TENCENT_URL_DURATION_LIMIT_SECONDS = 5L * 60L * 60L;
    }

    /**
     * 联调调试开关。
     *
     * @param dumpRawResult 是否把腾讯返回的原始 ResultDetail JSON 落盘
     * @param dumpDir       落盘目录
     */
    public record Debug(boolean dumpRawResult, String dumpDir) {
    }
}
