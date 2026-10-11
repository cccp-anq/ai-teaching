package com.aijiaoxue;

import com.aijiaoxue.fixtures.R__Phase1_acceptance_fixture;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.Objects;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import com.aijiaoxue.supervision.evaluation.SupervisionBatchWriter;
import com.aijiaoxue.supervision.evaluation.SupervisionImportStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sprint 3 acceptance: DAT-04 evaluation import plus the EVA-01/02/03 coverage summary
 * and drilldowns. The eligible/covered/uncovered numbers follow the provisional
 * require1 §4.7 formula (final C-02 rules stay TBD); covered/uncovered reuse one SQL
 * predicate, and every scenario proves eligible = covered + uncovered.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SupervisionCoverageIntegrationTest.FixtureConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional
class SupervisionCoverageIntegrationTest {
    private static final String TEST_PASSWORD = "Fixture-Only-2026!";
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired SupervisionBatchWriter batchWriter;
    MockMvc mvc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) throws Exception {
        boolean mysql = Boolean.getBoolean("mysqlMigration");
        String url = mysql ? Objects.requireNonNull(System.getenv("MIGRATION_TEST_URL"))
                : "jdbc:h2:mem:phase1-fixture-coverage;MODE=MySQL;DB_CLOSE_DELAY=-1";
        if (mysql && !url.matches(
                "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/ai_teaching_migration_[a-f0-9]{32}\\?sslMode=DISABLED&allowPublicKeyRetrieval=true"))
            throw new IllegalArgumentException("Dedicated MySQL test database required");
        String user = mysql ? Objects.requireNonNull(System.getenv("MIGRATION_TEST_USERNAME")) : "sa";
        String password = mysql ? Objects.requireNonNull(System.getenv("MIGRATION_TEST_PASSWORD")) : "";
        try (var db = DriverManager.getConnection(url, user, password);
             var tables = db.getMetaData().getTables(db.getCatalog(), mysql ? null : "PUBLIC", "%", new String[]{"TABLE"})) {
            if (tables.next()) throw new IllegalStateException("Coverage suite refuses nonempty databases");
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> user);
        properties.add("spring.datasource.password", () -> password);
        properties.add("spring.datasource.driver-class-name", () -> mysql ? "com.mysql.cj.jdbc.Driver" : "org.h2.Driver");
        properties.add("server.servlet.session.cookie.secure", () -> false);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {
        @Bean JavaMigration coverageFixture() throws Exception { return new R__Phase1_acceptance_fixture(true, true); }
    }

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(com.aijiaoxue.common.web.RequestIdFilter.class))
                .apply(springSecurity()).build();
    }

    @AfterAll
    void cleanup() throws Exception {
        Flyway.configure().dataSource(context.getBean(javax.sql.DataSource.class)).locations("classpath:db/migration")
                .javaMigrations(new R__Phase1_acceptance_fixture(false, true)).cleanDisabled(true).load().migrate();
        for (String table : R__Phase1_acceptance_fixture.TABLES)
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
    }

    private MockHttpSession login(String username) throws Exception {
        var session = mvc.perform(post("/api/v1/auth/login").with(csrf())
                        .param("username", username).param("password", TEST_PASSWORD))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        return (MockHttpSession) Objects.requireNonNull(session);
    }

    private static String textRequest(String sourceName, String... rows) throws Exception {
        return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(
                java.util.Map.of("sourceName", sourceName, "text", String.join("\n", rows)));
    }

    @Test
    void oversizedEvaluationJsonIsRejectedBeforeDeserialization() throws Exception {
        String body="{\"sourceName\":\"large.txt\",\"text\":\""
                +"a".repeat(6*1024*1024)+"\"}";
        mvc.perform(post("/api/v1/imports/supervision-evaluations").contentType("application/json")
                        .content(body).with(csrf()).session(login("admin-test")))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.errors[0].errorCode").value("TEXT_TOO_LARGE"));
    }

    @Test
    void eva01ImportFailsClosedForAnonymousNonAdminAndMissingCsrf() throws Exception {
        mvc.perform(post("/api/v1/imports/supervision-evaluations").contentType("application/json")
                        .content(textRequest("empty.txt", "101 已记录"))
                        .with(csrf()).session(login("admin-test")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/imports/supervision-evaluations").contentType("application/json")
                        .content(textRequest("empty.txt", "101 已记录")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/imports/supervision-evaluations").contentType("application/json")
                        .content(textRequest("empty.txt", "101 已记录"))
                        .session(login("supervisor-a-test")).with(csrf()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/api/v1/imports/supervision-evaluations").contentType("application/json")
                        .content(textRequest("empty.txt", "101 已记录"))
                        .session(login("admin-test")))
                .andExpect(status().isForbidden());
    }

    @Test
    void eva02ImportKeepsMultipleEvaluationsAndIsIdempotent() throws Exception {
        var session = login("admin-test");
        // Two COMPLETED records for the same offering are preserved verbatim.
        String mixedHabits = textRequest("evaluations.txt",
                "104\t已完成\t2026年8月30日 10:00",
                "开课编号：104，状态：COMPLETED，完成时间：2026/8/30 11:00");
        mvc.perform(post("/api/v1/imports/supervision-evaluations").contentType("application/json")
                        .content(mixedHabits)
                        .with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedEvaluations").value(2));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM supervision_evaluation WHERE course_offering_id=104", Integer.class));
        // Same file again → ALREADY_IMPORTED, no duplicate rows.
        mvc.perform(post("/api/v1/imports/supervision-evaluations").contentType("application/json")
                        .content(mixedHabits).with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ALREADY_IMPORTED"));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM supervision_evaluation WHERE course_offering_id=104", Integer.class));
        // A different file adds real records: multiple evaluations stay uncompressed.
        mvc.perform(post("/api/v1/imports/supervision-evaluations").contentType("application/json")
                        .content(textRequest("another.txt", "104 | 完成 | 2026-08-30 12:00:00"))
                        .with(csrf()).session(session))
                .andExpect(status().isOk());
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM supervision_evaluation WHERE course_offering_id=104", Integer.class));
    }

    @Test
    void eva03InvalidRowsAreRejectedWithoutAnyWrite() throws Exception {
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM supervision_evaluation", Long.class);
        mvc.perform(post("/api/v1/imports/supervision-evaluations").contentType("application/json")
                        .content(textRequest("invalid.txt", "101,COMPLETED,2026-08-30 10:00:00", "999999,COMPLETED,2026-08-30 10:00:00"))
                        .with(csrf()).session(login("admin-test")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failureRows").value(2))
                .andExpect(jsonPath("$.errors[0].errorCode").value("OFFERING_UNKNOWN"));
        mvc.perform(post("/api/v1/imports/supervision-evaluations").contentType("application/json")
                        .content(textRequest("invalid.txt", "101 | |"))
                        .with(csrf()).session(login("admin-test")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].errorCode").value("STATUS_INVALID"));
        mvc.perform(post("/api/v1/imports/supervision-evaluations").contentType("application/json")
                        .content(textRequest("invalid.txt", "101 COMPLETED not-a-date"))
                        .with(csrf()).session(login("admin-test")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].errorCode").value("COMPLETED_AT_INVALID"));
        mvc.perform(post("/api/v1/imports/supervision-evaluations").contentType("application/json")
                        .content(textRequest("e.csv", "101 COMPLETED"))
                        .with(csrf()).session(login("admin-test")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].errorCode").value("FILE_TYPE_UNSUPPORTED"));
        mvc.perform(post("/api/v1/imports/supervision-evaluations").contentType("application/json")
                        .content(textRequest("e.txt", "这是一段无法识别的评价"))
                        .with(csrf()).session(login("admin-test")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].errorCode").value("UNRECOGNIZED_ROW"));
        assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM supervision_evaluation", Long.class),
                "all-or-nothing: no partial evaluation writes");
    }

    @Test
    void eva04SummaryFollowsProvisionalFormulaWithDedupeAndZeroDenominator() throws Exception {
        var admin = login("admin-test");
        // Fixture baseline: 101 covered (two completed evaluations), 102 covered, 103 RECORDED only.
        mvc.perform(get("/api/v1/supervision/coverage").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(10))
                .andExpect(jsonPath("$.covered").value(2))
                .andExpect(jsonPath("$.uncovered").value(8))
                .andExpect(jsonPath("$.coverageRate").value(0.2));
        // Semester filter: 110 alone, uncovered → a real 0% (denominator exists).
        mvc.perform(get("/api/v1/supervision/coverage?semesterId=2").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(1))
                .andExpect(jsonPath("$.covered").value(0))
                .andExpect(jsonPath("$.uncovered").value(1))
                .andExpect(jsonPath("$.coverageRate").value(0.0));
        // Owner college filter (formal ownership only): 109 → uncovered.
        mvc.perform(get("/api/v1/supervision/coverage?ownerCollegeId=2").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(1))
                .andExpect(jsonPath("$.covered").value(0));
        // Zero denominator: no offering matches both filters; rate is null, never 0%.
        mvc.perform(get("/api/v1/supervision/coverage?semesterId=2&ownerCollegeId=2").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(0))
                .andExpect(jsonPath("$.covered").value(0))
                .andExpect(jsonPath("$.uncovered").value(0))
                .andExpect(jsonPath("$.coverageRate").doesNotExist());
    }

    @Test
    void eva05DrilldownCountsAlwaysSumToEligibleWithoutDuplicates() throws Exception {
        var admin = login("admin-test");
        var covered = mvc.perform(get("/api/v1/supervision/coverage/covered").session(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var uncovered = mvc.perform(get("/api/v1/supervision/coverage/uncovered").session(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        long coveredCount = mapper.readTree(covered).size();
        long uncoveredCount = mapper.readTree(uncovered).size();
        var summary = mapper.readTree(mvc.perform(get("/api/v1/supervision/coverage").session(admin))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(summary.get("covered").asLong(), coveredCount, "covered list must equal summary.covered");
        assertEquals(summary.get("uncovered").asLong(), uncoveredCount, "uncovered list must equal summary.uncovered");
        assertEquals(summary.get("eligible").asLong(), coveredCount + uncoveredCount);
        // Dedupe: offering 101 has two completed evaluations but appears exactly once.
        java.util.Set<String> coveredIds = new java.util.HashSet<>();
        for (var item : mapper.readTree(covered)) coveredIds.add(item.get("offeringId").asText());
        assertEquals(java.util.Set.of("101", "102"), coveredIds);
        // Non-completed record (103) never counts as covered.
        java.util.Set<String> uncoveredIds = new java.util.HashSet<>();
        for (var item : mapper.readTree(uncovered)) uncoveredIds.add(item.get("offeringId").asText());
        assertTrue(uncoveredIds.contains("103"));
        assertFalse(coveredIds.contains("103"));
    }

    @Test
    void eva07SingleSnapshotResponseKeepsSummaryAndListsConsistent() throws Exception {
        var body = mvc.perform(get("/api/v1/supervision/coverage/view").session(login("admin-test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.eligible").value(10))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var root = tools.jackson.databind.json.JsonMapper.builder().build().readTree(body);
        assertEquals(root.get("summary").get("covered").asInt(), root.get("covered").size());
        assertEquals(root.get("summary").get("uncovered").asInt(), root.get("uncovered").size());
        assertEquals(root.get("summary").get("eligible").asInt(),
                root.get("covered").size() + root.get("uncovered").size());
    }

    @Test
    void eva08ConcurrentSameHashCreatesOnlyOneSuccessfulBatch() throws Exception {
        String hash = "eva-concurrency-" + System.nanoTime();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var tasks = List.of(1, 2).stream().map(ignored -> executor.submit(() -> {
                ready.countDown();
                start.await();
                return batchWriter.apply("same.csv", hash, 1, List.of());
            })).toList();
            ready.await();
            start.countDown();
            var statuses = tasks.stream().map(task -> {
                try { return task.get().status(); }
                catch (Exception failure) { throw new RuntimeException(failure); }
            }).collect(java.util.stream.Collectors.toSet());
            assertEquals(java.util.Set.of(SupervisionImportStatus.SUCCESS,
                    SupervisionImportStatus.ALREADY_IMPORTED), statuses);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM import_batch WHERE source_type='SUPERVISION_EVALUATION_TEXT' AND source_sha256=?",
                Integer.class, hash));
        try (var connection = context.getBean(javax.sql.DataSource.class).getConnection();
             var statement = connection.prepareStatement(
                     "DELETE FROM import_batch WHERE source_type='SUPERVISION_EVALUATION_TEXT' AND source_sha256=?")) {
            statement.setString(1, hash);
            statement.executeUpdate();
        }
    }

    private void assertTrue(boolean condition) { org.junit.jupiter.api.Assertions.assertTrue(condition); }
    private void assertFalse(boolean condition) { org.junit.jupiter.api.Assertions.assertFalse(condition); }

    @Test
    void eva06CoverageNeverEscapesTheAuthorizedDataset() throws Exception {
        // teacher-a (SELF_TEACHER): dataset 101..104 → eligible 4, covered 2 (101/102).
        mvc.perform(get("/api/v1/supervision/coverage").session(login("teacher-a-test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(4))
                .andExpect(jsonPath("$.covered").value(2))
                .andExpect(jsonPath("$.uncovered").value(2));
        // supervisor-a (OFFERING 104): eligible 1, uncovered 1 — no leak of other data.
        mvc.perform(get("/api/v1/supervision/coverage").session(login("supervisor-a-test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(1))
                .andExpect(jsonPath("$.covered").value(0))
                .andExpect(jsonPath("$.uncovered").value(1))
                .andExpect(jsonPath("$.coverageRate").value(0.0));
        var supervisorUncovered = mvc.perform(get("/api/v1/supervision/coverage/uncovered")
                        .session(login("supervisor-a-test")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(supervisorUncovered.contains("104") && !supervisorUncovered.contains("101"));
        // Anonymous coverage access stays rejected.
        mvc.perform(get("/api/v1/supervision/coverage")).andExpect(status().isUnauthorized());
    }
}
