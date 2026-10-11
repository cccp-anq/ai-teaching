package com.aijiaoxue;

import com.aijiaoxue.identity.bootstrap.DemoBootstrapRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.DriverManager;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Round 3.8 BOOT demo face: the demo profile bootstraps an anonymous, idempotent demo
 * world (semester/college/formal-course+description/offering/schedules/accounts) on a
 * fresh database, and re-running it changes nothing. The password comes from the
 * DEMO_PASSWORD property and is only stored BCrypt-hashed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=demo",
        "DEMO_PASSWORD=demo-only-password",
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoBootstrapIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired DemoBootstrapRunner runner;
    MockMvc mvc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) throws Exception {
        String url = "jdbc:h2:mem:demo-bootstrap-" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (var db = DriverManager.getConnection(url, "sa", "");
             var tables = db.getMetaData().getTables(db.getCatalog(), "PUBLIC", "%", new String[]{"TABLE"})) {
            if (tables.next()) throw new IllegalStateException("demo bootstrap suite refuses nonempty databases");
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> "sa");
        properties.add("spring.datasource.password", () -> "");
        properties.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        properties.add("server.servlet.session.cookie.secure", () -> false);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    @Test
    void demo01BootstrapCreatesAnonymousDemoWorldAndIsIdempotent() throws Exception {
        assertEquals(2, count("semester"), "semester");
        assertEquals(2, count("college"), "college");
        assertEquals(5, count("teacher"), "teacher");
        assertEquals(2, count("course"), "course");
        assertEquals(4, count("course_offering"), "offering");
        assertEquals(5, count("class_schedule"), "schedule");
        assertEquals(4, count("user_account"), "account");

        // Formal-course facts for QRY/CMP acceptance: exactly one course carries a description,
        // the other stays NULL, and exactly one offering is unmapped (course_id NULL).
        Long withDescription = jdbc.queryForObject(
                "SELECT COUNT(*) FROM course WHERE description IS NOT NULL", Long.class);
        assertEquals(1L, withDescription);
        Long unmapped = jdbc.queryForObject(
                "SELECT COUNT(*) FROM course_offering WHERE course_id IS NULL", Long.class);
        assertEquals(1L, unmapped);

        // Idempotency: re-running the runner changes no row counts.
        long offerings = count("course_offering");
        long accounts = count("user_account");
        runner.run(null);
        assertEquals(offerings, count("course_offering"));
        assertEquals(accounts, count("user_account"));

        // Passwords are stored hashed only; login works through the real security chain.
        String hash = jdbc.queryForObject("SELECT password_hash FROM user_account WHERE username='demo-admin'", String.class);
        org.junit.jupiter.api.Assertions.assertFalse(
                hash.contains("demo-only-password"), () -> "password must never be stored in clear text");
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var csrfResponse = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        var session = (org.springframework.mock.web.MockHttpSession) csrfResponse.getRequest().getSession(false);
        String token = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(csrfResponse.getResponse().getContentAsString()).get("token").asString();
        var login = mvc.perform(post("/api/v1/auth/login").session(session)
                        .header("X-CSRF-TOKEN", token)
                        .param("username", "demo-admin").param("password", "demo-only-password"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("demo-admin")).andReturn();
        var loggedIn = (org.springframework.mock.web.MockHttpSession) login.getRequest().getSession(false);
        mvc.perform(get("/api/v1/course-offerings").session(loggedIn))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4));
    }
}
