package com.aijiaoxue;

import com.aijiaoxue.common.api.PageQuery;
import com.aijiaoxue.common.web.RequestIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.WebApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Import({ApplicationTest.ProbeController.class, ApplicationTest.ProbeSecurity.class})
class ApplicationTest {
    @Autowired WebApplicationContext context;
    @Autowired RequestIdFilter requestIdFilter;
    @Autowired SqlSessionFactory sessions;
    @Autowired Flyway flyway;
    MockMvc mvc;
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter).apply(springSecurity()).build();
    }
    @Test void contextStartsWithMybatisAndFlywayAtCurrentMigration() throws Exception {
        assertEquals(20, flyway.info().all().length);
        assertEquals("20", flyway.info().current().getVersion().toString());
        assertEquals(0, flyway.info().pending().length);
        try (var session = sessions.openSession(); var statement = session.getConnection().createStatement();
             var result = statement.executeQuery("SELECT 1")) {
            assertTrue(result.next()); assertEquals(1, result.getInt(1));
        }
    }
    @Test void statusAndRequestIdAreAvailable() throws Exception {
        mvc.perform(get("/api/v1/system/status").header("X-Request-ID", "req_123"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"))
            .andExpect(header().string("X-Request-ID", "req_123"));
        assertNull(MDC.get("requestId"));
    }
    @Test void untrustedRequestIdIsReplacedAndMdcIsCleared() throws Exception {
        var response = mvc.perform(get("/api/v1/system/status").header("X-Request-ID", "bad value"))
            .andExpect(status().isOk()).andReturn().getResponse();
        assertNotEquals("bad value", response.getHeader("X-Request-ID"));
        assertDoesNotThrow(() -> java.util.UUID.fromString(response.getHeader("X-Request-ID")));
        assertNull(MDC.get("requestId"));
    }
    @Test void productionBusinessPathsAreDeniedEvenToAuthenticatedUsers() throws Exception {
        mvc.perform(get("/api/v1/courses").header("X-Request-ID", "denied"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            .andExpect(jsonPath("$.requestId").value("denied"));
        mvc.perform(get("/api/v1/courses").with(user("test")))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/api/v1/system/status").with(user("test")))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
    @Test void validationAndMalformedJsonUseUnifiedErrors() throws Exception {
        mvc.perform(post("/api/v1/_test/validate").with(user("test")).with(csrf())
                .contentType("application/json").content("{\"name\":\"\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));
        mvc.perform(post("/api/v1/_test/validate").with(user("test")).with(csrf())
                .contentType("application/json").content("{"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("HTTP_400"));
        mvc.perform(get("/api/v1/_test/page?size=101").with(user("test")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("size"));
    }
    @Test void unexpectedErrorsHideDetailsAndPreserveRequestId() throws Exception {
        mvc.perform(get("/api/v1/_test/fail").with(user("test")).header("X-Request-ID", "failure"))
            .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.message").value("Internal server error"))
            .andExpect(jsonPath("$.requestId").value("failure"));
        assertNull(MDC.get("requestId"));
    }
    @RestController
    static class ProbeController {
        record Input(@NotBlank String name) {}
        @PostMapping("/_test/validate") Map<String,String> validate(@Valid @RequestBody Input input) { return Map.of("name", input.name()); }
        @GetMapping("/_test/page") PageQuery page(@Valid @ModelAttribute PageQuery query) { return query; }
        @GetMapping("/_test/fail") void fail() { throw new IllegalStateException("private implementation details"); }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeSecurity {
        @Bean @Order(0)
        SecurityFilterChain testEndpoints(HttpSecurity http) throws Exception {
            return http.securityMatcher("/api/v1/_test/**").authorizeHttpRequests(r -> r.anyRequest().authenticated()).build();
        }
    }
}
