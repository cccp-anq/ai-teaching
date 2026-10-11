package com.aijiaoxue.importing.timetable;

import com.aijiaoxue.identity.CurrentUser;
import com.aijiaoxue.identity.CurrentUserProvider;
import com.aijiaoxue.identity.PermissionService;
import com.aijiaoxue.importing.web.TimetableMultipartBodyLimitFilter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TimetableUploadLimitHttpTest.UploadSecurityConfiguration.class)
class TimetableUploadLimitHttpTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String DATABASE_URL = "jdbc:h2:mem:timetable_upload_limit_"
            + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DB_CLOSE_DELAY=-1";

    @Value("${local.server.port}") int port;
    @MockitoBean CurrentUserProvider currentUsers;
    @MockitoBean PermissionService permissions;

    @BeforeEach
    void authorizeImportServiceCalls() {
        when(currentUsers.requireCurrentUser()).thenReturn(mock(CurrentUser.class));
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> DATABASE_URL);
        properties.add("spring.datasource.username", () -> "sa");
        properties.add("spring.datasource.password", () -> "");
        properties.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        properties.add("server.servlet.session.cookie.secure", () -> false);
    }

    @Test
    void realServletRejectsOversizedMultipartBeforeControllerParsing() throws Exception {
        var result = oversized("/api/v1/imports/timetable?semesterId=1&sourceScopeCollegeId=1",
                "oversized.html", "text/html");
        assertEquals("FAILED", result.body().get("status").asText());
        assertEquals("Transport", result.body().get("errors").get(0).get("domain").asText());
    }

    private UploadResult oversized(String path, String fileName, String contentType) throws Exception {
        String boundary = "aijiaoxue-upload-limit";
        byte[] prefix = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] source = new byte[(int)TimetableMultipartBodyLimitFilter.MAX_REQUEST_BYTES + 1];
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        var request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.concat(
                        HttpRequest.BodyPublishers.ofByteArray(prefix),
                        HttpRequest.BodyPublishers.ofByteArray(source),
                        HttpRequest.BodyPublishers.ofByteArray(suffix)))
                .build();

        var response = HttpClient.newHttpClient().send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        var result = JSON.readTree(response.body());

        assertEquals(413, response.statusCode());
        assertEquals(0, result.get("totalRows").asInt());
        assertEquals("FILE_TOO_LARGE", result.get("errors").get(0).get("errorCode").asText());
        return new UploadResult(response.statusCode(), result);
    }

    private record UploadResult(int status, tools.jackson.databind.JsonNode body) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class UploadSecurityConfiguration {
        @Bean
        @Order(0)
        SecurityFilterChain oversizedUploadSecurity(HttpSecurity http) throws Exception {
            return http.securityMatcher("/api/v1/imports/**")
                    .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                    .csrf(csrf -> csrf.disable())
                    .build();
        }
    }
}
