package com.aijiaoxue.videoreact.asr.client;

import java.util.List;

/**
 * 查询识别任务状态的结果。
 *
 * <p>腾讯云 {@code DescribeTaskStatus} 的返回比较"松散"：状态、整段文本、分段明细、
 * 时长放在同一层。此处收敛成不可变记录，业务层不再接触 SDK 类型。
 *
 * @param providerTaskId 提供方任务 ID（原始字符串形态）
 * @param status         语义化状态
 * @param statusText     提供方原始状态文本，仅用于日志与错误提示
 * @param result         整段拼接后的纯文本
 * @param sentences      分段明细
 * @param audioDuration  音频时长（秒）
 * @param errorMessage   失败时的错误信息
 */
public record AsrProviderStatus(
        String providerTaskId,
        ProviderTaskStatus status,
        String statusText,
        String result,
        List<ProviderSentence> sentences,
        Double audioDuration,
        String errorMessage) {

    /** 是否已成功且带有可解析的分段明细。 */
    public boolean hasSentences() {
        return sentences != null && !sentences.isEmpty();
    }

    /** 整段文本长度，用于日志。 */
    public int resultLength() {
        return result == null ? 0 : result.length();
    }
}
