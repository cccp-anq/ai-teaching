package com.aijiaoxue.videoreact.asr.staging;

/**
 * 暂存失败。
 *
 * <p>{@code retryable} 直接决定任务终态的 {@code error.retryable}：
 * 上传超时/5xx 属于可重试，桶不存在/凭证无效/额度不足属于不可重试。
 * 把它混成一个布尔值而不是让调用方看异常类型，是因为
 * {@code JobRunner} 只关心"要不要让主项目重试"这一个是非判断。
 */
public class AudioStagingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    private final boolean retryable;

    /**
     * @param code      简短错误码，用于日志
     * @param message   面向排障的说明
     * @param retryable 是否可重试
     */
    public AudioStagingException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    /**
     * @param code      简短错误码
     * @param message   说明
     * @param retryable 是否可重试
     * @param cause     原因
     */
    public AudioStagingException(String code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    /** 错误码。 */
    public String code() {
        return code;
    }

    /** 是否可重试。 */
    public boolean retryable() {
        return retryable;
    }
}
