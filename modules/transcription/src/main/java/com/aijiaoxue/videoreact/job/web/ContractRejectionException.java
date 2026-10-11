package com.aijiaoxue.videoreact.job.web;

import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/**
 * 契约拒绝异常：带 HTTP 状态与错误码，由 {@code JobExceptionHandler} 统一转成拒绝体。
 *
 * <p>刻意不复用通用 {@code IllegalArgumentException}：契约要求区分
 * {@code 422 VALIDATION_FAILED} 与 {@code 409 IDEMPOTENCY_CONFLICT} 等语义，
 * 用状态码+码对承载才能让主项目做出正确决策。
 */
public class ContractRejectionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final String code;
    private final boolean retryable;
    private final List<RejectionBody.Detail> details;

    public ContractRejectionException(
            HttpStatus status,
            String code,
            String message,
            boolean retryable,
            List<RejectionBody.Detail> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    /**
     * 422：字段取值非法。
     *
     * <p>用 {@code HttpStatus.UNPROCESSABLE_CONTENT}：Spring 7（Boot 4）按 RFC 9110
     * 把 {@code UNPROCESSABLE_ENTITY} 改名并标记弃用。<b>状态码仍是 422</b>，
     * 只有 HTTP 原因短语从 {@code Unprocessable Entity} 变成 {@code Unprocessable Content}。
     * 契约按状态码与 {@code code} 判断，主项目不受影响。
     *
     * @param message 说明
     * @param details 字段明细
     * @return 异常
     */
    public static ContractRejectionException validation(String message, List<RejectionBody.Detail> details) {
        return new ContractRejectionException(HttpStatus.UNPROCESSABLE_CONTENT,
                ContractErrors.VALIDATION_FAILED, message, false, details);
    }

    /**
     * 422：不支持的大版本。
     *
     * @param message 说明
     * @return 异常
     */
    public static ContractRejectionException unsupportedSchema(String message) {
        return new ContractRejectionException(HttpStatus.UNPROCESSABLE_CONTENT,
                ContractErrors.UNSUPPORTED_SCHEMA, message, false, List.of());
    }

    /**
     * 409：同一 clientRequestId 但内容不同。
     *
     * @param message 说明
     * @return 异常
     */
    public static ContractRejectionException idempotencyConflict(String message) {
        return new ContractRejectionException(HttpStatus.CONFLICT,
                ContractErrors.IDEMPOTENCY_CONFLICT, message, false, List.of());
    }

    /**
     * 403：禁止（含媒体主机不在白名单）。
     *
     * @param message 说明
     * @return 异常
     */
    public static ContractRejectionException forbidden(String message) {
        return new ContractRejectionException(HttpStatus.FORBIDDEN,
                ContractErrors.FORBIDDEN, message, false, List.of());
    }

    /**
     * 404：不存在或无权。
     *
     * @param message 说明
     * @return 异常
     */
    public static ContractRejectionException notFound(String message) {
        return new ContractRejectionException(HttpStatus.NOT_FOUND,
                ContractErrors.NOT_FOUND, message, false, List.of());
    }

    /**
     * 413：请求体过大。
     *
     * <p>注意这里有两套"名字"，别搞混：
     * <ul>
     *   <li>{@code HttpStatus.CONTENT_TOO_LARGE} 是 <b>Spring 的枚举名</b>。
     *       Spring 7（Boot 4）已把 {@code HttpStatus.PAYLOAD_TOO_LARGE} 标记为弃用，
     *       按 RFC 9110 改名为 {@code CONTENT_TOO_LARGE}；<b>状态码仍是 413</b>，语义不变；</li>
     *   <li>{@code ContractErrors.PAYLOAD_TOO_LARGE} 是<b>我们自己的契约错误码</b>，
     *       由契约 §5 定义，不随 Spring 改名而动。</li>
     * </ul>
     * 所以响应体里的 {@code code} 依然是 {@code PAYLOAD_TOO_LARGE}，
     * 变的只是 HTTP 原因短语（{@code Content Too Large}）。主项目按 code 判断，不受影响。
     *
     * @param message 说明
     * @return 异常
     */
    public static ContractRejectionException payloadTooLarge(String message) {
        return new ContractRejectionException(HttpStatus.CONTENT_TOO_LARGE,
                ContractErrors.PAYLOAD_TOO_LARGE, message, false, List.of());
    }

    /**
     * 由字段明细构造一个校验异常的便捷方法。
     *
     * @param path    字段路径
     * @param detail  说明
     * @return 明细
     */
    public static RejectionBody.Detail detail(String path, String detail) {
        return new RejectionBody.Detail(path, ContractErrors.VALIDATION_FAILED, detail);
    }

    /**
     * 由 Map 列表构造明细（便于从校验器直接搬运）。
     *
     * @param raw 原始明细
     * @return 明细列表
     */
    public static List<RejectionBody.Detail> detailsOf(List<Map<String, String>> raw) {
        return raw.stream().map(RejectionBody.Detail::of).toList();
    }

    /** HTTP 状态。 */
    public HttpStatus status() {
        return status;
    }

    /** 契约错误码。 */
    public String code() {
        return code;
    }

    /** 是否可重试。 */
    public boolean retryable() {
        return retryable;
    }

    /** 字段明细。 */
    public List<RejectionBody.Detail> details() {
        return details;
    }
}
