package com.aijiaoxue.importing.timetable;

import com.aijiaoxue.identity.authentication.AccountPrincipal;
import com.aijiaoxue.importing.SourceKeys;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(TimetableImportIntegrationTest.FailureConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TimetableImportIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long COLLEGE = 9101;
    private static final long SEMESTER = 9102;
    private static final long ADMIN = 9103;
    private static final long ORDINARY = 9104;
    private static final LocationLookupRaceGate LOCATION_RACE_GATE = new LocationLookupRaceGate();
    private static final String[] CLEAN_ORDER = {
            "schedule_teacher", "schedule_week", "source_timetable_row", "user_role_scope",
            "class_schedule", "import_batch", "user_account", "role", "course_offering",
            "teacher", "location", "course", "semester", "college"
    };

    @Autowired TimetableImportService imports;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext context;
    @Autowired ControllableCheckpoint checkpoint;
    MockMvc mvc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        boolean mysql = Boolean.getBoolean("mysqlMigration");
        String url = mysql ? Objects.requireNonNull(System.getenv("MIGRATION_TEST_URL"))
                : "jdbc:h2:mem:timetable_import_" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        if (mysql && !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/ai_teaching_migration_[a-f0-9]{32}"
                + "\\?sslMode=DISABLED&allowPublicKeyRetrieval=true")) {
            throw new IllegalArgumentException("Dedicated MySQL test database required");
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> mysql
                ? Objects.requireNonNull(System.getenv("MIGRATION_TEST_USERNAME")) : "sa");
        properties.add("spring.datasource.password", () -> mysql
                ? Objects.requireNonNull(System.getenv("MIGRATION_TEST_PASSWORD")) : "");
        properties.add("spring.datasource.driver-class-name", () -> mysql
                ? "com.mysql.cj.jdbc.Driver" : "org.h2.Driver");
        properties.add("server.servlet.session.cookie.secure", () -> false);
    }

    @BeforeEach
    void prepare() {
        clearSecurity();
        clean();
        seedReferencesAndAccounts();
        checkpoint.fail.set(false);
        LOCATION_RACE_GATE.clear();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void cleanup() {
        clearSecurity();
        checkpoint.fail.set(false);
        LOCATION_RACE_GATE.clear();
        clean();
    }

    @AfterAll
    void emptyAfterSuite() {
        for (String table : CLEAN_ORDER) {
            assertEquals(0, count(table));
        }
    }

    @Test
    void imp01_02_05_06_07_08_09_10_normalizesTheAnonymousHtmlIntoAllSourceAndDomainTables()
            throws Exception {
        byte[] html = resource("/fixtures/timetable-parser-anonymous.html");

        TimetableImportResult result = importAsAdmin("anonymous.html", html);

        assertEquals(TimetableImportStatus.SUCCESS, result.status());
        assertEquals(9, result.totalRows());
        assertEquals(9, result.createdOfferings());
        assertEquals(9, result.createdSchedules());
        assertEquals(9, result.createdTeachers());
        assertEquals(9, result.createdLocations());
        assertCounts(1, 9, 9, 9, 37, 9, 10, 9);
        assertEquals(1, number("SELECT COUNT(*) FROM course_offering WHERE student_count=0"));
        assertEquals(1, number("SELECT COUNT(*) FROM source_timetable_row WHERE raw_p=0"));
        assertEquals(9, number("SELECT COUNT(*) FROM course_offering WHERE course_id IS NULL "
                + "AND official_offering_code IS NULL"));
        assertEquals(3, number("SELECT COUNT(*) FROM course_offering WHERE activity_type='PRACTICE' "
                + "AND course_id IS NULL"));
        assertEquals(1, number("SELECT COUNT(*) FROM location WHERE location_type='ONLINE'"));
        assertEquals(1, number("SELECT COUNT(*) FROM location WHERE location_type='TBD'"));
        assertEquals(0, number("SELECT COUNT(*) FROM user_account WHERE teacher_id IS NOT NULL"));
        assertEquals(9, number("SELECT COUNT(*) FROM teacher WHERE employee_no IS NULL "
                + "AND identity_status='NAME_ONLY' AND source_teacher_key IS NOT NULL"));
        String rawJson = jdbc.queryForObject(
                "SELECT raw_json FROM source_timetable_row WHERE source_index=1", String.class);
        assertNotNull(rawJson);
        assertTrue(rawJson.contains("测试课程A"));
        assertTrue(JSON.readTree(rawJson).isObject());
    }

    @Test
    void imp03_04_oneOfferingCanHaveMultipleSchedulesWithDifferentTeachers() throws Exception {
        Map<String, Object> first = row("分周教师-测试", "本", "测试班", 36,
                "000000000000001111", "1-4周", 2, "1-2", "Teacher Alpha", "测试地点A", "其它");
        Map<String, Object> second = row("分周教师-测试", "本", "测试班", 36,
                "000000000011110000", "5-8周", 2, "1-2", "Teacher Beta", "测试地点A", "其它");

        TimetableImportResult result = importAsAdmin("different-teachers.html", html(first, second));

        assertEquals(1, result.createdOfferings());
        assertEquals(2, result.createdSchedules());
        assertEquals(2, result.createdTeachers());
        assertEquals(2, number("SELECT COUNT(*) FROM schedule_teacher"));
        assertEquals(2, number("SELECT COUNT(DISTINCT teacher_id) FROM schedule_teacher"));
    }

    @Test
    void imp11_importsTheRound22Ac03TimeAndLocationShape() throws Exception {
        byte[] html = ac03HtmlFromRound22Fixture();

        TimetableImportResult result = importAsAdmin("round22-ac03.html", html);

        assertEquals(1, result.createdOfferings());
        assertEquals(2, result.createdSchedules());
        assertEquals(1, number("SELECT COUNT(*) FROM course_offering o WHERE EXISTS ("
                + "SELECT 1 FROM class_schedule s JOIN schedule_week w ON w.schedule_id=s.id "
                + "JOIN location l ON l.id=s.location_id WHERE s.offering_id=o.id "
                + "AND w.week_no=1 AND l.display_name='测试地点A')"));
        assertEquals(0, number("SELECT COUNT(*) FROM course_offering o WHERE EXISTS ("
                + "SELECT 1 FROM class_schedule s JOIN schedule_week w ON w.schedule_id=s.id "
                + "JOIN location l ON l.id=s.location_id WHERE s.offering_id=o.id "
                + "AND w.week_no=1 AND l.display_name='测试地点B')"));
    }

    @Test
    void imp12_exactSuccessfulFileIsIdempotentAndCompatibleDomainIsReused() throws Exception {
        byte[] original = resource("/fixtures/timetable-parser-anonymous.html");
        TimetableImportResult first = importAsAdmin("first.html", original);
        long offerings = count("course_offering");
        long schedules = count("class_schedule");
        long relations = count("schedule_teacher");
        long weeks = count("schedule_week");

        TimetableImportResult duplicate = importAsAdmin("renamed.html", original);
        assertEquals(TimetableImportStatus.ALREADY_IMPORTED, duplicate.status());
        assertEquals(first.batchId(), duplicate.batchId());
        assertEquals(1, count("import_batch"));
        assertEquals(9, count("source_timetable_row"));
        assertEquals(offerings, count("course_offering"));
        assertEquals(schedules, count("class_schedule"));
        assertEquals(relations, count("schedule_teacher"));
        assertEquals(weeks, count("schedule_week"));

        byte[] equivalentDifferentFile = ("\n" + new String(original, StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);
        TimetableImportResult reused = importAsAdmin("equivalent.html", equivalentDifferentFile);
        assertEquals(TimetableImportStatus.SUCCESS, reused.status());
        assertEquals(0, reused.createdOfferings());
        assertEquals(9, reused.reusedOfferings());
        assertEquals(0, reused.createdSchedules());
        assertEquals(9, reused.reusedSchedules());
        assertEquals(2, count("import_batch"));
        assertEquals(18, count("source_timetable_row"));
        assertEquals(offerings, count("course_offering"));
        assertEquals(schedules, count("class_schedule"));
    }

    @Test
    void imp13_duplicateSourceRowsRemainTraceableWithoutDuplicatingDomain() throws Exception {
        Map<String, Object> source = row("重复来源-测试", "本", "重复班", 30,
                "000000000000001111", "1-4周", 1, "1-2", "Teacher Alpha", "测试地点A", "其它");

        TimetableImportResult result = importAsAdmin("duplicate-rows.html", html(source, source));

        assertEquals(TimetableImportStatus.SUCCESS, result.status());
        assertEquals(2, count("source_timetable_row"));
        assertEquals(1, count("course_offering"));
        assertEquals(1, count("class_schedule"));
        assertEquals(1, count("schedule_teacher"));
        assertEquals(4, count("schedule_week"));
    }

    @Test
    void imp14_studentCountConflictRejectsTheWholeBatchBeforeWrites() throws Exception {
        Map<String, Object> first = row("人数冲突-测试", "本", "测试班", 30,
                "000000000000000001", "1周", 1, "1-2", "Teacher Alpha", "测试地点A", "其它");
        Map<String, Object> second = row("人数冲突-测试", "本", "测试班", 31,
                "000000000000000010", "2周", 1, "1-2", "Teacher Alpha", "测试地点B", "其它");

        TimetableImportRejectedException failure = assertThrows(TimetableImportRejectedException.class,
                () -> importAsAdmin("student-conflict.html", html(first, second)));

        assertEquals(TimetableImportStatus.CONFLICT, failure.result().status());
        assertEquals("SOURCE_OFFERING_STUDENT_COUNT_CONFLICT",
                failure.result().errors().get(0).errorCode());
        assertEquals(2, failure.result().errors().get(0).sourceIndex());
        assertNoImportData();
    }

    @Test
    void scheduleKeyCollisionAcrossLocationGroupsIsAnExplicitConflict() throws Exception {
        Map<String, Object> first = row("地点组冲突-测试", "本", "测试班", 30,
                "000000000000000001", "1周", 1, "1-2", "Teacher Alpha", "共享地点", "其它");
        Map<String, Object> second = row("地点组冲突-测试", "本", "测试班", 30,
                "000000000000000001", "1周", 1, "1-2", "Teacher Alpha", "共享地点", "线上");

        TimetableImportRejectedException failure = assertThrows(TimetableImportRejectedException.class,
                () -> importAsAdmin("location-group-conflict.html", html(first, second)));

        assertEquals(TimetableImportStatus.CONFLICT, failure.result().status());
        assertEquals("SOURCE_SCHEDULE_CONFLICT", failure.result().errors().get(0).errorCode());
        assertNoImportData();
    }

    @Test
    void imp15_existingOfferingAndScheduleConflictsAreNeverOverwritten() throws Exception {
        Map<String, Object> source = row("已有开课-测试", "本", "已有班", 30,
                "000000000000001111", "1-4周", 1, "1-2", "Teacher Alpha", "测试地点A", "其它");
        byte[] original = html(source);
        TimetableImportResult first = importAsAdmin("existing.html", original);
        String offeringKey = jdbc.queryForObject("SELECT source_offering_key FROM course_offering", String.class);
        jdbc.update("UPDATE course_offering SET student_count=31 WHERE source_offering_key=?", offeringKey);

        TimetableImportRejectedException offeringConflict = assertThrows(
                TimetableImportRejectedException.class,
                () -> importAsAdmin("offering-conflict.html", withLeadingSpace(original)));
        assertEquals("EXISTING_OFFERING_CONFLICT",
                offeringConflict.result().errors().get(0).errorCode());
        assertEquals(31, number("SELECT student_count FROM course_offering"));
        assertEquals(1, count("import_batch"));

        jdbc.update("UPDATE course_offering SET student_count=30 WHERE source_offering_key=?", offeringKey);
        jdbc.update("UPDATE class_schedule SET raw_week_text='不兼容周文本'");
        TimetableImportRejectedException scheduleConflict = assertThrows(
                TimetableImportRejectedException.class,
                () -> importAsAdmin("schedule-conflict.html", withTwoLeadingSpaces(original)));
        assertEquals("EXISTING_SCHEDULE_CONFLICT",
                scheduleConflict.result().errors().get(0).errorCode());
        assertEquals("不兼容周文本", jdbc.queryForObject(
                "SELECT raw_week_text FROM class_schedule", String.class));
        assertEquals(first.batchId(), jdbc.queryForObject("SELECT id FROM import_batch", Long.class));
    }

    @Test
    void imp16_17_unknownSemesterAndSourceScopeAreRejectedWithoutCreatingReferences() throws Exception {
        byte[] html = html(row("未知引用-测试", "本", "测试班", 1,
                "000000000000000001", "1周", 1, "1-2", "Teacher Alpha", "线上", "线上"));

        TimetableImportRejectedException semester = assertThrows(TimetableImportRejectedException.class,
                () -> importAsAdmin("unknown-semester.html", html, 999999, COLLEGE));
        assertEquals("UNKNOWN_SEMESTER", semester.result().errors().get(0).errorCode());
        TimetableImportRejectedException college = assertThrows(TimetableImportRejectedException.class,
                () -> importAsAdmin("unknown-college.html", withLeadingSpace(html), SEMESTER, 999999));
        assertEquals("UNKNOWN_SOURCE_SCOPE", college.result().errors().get(0).errorCode());
        assertEquals(1, count("semester"));
        assertEquals(1, count("college"));
        assertNoImportData();
    }

    @Test
    void imp18_aFailureAfterRealScheduleInsertRollsBackSourceAndEveryDomainTable() throws Exception {
        byte[] html = html(row("回滚-测试", "本", "测试班", 1,
                "000000000000000001", "1周", 1, "1-2", "Teacher Alpha", "测试地点A", "其它"));
        checkpoint.fail.set(true);

        TimetableImportRejectedException failure = assertThrows(TimetableImportRejectedException.class,
                () -> importAsAdmin("rollback.html", html));

        assertEquals(TimetableImportStatus.FAILED, failure.result().status());
        assertEquals("IMPORT_PERSISTENCE_ERROR", failure.result().errors().get(0).errorCode());
        assertNoImportData();
    }

    @Test
    void parserErrorsProduceLocatableResultsAndNoDatabaseWrites() throws Exception {
        byte[] invalid = "<html><script>const COURSES=[{\"n\":\"broken\"}]</script></html>"
                .getBytes(StandardCharsets.UTF_8);

        TimetableImportRejectedException failure = assertThrows(TimetableImportRejectedException.class,
                () -> importAsAdmin("invalid.html", invalid));

        assertEquals(TimetableImportStatus.FAILED, failure.result().status());
        assertEquals(1, failure.result().errors().get(0).sourceIndex());
        assertNotNull(failure.result().errors().get(0).field());
        assertNoImportData();
    }

    @Test
    void uploadedFileMustBeNonemptyHtmlAndNoLargerThanFiveMib() {
        TimetableImportRejectedException type = assertThrows(TimetableImportRejectedException.class,
                () -> importAsAdmin("timetable.txt", "x".getBytes(StandardCharsets.UTF_8)));
        assertEquals("UNSUPPORTED_FILE_TYPE", type.result().errors().get(0).errorCode());
        TimetableImportRejectedException empty = assertThrows(TimetableImportRejectedException.class,
                () -> importAsAdmin("timetable.html", new byte[0]));
        assertEquals("INVALID_SOURCE_DOCUMENT", empty.result().errors().get(0).errorCode());
        TimetableImportRejectedException large = assertThrows(TimetableImportRejectedException.class,
                () -> importAsAdmin("timetable.html", new byte[TimetableImportService.MAX_FILE_BYTES + 1]));
        assertEquals(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, large.httpStatus());
        assertEquals("FILE_TOO_LARGE", large.result().errors().get(0).errorCode());
        assertNoImportData();
    }

    @Test
    void concurrentImportsFromDifferentScopesReuseOneCompatibleLocation() throws Exception {
        long otherCollege = 9105;
        jdbc.update("INSERT INTO college(id,college_code,college_name) VALUES (?,?,?)",
                otherCollege, "TEST-OTHER", "另一测试来源范围");
        byte[] first = html(row("并发课程A", "本", "并发班级A", 20,
                "000000000000000001", "1周", 1, "1-2", "Teacher Concurrent A",
                "并发楼A101", "其它"));
        byte[] second = html(row("并发课程B", "本", "并发班级B", 21,
                "000000000000000001", "1周", 2, "3-4", "Teacher Concurrent B",
                "并发楼A101", "其它"));
        LOCATION_RACE_GATE.arm("并发楼A101", "其它");

        var executor = Executors.newFixedThreadPool(2);
        try {
            var firstResult = executor.submit(() -> importAsAdminInWorker(
                    "concurrent-a.html", first, COLLEGE));
            var secondResult = executor.submit(() -> importAsAdminInWorker(
                    "concurrent-b.html", second, otherCollege));
            TimetableImportResult a = firstResult.get(20, TimeUnit.SECONDS);
            TimetableImportResult b = secondResult.get(20, TimeUnit.SECONDS);

            assertEquals(TimetableImportStatus.SUCCESS, a.status());
            assertEquals(TimetableImportStatus.SUCCESS, b.status());
            assertEquals(1, a.createdLocations() + b.createdLocations());
            assertEquals(1, a.reusedLocations() + b.reusedLocations());
            assertCounts(2, 2, 2, 2, 2, 2, 2, 1);
            assertEquals(1, number("SELECT COUNT(DISTINCT location_id) FROM class_schedule"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void api01_02_03_04_enforcesAuthenticationCsrfCapabilityAndReferenceValidation() throws Exception {
        byte[] html = html(row("API-测试", "本", "测试班", 1,
                "000000000000000001", "1周", 1, "1-2", "Teacher Alpha", "线上", "线上"));
        MockMultipartFile file = new MockMultipartFile("file", "api.html", "text/html", html);

        mvc.perform(multipart("/api/v1/imports/timetable").file(file)
                        .param("semesterId", Long.toString(SEMESTER))
                        .param("sourceScopeCollegeId", Long.toString(COLLEGE))
                        .with(csrf()).with(authentication(authToken(ADMIN))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCESS"));
        mvc.perform(multipart("/api/v1/imports/timetable").file(file)
                        .param("semesterId", Long.toString(SEMESTER))
                        .param("sourceScopeCollegeId", Long.toString(COLLEGE))
                        .with(csrf()).with(authentication(authToken(ADMIN))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ALREADY_IMPORTED"));
        assertEquals(1, count("course_offering"));
        assertEquals(1, count("class_schedule"));
        mvc.perform(multipart("/api/v1/imports/timetable").file(file)
                        .param("semesterId", Long.toString(SEMESTER))
                        .param("sourceScopeCollegeId", Long.toString(COLLEGE))
                        .with(csrf()).with(authentication(authToken(ORDINARY))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(multipart("/api/v1/imports/timetable").file(file)
                        .param("semesterId", Long.toString(SEMESTER))
                        .param("sourceScopeCollegeId", Long.toString(COLLEGE)).with(csrf()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(multipart("/api/v1/imports/timetable").file(file)
                        .param("semesterId", Long.toString(SEMESTER))
                        .param("sourceScopeCollegeId", Long.toString(COLLEGE))
                        .with(authentication(authToken(ADMIN))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));

        MockMultipartFile changed = new MockMultipartFile("file", "invalid-ref.html", "text/html",
                withLeadingSpace(html));
        mvc.perform(multipart("/api/v1/imports/timetable").file(changed)
                        .param("semesterId", "999999")
                        .param("sourceScopeCollegeId", Long.toString(COLLEGE))
                        .with(csrf()).with(authentication(authToken(ADMIN))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].errorCode").value("UNKNOWN_SEMESTER"));
    }

    @Test
    void real538HtmlImportsIntoTheDisposableTestDatabaseWhenLocalSnapshotExists() throws Exception {
        Path snapshot = findSnapshot();
        assumeTrue(Files.isRegularFile(snapshot), "ignored real HTML snapshot is not available");
        byte[] bytes = Files.readAllBytes(snapshot);

        TimetableImportResult result = importAsAdmin("local-real-snapshot.html", bytes);

        assertEquals(TimetableImportStatus.SUCCESS, result.status());
        assertEquals(538, result.totalRows());
        assertEquals(538, count("source_timetable_row"));
        System.out.printf("REAL_IMPORT_COUNTS sha256=%s sourceRows=%d valid=%d invalid=0 "
                        + "offerings=%d schedules=%d teachers=%d scheduleTeachers=%d weeks=%d locations=%d%n",
                SourceKeys.contentSha256(bytes), count("source_timetable_row"), result.successRows(),
                count("course_offering"), count("class_schedule"), count("teacher"),
                count("schedule_teacher"), count("schedule_week"), count("location"));
    }

    private TimetableImportResult importAsAdmin(String fileName, byte[] html) {
        return importAsAdmin(fileName, html, SEMESTER, COLLEGE);
    }

    private TimetableImportResult importAsAdmin(String fileName, byte[] html,
                                                 long semester, long college) {
        SecurityContextHolder.getContext().setAuthentication(authToken(ADMIN));
        return imports.importHtml(fileName, html, semester, college);
    }

    private TimetableImportResult importAsAdminInWorker(String fileName, byte[] html, long college) {
        SecurityContextHolder.getContext().setAuthentication(authToken(ADMIN));
        try {
            return imports.importHtml(fileName, html, SEMESTER, college);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private UsernamePasswordAuthenticationToken authToken(long userId) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new AccountPrincipal(userId, "test-" + userId, "not-used", true), null, List.of());
    }

    private void seedReferencesAndAccounts() {
        jdbc.update("INSERT INTO college(id,college_code,college_name) VALUES (?,?,?)",
                COLLEGE, "TEST-SOFTWARE", "测试来源范围");
        jdbc.update("INSERT INTO semester(id,semester_code,semester_name,teaching_week_count) VALUES (?,?,?,?)",
                SEMESTER, "2026-FALL", "2026秋", 18);
        jdbc.update("INSERT INTO role(id,role_code,role_name) VALUES (?,?,?)", 9111, "ADMIN", "管理员");
        jdbc.update("INSERT INTO role(id,role_code,role_name) VALUES (?,?,?)", 9112, "TEACHER", "普通用户");
        jdbc.update("INSERT INTO user_account(id,username,password_hash,enabled) VALUES (?,?,?,?)",
                ADMIN, "import-admin", "not-used", true);
        jdbc.update("INSERT INTO user_account(id,username,password_hash,enabled) VALUES (?,?,?,?)",
                ORDINARY, "ordinary-user", "not-used", true);
        jdbc.update("INSERT INTO user_role_scope(id,user_id,role_id,scope_type) VALUES (?,?,?,?)",
                9121, ADMIN, 9111, "GLOBAL");
        jdbc.update("INSERT INTO user_role_scope(id,user_id,role_id,scope_type) VALUES (?,?,?,?)",
                9122, ORDINARY, 9112, "GLOBAL");
    }

    private void clean() {
        for (String table : CLEAN_ORDER) jdbc.update("DELETE FROM " + table);
    }

    private void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private void assertCounts(long batches, long sources, long offerings, long schedules,
                              long weeks, long teachers, long scheduleTeachers, long locations) {
        assertEquals(batches, count("import_batch"));
        assertEquals(sources, count("source_timetable_row"));
        assertEquals(offerings, count("course_offering"));
        assertEquals(schedules, count("class_schedule"));
        assertEquals(weeks, count("schedule_week"));
        assertEquals(teachers, count("teacher"));
        assertEquals(scheduleTeachers, count("schedule_teacher"));
        assertEquals(locations, count("location"));
    }

    private void assertNoImportData() {
        assertCounts(0, 0, 0, 0, 0, 0, 0, 0);
    }

    private long count(String table) {
        return number("SELECT COUNT(*) FROM " + table);
    }

    private long number(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        assertNotNull(value);
        return value;
    }

    private byte[] resource(String path) throws Exception {
        try (var input = getClass().getResourceAsStream(path)) {
            assertNotNull(input);
            return input.readAllBytes();
        }
    }

    @SafeVarargs
    private byte[] html(Map<String, Object>... rows) throws Exception {
        return ("<!doctype html><html><script>const COURSES="
                + JSON.writeValueAsString(List.of(rows)) + ";</script></html>")
                .getBytes(StandardCharsets.UTF_8);
    }

    private Map<String, Object> row(String n, String t, String c, int p, String w, String rw,
                                    int d, String s, String h, String l, String g) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("n", n); row.put("t", t); row.put("c", c); row.put("p", p);
        row.put("w", w); row.put("rw", rw); row.put("d", d); row.put("s", s);
        row.put("h", h); row.put("l", l); row.put("g", g);
        return row;
    }

    private byte[] ac03HtmlFromRound22Fixture() throws Exception {
        JsonNode fixture;
        try (var input = getClass().getResourceAsStream("/fixtures/phase1.json")) {
            assertNotNull(input);
            fixture = JSON.readTree(input);
        }
        JsonNode offering = find(fixture.get("offerings"), "id", 104);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode schedule : fixture.get("schedules")) {
            if (schedule.get("offering").asLong() != 104) continue;
            JsonNode location = find(fixture.get("locations"), "id", schedule.get("location").asLong());
            List<String> teacherNames = new ArrayList<>();
            for (JsonNode teacherId : schedule.get("teachers")) {
                teacherNames.add(find(fixture.get("teachers"), "id", teacherId.asLong())
                        .get("name").asText());
            }
            rows.add(row(offering.get("name").asText(), "本", offering.get("classes").asText(),
                    offering.get("students").asInt(), schedule.get("mask").asText(),
                    schedule.get("weeks").asText(), schedule.get("day").asInt(),
                    schedule.get("start").asText() + "-" + schedule.get("end").asText(),
                    String.join(",", teacherNames), location.get("name").asText(), "其它"));
        }
        @SuppressWarnings("unchecked") Map<String, Object>[] array = rows.toArray(Map[]::new);
        return html(array);
    }

    private JsonNode find(JsonNode array, String field, long value) {
        for (JsonNode node : array) if (node.get(field).asLong() == value) return node;
        throw new IllegalArgumentException("fixture reference not found");
    }

    private byte[] withLeadingSpace(byte[] source) {
        return (" " + new String(source, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] withTwoLeadingSpaces(byte[] source) {
        return ("  " + new String(source, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
    }

    private Path findSnapshot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        Path direct = current.resolve(".work/course-source-page.html");
        if (Files.isRegularFile(direct)) return direct;
        return current.getParent() == null ? direct
                : current.getParent().resolve(".work/course-source-page.html");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailureConfiguration {
        @Bean @Primary ControllableCheckpoint controllableCheckpoint() {
            return new ControllableCheckpoint();
        }

        @Bean static BeanPostProcessor locationLookupRaceProxy() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof TimetableImportMapper delegate)) return bean;
                    return Proxy.newProxyInstance(TimetableImportMapper.class.getClassLoader(),
                            new Class<?>[] {TimetableImportMapper.class}, (proxy, method, arguments) -> {
                                try {
                                    Object result = method.invoke(delegate, arguments);
                                    if (method.getName().equals("location") && result == null) {
                                        LOCATION_RACE_GATE.awaitIfArmed(
                                                (String) arguments[0], (String) arguments[1]);
                                    }
                                    return result;
                                } catch (InvocationTargetException exception) {
                                    throw exception.getCause();
                                }
                            });
                }
            };
        }
    }

    static final class LocationLookupRaceGate {
        private volatile String normalizedName;
        private volatile String locationGroup;
        private volatile CyclicBarrier barrier;

        void arm(String expectedName, String expectedGroup) {
            normalizedName = expectedName;
            locationGroup = expectedGroup;
            barrier = new CyclicBarrier(2);
        }

        void awaitIfArmed(String actualName, String actualGroup) throws Exception {
            CyclicBarrier current = barrier;
            if (current != null && normalizedName.equals(actualName)
                    && locationGroup.equals(actualGroup)) {
                current.await(10, TimeUnit.SECONDS);
            }
        }

        void clear() {
            barrier = null;
            normalizedName = null;
            locationGroup = null;
        }
    }

    static final class ControllableCheckpoint extends ImportTransactionCheckpoint {
        final AtomicBoolean fail = new AtomicBoolean();
        @Override public void afterScheduleWrite() {
            if (fail.getAndSet(false)) throw new IllegalStateException("injected transaction failure");
        }
    }
}
