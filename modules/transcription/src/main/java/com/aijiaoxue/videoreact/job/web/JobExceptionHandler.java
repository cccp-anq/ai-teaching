package com.aijiaoxue.videoreact.job.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.UUID;

/**
 * 契约 §5 的统一拒绝体。
 *
 * <p>把三类失败映射成契约要求的形状：
 * <ol>
 *   <li>{@link ContractRejectionException}：显式状态 + 错误码 + 字段明细；</li>
 *   <li>JSON 解析失败：<b>400 MALFORMED_JSON</b>（契约明确"修复请求，不重试"）；</li>
 *   <li>其他未预期异常：<b>500</b>，且<b>不回显异常细节</b>——
 *       契约要求 message 不含堆栈、密钥或媒体 URL。</li>
 * </ol>
 */
@RestControllerAdvice
public class JobExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(JobExceptionHandler.class);

    /**
     * 显式契约拒绝。
     *
     * @param e 异常
     * @return 拒绝体
     */
    @ExceptionHandler(ContractRejectionException.class)
    public ResponseEntity<RejectionBody> handleContract(ContractRejectionException e) {
        RejectionBody body = new RejectionBody(
                e.code(), e.getMessage(), e.retryable(), requestId(), e.details());
        return ResponseEntity.status(e.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * 请求体无法反序列化 → 400 MALFORMED_JSON。
     *
     * <p>不回显解析器的原始信息：它可能包含请求片段（例如媒体 URL）。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<RejectionBody> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("请求体无法解析: {}", e.getClass().getSimpleName());
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(RejectionBody.of(ContractErrors.MALFORMED_JSON,
                        "请求体不是合法的 JSON 或字段类型不匹配", false, requestId()));
    }

    /**
     * 缺少必需的请求头。
     *
     * @param e 异常
     * @return 拒绝体
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<RejectionBody> handleMissingHeader(MissingRequestHeaderException e) {
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(RejectionBody.of(ContractErrors.VALIDATION_FAILED,
                        "缺少必需的请求头: " + e.getHeaderName(), false, requestId()));
    }

    /**
     * 兜底：不泄漏内部细节。
     *
     * @param e 异常
     * @return 拒绝体
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<RejectionBody> handleUnexpected(RuntimeException e) {
        log.error("处理请求时发生未预期异常，requestId={}", requestId(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RejectionBody(ContractErrors.PROCESSING_FAILED,
                        "服务内部错误，请按 requestId 对账",
                        true, requestId(), List.of()));
    }

    /** 取当前请求 ID；没有就生成一个，保证响应里始终可对账。 */
    private static String requestId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            String header = servletAttributes.getRequest().getHeader("X-Request-Id");
            if (header != null && !header.isBlank()) {
                return header;
            }
        }
        return UUID.randomUUID().toString();
    }
}
