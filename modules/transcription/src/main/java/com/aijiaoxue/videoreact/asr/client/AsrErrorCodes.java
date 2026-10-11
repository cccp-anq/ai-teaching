package com.aijiaoxue.videoreact.asr.client;

import java.util.Set;

/**
 * 提供方错误码语义工具。
 *
 * <p>把「错误码 → 业务后果」的判断集中在一处，避免 service 与 client 各写一份
 * 而在后续更换提供方时漏改。
 */
public final class AsrErrorCodes {

    /**
     * 「识别结果已过期」类错误码。
     *
     * <p>腾讯云录音文件识别的结果<b>仅在服务端保存 24 小时</b>，超时后查询会返回
     * 任务不存在。这属于可预期的业务情况，应当给出独立提示让用户重新发起转写，
     * 而不是笼统报"查询失败"。
     */
    private static final Set<String> EXPIRED_CODES = Set.of(
            "FailedOperation.TaskNotFound",
            "FailedOperation.TaskIdNotFound",
            "ResourceNotFound");

    private AsrErrorCodes() {
    }

    /**
     * 判断异常是否表示提供方侧结果已过期。
     *
     * @param exception 提供方异常
     * @return true 表示结果已过期，重试无意义，需要重新提交识别
     */
    public static boolean isResultExpired(AsrProviderException exception) {
        String code = exception == null ? null : exception.providerErrorCode();
        return code != null && EXPIRED_CODES.contains(code);
    }

    /**
     * 判断错误码是否属于结果过期。
     *
     * @param providerErrorCode 提供方错误码
     * @return true 表示结果已过期
     */
    public static boolean isExpiredCode(String providerErrorCode) {
        return providerErrorCode != null && EXPIRED_CODES.contains(providerErrorCode);
    }
}
