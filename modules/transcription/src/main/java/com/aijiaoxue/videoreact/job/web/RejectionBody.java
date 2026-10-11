package com.aijiaoxue.videoreact.job.web;

import java.util.List;
import java.util.Map;

/**
 * 契约 §5 的 HTTP 拒绝体。
 *
 * <p>{@code {code, message, retryable, requestId, details}}，
 * 其中 {@code details} 可含 {@code {path, code, message}} 用于指出具体字段。
 *
 * <p>{@code message} 不含堆栈、密钥或媒体 URL（契约明确要求）。
 *
 * @param code      错误码
 * @param message   可读说明
 * @param retryable 是否可重试
 * @param requestId 服务端请求 ID，便于对账
 * @param details   字段级明细
 */
public record RejectionBody(
        String code,
        String message,
        boolean retryable,
        String requestId,
        List<Detail> details) {

    /**
     * 字段级明细。
     *
     * @param path    出错字段路径，如 {@code media.sha256}
     * @param code    明细错误码
     * @param message 说明
     */
    public record Detail(String path, String code, String message) {

        /**
         * 由 Map 构造，便于校验器收集。
         *
         * @param raw 形如 {@code {path, code, message}}
         * @return 明细
         */
        public static Detail of(Map<String, String> raw) {
            return new Detail(raw.get("path"), raw.get("code"), raw.get("message"));
        }
    }

    /**
     * 构造无字段明细的拒绝体。
     *
     * @param code      错误码
     * @param message   说明
     * @param retryable 是否可重试
     * @param requestId 请求 ID
     * @return 拒绝体
     */
    public static RejectionBody of(String code, String message, boolean retryable, String requestId) {
        return new RejectionBody(code, message, retryable, requestId, List.of());
    }
}
