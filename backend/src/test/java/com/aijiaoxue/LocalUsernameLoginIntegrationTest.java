package com.aijiaoxue;

import com.aijiaoxue.fixtures.R__Phase1_acceptance_fixture;
import org.flywaydb.core.api.migration.JavaMigration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:phase1-fixture-local-username-login;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "server.address=127.0.0.1", "app.identity.username-only-login-enabled=true",
        "app.identity.username-only-login-confirmation=LOCAL_DEVELOPMENT_ONLY"
})
@Import(LocalUsernameLoginIntegrationTest.Fixture.class)
class LocalUsernameLoginIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test void usernameOnlyLoginUsesExistingAccountAndDatabaseGrants() throws Exception {
        mvc.perform(get("/api/v1/auth/login-mode"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.usernameOnly").value(true));

        var admin = login("admin-test");
        mvc.perform(get("/api/v1/auth/me").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin-test"))
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"));
        var teacher = login("teacher-a-test");
        mvc.perform(get("/api/v1/auth/me").session(teacher)).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("teacher-a-test"))
                .andExpect(jsonPath("$.roles", hasItem("TEACHER")));

        mvc.perform(post("/api/v1/auth/login").with(csrf()).param("username", "missing-test"))
                .andExpect(status().isUnauthorized());
        jdbc.update("UPDATE user_account SET enabled=FALSE WHERE username='teacher-b-test'");
        mvc.perform(post("/api/v1/auth/login").with(csrf()).param("username", "teacher-b-test"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").param("username", "admin-test"))
                .andExpect(status().isForbidden());
        org.junit.jupiter.api.Assertions.assertEquals(2,jdbc.queryForObject(
                "SELECT COUNT(*) FROM username_only_login_audit",Integer.class));
    }

    private MockHttpSession login(String username) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/v1/auth/login").with(csrf())
                .param("username", username)).andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }

    @Test void modeCannotStartOnNonLoopbackAddress() {
        UserDetailsService users = username -> { throw new IllegalStateException("unused"); };
        var environment = new org.springframework.mock.env.MockEnvironment().withProperty("server.address", "0.0.0.0");
        assertThrows(IllegalStateException.class,
                () -> new com.aijiaoxue.identity.authentication.LocalUsernameAuthenticationProvider(users, environment));
    }

    @Test void modeRequiresDedicatedConfirmationAndRejectsProductionProfile() {
        UserDetailsService users = username -> { throw new IllegalStateException("unused"); };
        var missing = new org.springframework.mock.env.MockEnvironment().withProperty("server.address", "127.0.0.1");
        assertThrows(IllegalStateException.class,
                () -> new com.aijiaoxue.identity.authentication.LocalUsernameAuthenticationProvider(users, missing));
        var production = new org.springframework.mock.env.MockEnvironment()
                .withProperty("server.address", "127.0.0.1")
                .withProperty("app.identity.username-only-login-confirmation", "LOCAL_DEVELOPMENT_ONLY");
        production.setActiveProfiles("production");
        assertThrows(IllegalStateException.class,
                () -> new com.aijiaoxue.identity.authentication.LocalUsernameAuthenticationProvider(users, production));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixture {
        @Bean JavaMigration usernameFixture() throws Exception {
            return new R__Phase1_acceptance_fixture(true, true);
        }
    }
}
