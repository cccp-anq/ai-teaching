package com.aijiaoxue.videoreact.job.web;

import com.aijiaoxue.videoreact.job.config.ContractProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 机器凭证鉴权（契约 §6）。
 *
 * <p>契约要求：「主项目→模块用每部署/模块独立 {@code Bearer} 凭证，HTTPS；
 * 凭证从环境/密钥管理注入，支持轮换；<b>不放 JSON fixture、前端、URL 或 Git</b>」。
 *
 * <p>实现要点：
 * <ul>
 *   <li>只保护 {@code /api/v1/**}，{@code /health} 也保护——
 *       因为契约允许「可做内部访问限制」，交给部署决定是否开放；</li>
 *   <li>比对用<b>常量时间</b>算法，避免通过响应时间侧信道逐字节猜凭证；</li>
 *   <li>401 不透露"这个 token 差几位"，只回统一错误体；</li>
 *   <li>未配置任何凭证时<b>拒绝全部请求</b>而不是放行——
 *       "忘了配凭证"必须是失败关闭，不能变成无鉴权裸奔。</li>
 * </ul>
 */
@Component
@org.springframework.core.annotation.Order(2)
public class BearerAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BearerAuthFilter.class);

    private static final String PREFIX = "Bearer ";

    private final ContractProperties contract;
    private final ObjectMapper objectMapper;

    public BearerAuthFilter(ContractProperties contract, ObjectMapper objectMapper) {
        this.contract = contract;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 只保护契约路由；其他路径（如根路径）不经过本过滤器
        return !request.getRequestURI().startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String token = extractToken(header);

        if (token == null || !isKnownToken(token)) {
            reject(response, request.getHeader("X-Request-Id"));
            return;
        }
        chain.doFilter(request, response);
    }

    private static String extractToken(String header) {
        if (header == null || !header.startsWith(PREFIX)) {
            return null;
        }
        String token = header.substring(PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * 常量时间比对。
     *
     * <p>用 {@link MessageDigest#isEqual} 而不是 {@code String.equals}：
     * 后者在首个不同字符处短路返回，攻击者可据响应时间逐字节还原凭证。
     */
    private boolean isKnownToken(String token) {
        var tokens = contract.bearerTokens();
        if (tokens == null || tokens.isEmpty()) {
            log.error("未配置任何机器凭证（videoreact.contract.bearer-tokens），"
                    + "按失败关闭策略拒绝请求");
            return false;
        }
        byte[] candidate = token.getBytes(StandardCharsets.UTF_8);
        boolean matched = false;
        for (String known : tokens) {
            if (known == null || known.isBlank()) {
                continue;
            }
            // 不短路：无论是否匹配都比对完，避免因提前返回泄漏信息
            matched |= MessageDigest.isEqual(candidate, known.getBytes(StandardCharsets.UTF_8));
        }
        return matched;
    }

    private void reject(HttpServletResponse response, String requestId) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        RejectionBody body = RejectionBody.of(
                ContractErrors.UNAUTHENTICATED,
                "缺少或无效的机器凭证",
                false,
                requestId == null ? "" : requestId);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
