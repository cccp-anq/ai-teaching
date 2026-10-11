package com.aijiaoxue.importing.web;

import com.aijiaoxue.importing.timetable.TimetableImportError;
import com.aijiaoxue.importing.timetable.TimetableImportResult;
import com.aijiaoxue.importing.timetable.TimetableImportStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** Rejects a timetable multipart request before the servlet container creates a large temporary part. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class TimetableMultipartBodyLimitFilter extends OncePerRequestFilter {
    public static final long MAX_REQUEST_BYTES=6L*1024*1024;
    private final ObjectMapper json;

    public TimetableMultipartBodyLimitFilter(ObjectMapper json) { this.json=json; }

    @Override
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        if(isTimetableMultipart(request)) {
            long length=request.getContentLengthLong();
            boolean streamed=length<0 && request.getHeader("Transfer-Encoding")!=null;
            if(length>MAX_REQUEST_BYTES||streamed) {
                response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                json.writeValue(response.getOutputStream(),TimetableImportResult.rejected(
                        TimetableImportStatus.FAILED,0,List.of(new TimetableImportError(
                                null,"file","Transport","FILE_TOO_LARGE",
                                "HTML multipart request exceeds the 6 MiB transport limit"))));
                return;
            }
        }
        chain.doFilter(request,response);
    }

    private static boolean isTimetableMultipart(HttpServletRequest request) {
        String contentType=request.getContentType();
        return "POST".equalsIgnoreCase(request.getMethod())
                && "/api/v1/imports/timetable".equals(request.getRequestURI())
                && contentType!=null&&contentType.toLowerCase(java.util.Locale.ROOT)
                    .startsWith(MediaType.MULTIPART_FORM_DATA_VALUE);
    }
}
