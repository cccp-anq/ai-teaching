package com.aijiaoxue.common.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-ID";
    public static final String ATTRIBUTE = RequestIdFilter.class.getName();
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        String id = supplied != null && SAFE.matcher(supplied).matches() ? supplied : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader(HEADER, id);
        String previous = MDC.get("requestId");
        MDC.put("requestId", id);
        try { chain.doFilter(request, response); }
        finally {
            if (previous == null) MDC.remove("requestId"); else MDC.put("requestId", previous);
        }
    }
}
