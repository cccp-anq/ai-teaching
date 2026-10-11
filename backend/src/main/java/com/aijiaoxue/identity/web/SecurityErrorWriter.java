package com.aijiaoxue.identity.web;

import com.aijiaoxue.common.api.ApiError;
import com.aijiaoxue.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class SecurityErrorWriter {
    private final ObjectMapper mapper;
    public SecurityErrorWriter(ObjectMapper mapper) { this.mapper = mapper; }
    public void write(HttpServletRequest request, HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        mapper.writeValue(response.getOutputStream(), new ApiError(code, "Access denied", request.getRequestURI(),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE), List.of()));
    }
}
