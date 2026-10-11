package com.aijiaoxue.videoreact.job.web;

import com.aijiaoxue.videoreact.job.config.ContractProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 请求体体积上限（契约 §6：超过上限返回 413）。
 *
 * <p><b>为什么需要显式拦截</b>：审计实测发现，配置里声明了 {@code max-body-bytes}
 * 却<b>没有任何读取点</b>——2MB 的请求体照样被受理。这让交接表里的数值变成假声明，
 * 主项目无法据此做容量规划，模块也可能在真实联调时被超大请求打爆内存。
 *
 * <p>拦截时机选在进入业务逻辑<b>之前</b>，并按契约返回
 * {@code 413 PAYLOAD_TOO_LARGE}，而不是等反序列化完再报错
 * （反序列化本身就会先把整个 body 读进内存，那时拦截已经晚了）。
 *
 * <p>用 {@code Content-Length} 判断而不读流：分块传输没有该头时按 411 之外的
 * 情况处理——放行给容器自身的上限兜底，不做会破坏正常请求的猜测。
 */
@Component
@org.springframework.core.annotation.Order(3)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestSizeLimitFilter.class);

    private final ContractProperties contract;
    private final ObjectMapper objectMapper;

    public RequestSizeLimitFilter(ContractProperties contract, ObjectMapper objectMapper) {
        this.contract = contract;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 只对会带请求体的方法生效
        String method = request.getMethod();
        return !request.getRequestURI().startsWith("/api/v1/") || !("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        long limit = contract.maxBodyBytes();
        long declared = request.getContentLengthLong();
        if (limit > 0 && declared > limit) {
            log.warn("拒绝超大请求体: path={}, contentLength={}, limit={}",
                    request.getRequestURI(), declared, limit);
            reject(response, declared, limit, request.getHeader("X-Request-Id"));
            return;
        }
        byte[] bytes = request.getInputStream().readNBytes((int)Math.min(Integer.MAX_VALUE-1,limit)+1);
        if (bytes.length > limit) { reject(response, bytes.length, limit, request.getHeader("X-Request-Id")); return; }
        chain.doFilter(new com.aijiaoxue.videoreact.app.BoundedBodyRequest(request,bytes), response);
    }

    private void reject(
            HttpServletResponse response, long declared, long limit, String requestId)
            throws IOException {
        response.setStatus(413);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        RejectionBody body = RejectionBody.of(
                ContractErrors.PAYLOAD_TOO_LARGE,
                "请求体 " + declared + " 字节超过本部署上限 " + limit + " 字节",
                false,
                requestId == null ? "" : requestId);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
