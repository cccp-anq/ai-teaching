package com.aijiaoxue.course;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aijiaoxue.fixtures.R__Phase1_acceptance_fixture;
import com.aijiaoxue.common.web.RequestIdFilter;
import java.sql.DriverManager;
import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.migration.JavaMigration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * Sprint 1 integration-support lookup endpoints (Round 3.7). Selection sources only:
 * no authorization widening, no teacher directory, anonymous/unauthorized fail closed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(LookupIntegrationTest.FixtureConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional
class LookupIntegrationTest {
    private static final String TEST_PASSWORD = "Fixture-Only-2026!";
    @Autowired WebApplicationContext context;
    @Autowired RequestIdFilter requestIds;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    MockMvc mvc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) throws Exception {
        boolean mysql = Boolean.getBoolean("mysqlMigration");
        String url = mysql ? Objects.requireNonNull(System.getenv("MIGRATION_TEST_URL"))
                : "jdbc:h2:mem:phase1-fixture-lookup;MODE=MySQL;DB_CLOSE_DELAY=-1";
        if (mysql && !url.matches(
                "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/ai_teaching_migration_[a-f0-9]{32}\\?sslMode=DISABLED&allowPublicKeyRetrieval=true"))
            throw new IllegalArgumentException("Dedicated MySQL test database required");
        String user = mysql ? Objects.requireNonNull(System.getenv("MIGRATION_TEST_USERNAME")) : "sa";
        String password = mysql ? Objects.requireNonNull(System.getenv("MIGRATION_TEST_PASSWORD")) : "";
        try (var db = DriverManager.getConnection(url, user, password);
             var tables = db.getMetaData().getTables(db.getCatalog(), mysql ? null : "PUBLIC", "%", new String[]{"TABLE"})) {
            if (tables.next()) throw new IllegalStateException("Lookup suite refuses nonempty databases");
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> user);
        properties.add("spring.datasource.password", () -> password);
        properties.add("spring.datasource.driver-class-name", () -> mysql ? "com.mysql.cj.jdbc.Driver" : "org.h2.Driver");
        properties.add("server.servlet.session.cookie.secure", () -> false);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {
        @Bean JavaMigration lookupFixture() throws Exception { return new R__Phase1_acceptance_fixture(true, true); }
    }

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIds).apply(springSecurity()).build();
    }

    @AfterAll
    void cleanup() throws Exception {
        Flyway.configure().dataSource(dataSource()).locations("classpath:db/migration")
                .javaMigrations(new R__Phase1_acceptance_fixture(false, true)).cleanDisabled(true).load().migrate();
        for (String table : R__Phase1_acceptance_fixture.TABLES)
            org.junit.jupiter.api.Assertions.assertEquals(0,
                    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                            () -> jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)));
    }

    private DataSource dataSource() { return context.getBean(DataSource.class); }

    private MockHttpSession login(String username) throws Exception {
        var session = mvc.perform(post("/api/v1/auth/login").with(csrf())
                        .param("username", username).param("password", TEST_PASSWORD))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        return (MockHttpSession) Objects.requireNonNull(session);
    }

    @Test
    void lookup01AnonymousIsRejectedOnBothLookups() throws Exception {
        mvc.perform(get("/api/v1/lookups/semesters")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/lookups/colleges")).andExpect(status().isUnauthorized());
    }

    @Test
    void lookup02AuthenticatedSemestersAreStableAndComplete() throws Exception {
        mvc.perform(get("/api/v1/lookups/semesters").session(login("admin-test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].semesterId").value(1))
                .andExpect(jsonPath("$[0].semesterCode").value("2026-FALL"))
                .andExpect(jsonPath("$[0].semesterName").value("2026秋"))
                .andExpect(jsonPath("$[1].semesterId").value(2))
                .andExpect(jsonPath("$[1].semesterCode").value("TEST-TERM-B"))
                .andExpect(jsonPath("$[1].semesterName").value("人工测试学期B"))
                .andExpect(jsonPath("$[0].firstWeekDate").doesNotExist())
                .andExpect(jsonPath("$[0].teachingWeekCount").doesNotExist());
    }

    @Test
    void lookup03AuthenticatedCollegesAreStableAndComplete() throws Exception {
        mvc.perform(get("/api/v1/lookups/colleges").session(login("admin-test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].collegeId").value(2))
                .andExpect(jsonPath("$[0].collegeCode").value("TEST-SCOPE-B"))
                .andExpect(jsonPath("$[0].collegeName").value("人工测试学院B"))
                .andExpect(jsonPath("$[1].collegeId").value(1))
                .andExpect(jsonPath("$[1].collegeCode").value("TEST-SOFTWARE"))
                .andExpect(jsonPath("$[1].collegeName").value("软件学院（匿名测试范围）"));
    }

    @Test
    void lookup04CourseReadersBeyondAdminMayReadLookups() throws Exception {
        var teacher = login("teacher-a-test");
        mvc.perform(get("/api/v1/lookups/semesters").session(teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].semesterId").value(1));
        mvc.perform(get("/api/v1/lookups/colleges").session(teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].collegeId").value(1));

        var supervisor = login("supervisor-a-test");
        mvc.perform(get("/api/v1/lookups/semesters").session(supervisor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].semesterId").value(1));
        mvc.perform(get("/api/v1/lookups/colleges").session(supervisor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].collegeId").value(1));

        var director = login("director-a-test");
        mvc.perform(get("/api/v1/lookups/semesters").session(director))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/v1/lookups/colleges").session(director))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void lookup05LoggedInAccountWithoutReadGrantIsForbidden() throws Exception {
        // Enabled account with no user_role_scope rows: authentication succeeds, COURSE_READ fails closed.
        String hash = jdbc.queryForObject("SELECT password_hash FROM user_account WHERE username='admin-test'", String.class);
        jdbc.update("INSERT INTO user_account (username, password_hash, enabled) VALUES ('lookup-no-grant-test', ?, TRUE)", hash);
        var session = login("lookup-no-grant-test");
        mvc.perform(get("/api/v1/lookups/semesters").session(session)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/lookups/colleges").session(session)).andExpect(status().isForbidden());
    }

    @Test
    void lookup06LookupsAreReadOnlyGetResources() throws Exception {
        var session = login("admin-test");
        mvc.perform(post("/api/v1/lookups/semesters").with(csrf()).session(session))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/lookups/colleges").with(csrf()).session(session))
                .andExpect(status().isForbidden());
    }

    @Test
    void lookup07OwnerCollegeOptionsFollowTheAuthorizedDataset() throws Exception {
        // GLOBAL sees every owner college referenced by a mapped offering (1 and 2).
        var admin = login("admin-test");
        mvc.perform(get("/api/v1/lookups/owner-colleges").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].collegeId").value(2))
                .andExpect(jsonPath("$[1].collegeId").value(1));
        // SELF_TEACHER only sees owner colleges of visible offerings (101 maps to college 1).
        mvc.perform(get("/api/v1/lookups/owner-colleges").session(login("teacher-a-test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].collegeId").value(1));
        // SUPERVISOR sees only offering 104 (course_id NULL): no owner college options at all.
        mvc.perform(get("/api/v1/lookups/owner-colleges").session(login("supervisor-a-test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        // Anonymous stays rejected.
        mvc.perform(get("/api/v1/lookups/owner-colleges")).andExpect(status().isUnauthorized());
    }

    @Test
    void lookup08TeacherOptionsStayInsideTheAuthorizedDataset() throws Exception {
        // GLOBAL sees every teacher teaching inside the whole dataset (fixture T1..T5).
        mvc.perform(get("/api/v1/lookups/teachers").session(login("admin-test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].displayName").value("Teacher Alpha"))
                .andExpect(jsonPath("$[0].teacherId").value(1));
        // SELF_TEACHER (teacher-a, T1): co-taught schedules surface T1+T2+T3 but nobody else.
        mvc.perform(get("/api/v1/lookups/teachers").session(login("teacher-a-test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[*].displayName",
                        org.hamcrest.Matchers.containsInAnyOrder("Teacher Alpha", "Teacher Beta", "Teacher Gamma")));
        // SUPERVISOR sees only the teachers of explicit target 104 (T2).
        mvc.perform(get("/api/v1/lookups/teachers").session(login("supervisor-a-test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].displayName").value("Teacher Beta"));
        mvc.perform(get("/api/v1/lookups/teachers")).andExpect(status().isUnauthorized());
    }
}
