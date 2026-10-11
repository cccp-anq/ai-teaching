package com.aijiaoxue.videoreact.asr.client;

/**
 * 腾讯云 TaskStatus.Status 字段的语义映射。
 *
 * <p>腾讯返回的是数字：{@code 0}=等待中、{@code 1}=执行中、{@code 2}=成功、{@code 3}=失败。
 * 该数字只允许出现在 client 层，业务层统一使用本枚举，避免更换提供方时污染上层。
 */
public enum ProviderTaskStatus {

    /** 等待中（0）。 */
    WAITING,

    /** 执行中（1）。 */
    RUNNING,

    /** 识别成功（2）。 */
    SUCCESS,

    /** 识别失败（3）。 */
    FAILED,

    /** 未知状态，视为仍处理中但需告警。 */
    UNKNOWN;

    /**
     * 由腾讯数字状态映射。
     *
     * @param status 腾讯 TaskStatus.Status
     * @return 语义化状态；无法识别时返回 {@link #UNKNOWN}
     */
    public static ProviderTaskStatus fromTencent(Long status) {
        if (status == null) {
            return UNKNOWN;
        }
        return switch (status.intValue()) {
            case 0 -> WAITING;
            case 1 -> RUNNING;
            case 2 -> SUCCESS;
            case 3 -> FAILED;
            default -> UNKNOWN;
        };
    }

    /** 是否为终态。 */
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED;
    }
}
