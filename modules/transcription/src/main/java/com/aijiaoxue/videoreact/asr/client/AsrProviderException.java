package com.aijiaoxue.videoreact.asr.client;

/**
 * ASR 提供方调用异常。
 *
 * <p>刻意把腾讯云 SDK 的 {@code TencentCloudSDKException} 挡在 client 层，
 * 业务层与上层接口只依赖本异常，换提供方时不需要改异常处理。
 */
public class AsrProviderException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String provider;
    private final String providerErrorCode;
    private final boolean retryable;

    public AsrProviderException(String provider, String providerErrorCode, String message, boolean retryable) {
        this(provider, providerErrorCode, message, retryable, null);
    }

    public AsrProviderException(
            String provider,
            String providerErrorCode,
            String message,
            boolean retryable,
            Throwable cause) {
        super(message, cause);
        this.provider = provider;
        this.providerErrorCode = providerErrorCode;
        this.retryable = retryable;
    }

    /** 提供方标识，如 {@code TENCENT}。 */
    public String provider() {
        return provider;
    }

    /** 提供方错误码，如 {@code FailedOperation.UserHasNoFreeAmount}；可能为 null。 */
    public String providerErrorCode() {
        return providerErrorCode;
    }

    /**
     * 是否值得重试。
     *
     * <p>限频、内部错误、网络超时属于可重试；鉴权失败、参数错误、额度耗尽属于不可重试，
     * 直接标记任务失败，避免无意义地反复调用。
     */
    public boolean retryable() {
        return retryable;
    }

    /**
     * 供上层记录用的错误码，形如 {@code TENCENT:FailedOperation.ErrorRecognize}。
     *
     * @return 带提供方前缀的错误码
     */
    public String displayCode() {
        return provider == null ? providerErrorCode : provider + ":" + providerErrorCode;
    }
}
