package com.aijiaoxue.videoreact.asr.client;

import java.util.List;

/**
 * ASR 提供方客户端抽象。
 *
 * <p>业务层只依赖本接口，因此把腾讯云换成 TokenHub / 阿里云 / Groq 时，
 * 只需要新增一个实现类，{@code AsrService}、数据库表与对外接口都不用动。
 *
 * <p><b>任务 ID 为什么是 String 而不是 Long</b>：不同提供方的 ID 形态不一样——
 * 腾讯云标准 ASR 是数字 TaskId（{@code 9266418}），
 * TokenHub 是字符串（{@code th_1486968752_00_asrproxy_bd1312a94bca812d62576863c1afb380}）。
 * 把它哈希或截断成数字会破坏"用原始 ID 回查提供方"的能力，
 * 所以这里保留提供方的原始形态，数据库列也相应是 VARCHAR。
 *
 * <p><b>凭证检查为什么放在提供方</b>：不同提供方的凭证字段完全不同
 * （腾讯云是 SecretId/SecretKey，TokenHub 是一个 Bearer API Key）。
 * 如果在业务层写死某一家，就会出现"选了 TokenHub 却被要求填 TENCENT_SECRET_ID"
 * 这种自相矛盾的报错。所以由提供方自己回答"我配好了没、缺什么"。
 */
public interface AsrProviderClient {
    default boolean supportsSpeakerLabels(){return false;}

    /**
     * 提交识别任务。
     *
     * @param command 提交参数
     * @return 提供方任务 ID，保持提供方原始形态
     * @throws AsrProviderException 调用失败（网络、鉴权、参数、额度等）
     */
    String submit(AsrSubmitCommand command);

    /**
     * 查询任务状态与结果。
     *
     * @param providerTaskId 提供方任务 ID（原始形态）
     * @return 任务状态
     * @throws AsrProviderException 调用失败
     */
    AsrProviderStatus describe(String providerTaskId);

    /**
     * 提供方标识，用于写入 {@code asr_task.provider}。
     *
     * @return 如 {@code TENCENT} / {@code WAND}
     */
    String providerName();

    /**
     * 凭证是否已配置完整。
     *
     * @return true 表示可以提交任务
     */
    boolean credentialsReady();

    /**
     * 凭证缺失时该怎么做。
     *
     * <p>用于启动自检与 {@code /health} 接口，让使用者一眼看到"缺哪个环境变量"，
     * 而不是去翻服务端日志。
     *
     * @return 待办提示；已配置完整时返回空列表
     */
    List<String> configurationHints();

    /**
     * 当前使用的模型/引擎标识，用于日志与 {@code /health}。
     *
     * @return 如 {@code 16k_zh_en_2.0} 或 {@code wand-asr-v1}
     */
    String modelIdentifier();

    /**
     * 是否支持把音频<b>内联</b>提交（base64/二进制），而不是只接受公网 URL。
     *
     * <p><b>为什么这个能力必须由提供方声明</b>：它决定了 {@code JobRunner} 走哪条路，
     * 而两条路的代价完全不同——
     * <ul>
     *   <li>内联：音频字节不出模块，契约的 sha256 核验语义完整；</li>
     *   <li>仅 URL：必须先把音轨暂存到对象存储再给预签名地址，
     *       多一次上传、多一份副本、多一个清理动作。</li>
     * </ul>
     * 这个差别无法从业务层推断：同样是 HTTP 接口，TokenHub 收 base64，
     * 阿里云 filetrans 明确拒收（官方 FAQ：「不支持Base64编码方式的音频」）。
     *
     * <p><b>默认值为什么是 true</b>：默认值只在新增实现类忘记覆盖时生效。
     * 假设"支持内联"会在运行期得到一个直白的引擎报错（参数不合法）；
     * 反过来假设"不支持"则会让一个本可内联的提供方被迫走暂存，
     * 而暂存当前尚未配置——把一个功能问题伪装成配置问题更难排查。
     *
     * @return true 表示可以提交 {@code AsrSubmitCommand.ofBytes(...)} 构造的命令
     */
    default boolean acceptsInlineAudio() {
        return true;
    }

    /**
     * 通过 URL 提交时，提供方允许的最大文件字节数。
     *
     * <p><b>为什么由提供方回答</b>：这是各家自己的约束，差别很大——
     * 腾讯云标准 ASR 的 Url 方式官方限制 <b>1GB</b>；而 TokenHub 的文档没有给上限
     * （实测 4MB MP4 可直接提交并识别成功）。
     * 如果在业务层写死一个数字，就会出现"用 TokenHub 提交 1.2GB 课堂录像
     * 被本地错误拦下"这种与提供方能力不符的拒绝。
     *
     * @return 上限字节数；无已知上限时返回 {@link Long#MAX_VALUE}
     */
    default long maxInputUrlBytes() {
        return Long.MAX_VALUE;
    }
}
