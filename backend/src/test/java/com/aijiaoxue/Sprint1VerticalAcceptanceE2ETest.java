package com.aijiaoxue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.aijiaoxue.fixtures.R__Phase1_acceptance_fixture;
import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.migration.JavaMigration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sprint 1 vertical acceptance (Round 3.7): real HTTP over the fully wired application
 * and the shared phase1 fixture, walking login → import → query → detail → comparison
 * plus the fail-closed error states. No test transaction protects server-side writes,
 * so @AfterAll removes every business row and asserts the fourteen tables are empty.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(Sprint1VerticalAcceptanceE2ETest.FixtureConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Sprint1VerticalAcceptanceE2ETest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TEST_PASSWORD = "Fixture-Only-2026!";
    private static final String ANONYMOUS_FIXTURE = "/fixtures/timetable-parser-anonymous.html";
    private static final Path REAL_SNAPSHOT = Path.of("..", ".work", "course-source-page.html")
            .toAbsolutePath().normalize();

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;

    HttpClient http;
    CookieManager cookies;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) throws Exception {
        boolean mysql = Boolean.getBoolean("mysqlMigration");
        String url = mysql ? Objects.requireNonNull(System.getenv("MIGRATION_TEST_URL"))
                : "jdbc:h2:mem:phase1-fixture-vertical-e2e;MODE=MySQL;DB_CLOSE_DELAY=-1";
        if (mysql && !url.matches(
                "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/ai_teaching_migration_[a-f0-9]{32}\\?sslMode=DISABLED&allowPublicKeyRetrieval=true"))
            throw new IllegalArgumentException("Dedicated MySQL test database required");
        String user = mysql ? Objects.requireNonNull(System.getenv("MIGRATION_TEST_USERNAME")) : "sa";
        String password = mysql ? Objects.requireNonNull(System.getenv("MIGRATION_TEST_PASSWORD")) : "";
        try (var db = DriverManager.getConnection(url, user, password);
             var tables = db.getMetaData().getTables(db.getCatalog(), mysql ? null : "PUBLIC", "%", new String[]{"TABLE"})) {
            if (tables.next()) throw new IllegalStateException("Vertical acceptance refuses nonempty databases");
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> user);
        properties.add("spring.datasource.password", () -> password);
        properties.add("spring.datasource.driver-class-name", () -> mysql ? "com.mysql.cj.jdbc.Driver" : "org.h2.Driver");
        properties.add("server.servlet.session.cookie.secure", () -> false);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {
        @Bean JavaMigration verticalFixture() throws Exception { return new R__Phase1_acceptance_fixture(true, true); }
    }

    @BeforeAll
    void startClient() {
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @BeforeEach
    void freshSession() {
        cookies = new CookieManager();
        http = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Reverse-FK deletion order for the fourteen business tables; verified against TABLES in cleanup. */
    private static final List<String> CLEANUP_ORDER = List.of(
            "source_timetable_row", "schedule_week", "schedule_teacher", "class_schedule",
            "teaching_resource", "supervision_evaluation", "import_batch", "user_role_scope",
            "user_account", "course_offering", "course", "location", "teacher", "role", "semester", "college");

    @AfterAll
    void cleanup() throws Exception {
        CLEANUP_ORDER.forEach(table -> jdbc.update("DELETE FROM " + table));
        assertEquals(R__Phase1_acceptance_fixture.TABLES.size(), CLEANUP_ORDER.size());
        for (String table : R__Phase1_acceptance_fixture.TABLES)
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class),
                    () -> "table not cleaned: " + table);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .javaMigrations(new R__Phase1_acceptance_fixture(false, true)).cleanDisabled(true).load().migrate();
    }

    // ---- raw HTTP helpers -------------------------------------------------

    private String base() { return "http://127.0.0.1:" + port + "/api/v1"; }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(base() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> postForm(String path, String csrfToken, String... keyValue)
            throws IOException, InterruptedException {
        var body = new StringBuilder();
        for (int i = 0; i < keyValue.length; i += 2) {
            if (i > 0) body.append('&');
            body.append(java.net.URLEncoder.encode(keyValue[i], StandardCharsets.UTF_8)).append('=')
                    .append(java.net.URLEncoder.encode(keyValue[i + 1], StandardCharsets.UTF_8));
        }
        var builder = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (csrfToken != null) builder.header("X-CSRF-TOKEN", csrfToken);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> postMultipart(String path, String csrfToken, String filename, byte[] file,
                                               String... keyValue) throws IOException, InterruptedException {
        String boundary = "aijiaoxue-vertical-e2e";
        var bytes = new java.io.ByteArrayOutputStream();
        var parts = new StringBuilder();
        for (int i = 0; i < keyValue.length; i += 2) {
            parts.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"").append(keyValue[i]).append("\"\r\n\r\n")
                    .append(keyValue[i + 1]).append("\r\n");
        }
        bytes.write(parts.toString().getBytes(StandardCharsets.UTF_8));
        bytes.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + filename + "\"\r\nContent-Type: text/html\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        bytes.write(file);
        bytes.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var builder = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray()));
        if (csrfToken != null) builder.header("X-CSRF-TOKEN", csrfToken);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String freshCsrfToken() throws IOException, InterruptedException {
        var response = get("/auth/csrf");
        assertEquals(200, response.statusCode(), () -> response.body());
        return JSON.readTree(response.body()).get("token").asString();
    }

    private JsonNode login(String username) throws IOException, InterruptedException {
        var token = freshCsrfToken();
        var response = postForm("/auth/login", token, "username", username, "password", TEST_PASSWORD);
        assertEquals(200, response.statusCode(), () -> response.body());
        return JSON.readTree(response.body());
    }

    private JsonNode importTimetable(String username, String csrfToken, String semesterId,
                                     String collegeId, String filename, byte[] file, int expectedStatus)
            throws IOException, InterruptedException {
        boolean freshSession = csrfToken == null;
        var response = postMultipart("/imports/timetable", freshSession ? freshCsrfToken() : csrfToken,
                filename, file, "semesterId", semesterId, "sourceScopeCollegeId", collegeId);
        assertEquals(expectedStatus, response.statusCode(), () -> response.body());
        return JSON.readTree(response.body());
    }

    private byte[] classpathHtml() throws IOException {
        try (var stream = Objects.requireNonNull(
                Sprint1VerticalAcceptanceE2ETest.class.getResourceAsStream(ANONYMOUS_FIXTURE), "fixture missing")) {
            return stream.readAllBytes();
        }
    }

    // ---- vertical happy path ----------------------------------------------

    @Test
    @Order(1)
    void vertical01LoginImportQueryDetailComparisonWithoutBreakingInvariants() throws Exception {
        assertEquals(401, get("/course-offerings").statusCode(), "anonymous query must be rejected");
        var admin = login("admin-test");
        assertNotEquals("", admin.get("username").asString());
        assertTrue(admin.get("roles").size() >= 1);
        assertEquals(200, get("/auth/me").statusCode());

        var semesters = JSON.readTree(get("/lookups/semesters").body());
        var colleges = JSON.readTree(get("/lookups/colleges").body());
        assertEquals(2, semesters.size());
        assertEquals(2, colleges.size());

        long semester1Baseline = JSON.readTree(get("/course-offerings?semesterId=1&size=1").body())
                .get("total").asLong();
        byte[] html = classpathHtml();
        var first = importTimetable("admin-test", null, "1", "1", "acceptance.html", html, 200);
        assertEquals("SUCCESS", first.get("status").asString());
        assertEquals(9, first.get("totalRows").intValue());
        assertEquals(9, first.get("successRows").intValue());
        assertEquals(0, first.get("failureRows").intValue());
        assertEquals(9, first.get("createdOfferings").intValue());
        assertTrue(first.get("batchId").asLong() > 0);

        var repeated = importTimetable("admin-test", null, "1", "1", "acceptance.html", html, 200);
        assertEquals("ALREADY_IMPORTED", repeated.get("status").asString());
        assertEquals(first.get("batchId").asLong(), repeated.get("batchId").asLong());

        var query = JSON.readTree(get("/course-offerings?semesterId=1&size=100").body());
        assertEquals(semester1Baseline + 9, query.get("total").asLong());
        assertEquals((int) semester1Baseline + 9, query.get("items").size());
        long offeringId = 0;
        boolean sawZeroStudents = false;
        for (var item : query.get("items")) {
            offeringId = item.get("offeringId").asLong();
            assertFalse(item.get("teacherNames").isEmpty());
            if (item.get("studentCount").asInt() == 0) sawZeroStudents = true;
        }
        assertTrue(sawZeroStudents, "p=0 source rows must surface as studentCount=0");
        // Fixture 107 provides one ONLINE and one TBD schedule; the anonymous fixture adds one of each.
        assertEquals(2, JSON.readTree(get("/course-offerings?semesterId=1&location=%E7%BA%BF%E4%B8%8A").body())
                .get("total").asLong(), "ONLINE location must remain queryable (fixture + imported)");
        assertEquals(2, JSON.readTree(get("/course-offerings?semesterId=1&location=%E5%BE%85%E5%AE%9A").body())
                .get("total").asLong(), "TBD location must remain queryable (fixture + imported)");

        var detail = JSON.readTree(get("/course-offerings/" + offeringId).body());
        assertEquals(offeringId, detail.get("offeringId").asLong());
        assertTrue(detail.get("schedules").size() >= 1);
        for (var schedule : detail.get("schedules")) {
            assertFalse(schedule.get("weeks").isEmpty(), () -> schedule.toString());
            assertFalse(schedule.get("teachers").isEmpty(), () -> schedule.toString());
            assertTrue(schedule.get("location").get("locationType").asString().matches("PHYSICAL|ONLINE|TBD"));
        }

        // AC-03 vertical: UI-style combined params must keep the Same-Schedule semantics end to end.
        String ac03Name = java.net.URLEncoder.encode("AC-03时间地点-测试", StandardCharsets.UTF_8);
        String locationA = java.net.URLEncoder.encode("测试地点A", StandardCharsets.UTF_8);
        String locationB = java.net.URLEncoder.encode("测试地点B", StandardCharsets.UTF_8);
        assertEquals(1, JSON.readTree(get("/course-offerings?courseName=" + ac03Name + "&week=1&location=" + locationA).body())
                .get("total").asLong(), "AC-03: week1 + location A must hit through the HTTP chain");
        assertEquals(1, JSON.readTree(get("/course-offerings?courseName=" + ac03Name + "&week=2&location=" + locationB).body())
                .get("total").asLong(), "AC-03: week2 + location B must hit through the HTTP chain");
        assertEquals(0, JSON.readTree(get("/course-offerings?courseName=" + ac03Name + "&week=1&location=" + locationB).body())
                .get("total").asLong(), "AC-03: week1 + location B must not match (different schedules)");
        assertEquals(0, JSON.readTree(get("/course-offerings?courseName=" + ac03Name + "&week=2&location=" + locationA).body())
                .get("total").asLong(), "AC-03: week2 + location A must not match (different schedules)");

        var comparison = JSON.readTree(get("/teachers/comparison?teacherIds=1,2,3,4,5").body());
        assertEquals(5, comparison.get("teachers").size());
        var coTaught = firstOfferingOf(comparison, 0, 102L);
        assertTrue(hasTeacher(coTaught.get("teachingSchedules").get(0), 2L), "co-teaching must keep both teachers");
        var second = firstOfferingOf(comparison, 1, 102L);
        assertTrue(hasTeacher(second.get("teachingSchedules").get(0), 1L), "co-teaching must keep both teachers");
    }

    private JsonNode firstOfferingOf(JsonNode comparison, int teacherIndex, long offeringId) {
        for (var offering : comparison.get("teachers").get(teacherIndex).get("offerings"))
            if (offering.get("offeringId").asLong() == offeringId) return offering;
        throw new AssertionError("offering " + offeringId + " missing from teacher index " + teacherIndex);
    }

    private boolean hasTeacher(JsonNode schedule, long teacherId) {
        for (var teacher : schedule.get("teachers"))
            if (teacher.get("teacherId").asLong() == teacherId) return true;
        return false;
    }

    // ---- fail-closed error states -----------------------------------------

    @Test
    @Order(2)
    void vertical02UnauthorizedUnknownAndInvalidRequestsFailClosed() throws Exception {
        var admin = login("admin-test");
        assertEquals(403, postForm("/auth/login", null, "username", "admin-test", "password", TEST_PASSWORD)
                .statusCode(), "login without CSRF must be forbidden");

        var unknownTeacher = get("/teachers/comparison?teacherIds=99");
        assertEquals(422, unknownTeacher.statusCode());
        assertEquals("UNKNOWN_TEACHER", JSON.readTree(unknownTeacher.body()).get("code").asString());
        assertEquals(400, get("/teachers/comparison?teacherIds=1,2,3,4,5,6").statusCode());

        var invisible = get("/course-offerings/999999");
        assertEquals(404, invisible.statusCode());
        assertEquals("NOT_VISIBLE", JSON.readTree(invisible.body()).get("code").asString());
        assertFalse(JSON.readTree(invisible.body()).has("displayName"), "hidden offerings must not leak fields");

        // The endpoint declares consumes=multipart, so a urlencoded POST is rejected by the framework.
        assertEquals(415, postForm("/imports/timetable", freshCsrfToken()).statusCode());

        var teacher = login("teacher-a-test");
        assertEquals(200, get("/course-offerings/101").statusCode(), "authorized own-schedule offering is visible");
        var forbiddenDetail = get("/course-offerings/109");
        assertEquals(404, forbiddenDetail.statusCode(), "out-of-scope detail must be hidden as NOT_VISIBLE");
        assertEquals("NOT_VISIBLE", JSON.readTree(forbiddenDetail.body()).get("code").asString());
        var teacherImport = postMultipart("/imports/timetable", freshCsrfToken(), "x.html", classpathHtml(),
                "semesterId", "1", "sourceScopeCollegeId", "1");
        assertEquals(403, teacherImport.statusCode(),
                () -> "non-admin import must be forbidden, got: " + teacherImport.body());
    }

    @Test
    @Order(3)
    void vertical03OversizedUploadReturnsImportResultFailure() throws Exception {
        login("admin-test");
        var result = importTimetable("admin-test", null, "1", "1", "oversized.html",
                new byte[5 * 1024 * 1024 + 1], 413);
        assertEquals("FAILED", result.get("status").asString());
        assertEquals("FILE_TOO_LARGE", result.get("errors").get(0).get("errorCode").asString());
    }

    // ---- optional real 538-row vertical smoke ------------------------------

    @Test
    @Order(4)
    void vertical04RealSnapshotVerticalSmokeWhenAvailableLocally() throws Exception {
        assumeTrue(Files.isRegularFile(REAL_SNAPSHOT), () -> REAL_SNAPSHOT + " not present; smoke skipped");
        login("admin-test");
        long semester2Baseline = JSON.readTree(get("/course-offerings?semesterId=2&size=1").body())
                .get("total").asLong();
        byte[] html = Files.readAllBytes(REAL_SNAPSHOT);
        var result = importTimetable("admin-test", null, "2", "2", "real-snapshot.html", html, 200);
        assertEquals("SUCCESS", result.get("status").asString());
        assertEquals(538, result.get("totalRows").intValue());
        assertEquals(538, result.get("successRows").intValue());

        var query = JSON.readTree(get("/course-offerings?semesterId=2&size=1").body());
        assertEquals(semester2Baseline + result.get("createdOfferings").asLong(), query.get("total").asLong());

        List<Long> importedTeachers = jdbc.queryForList(
                "SELECT id FROM teacher WHERE source_teacher_key IS NOT NULL ORDER BY id LIMIT 5",
                Long.class);
        assertEquals(5, importedTeachers.size());
        var comparison = JSON.readTree(get("/teachers/comparison?teacherIds="
                + importedTeachers.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElseThrow()).body());
        assertEquals(5, comparison.get("teachers").size());
        List<Integer> offeringCounts = new ArrayList<>();
        List<Integer> scheduleCounts = new ArrayList<>();
        for (var teacher : comparison.get("teachers")) {
            int offerings = teacher.get("offerings").size();
            int schedules = 0;
            for (var offering : teacher.get("offerings")) {
                schedules += offering.get("teachingSchedules").size();
                for (var schedule : offering.get("teachingSchedules"))
                    assertTrue(schedule.get("teachers").size() >= 1);
            }
            offeringCounts.add(offerings);
            scheduleCounts.add(schedules);
        }
        System.out.println("REAL-538 vertical smoke (anonymous): teacher_groups=5 offering_counts="
                + offeringCounts + " schedule_counts=" + scheduleCounts + " result=PASS");
    }
}
