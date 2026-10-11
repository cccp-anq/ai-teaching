package com.aijiaoxue.course;

import com.aijiaoxue.common.web.RequestIdFilter;
import com.aijiaoxue.course.detail.CourseOfferingDetail;
import com.aijiaoxue.course.detail.CourseOfferingDetailService;
import com.aijiaoxue.course.detail.ScheduleDetail;
import com.aijiaoxue.course.detail.SyllabusStatus;
import com.aijiaoxue.fixtures.R__Phase1_acceptance_fixture;
import com.aijiaoxue.identity.authentication.AccountPrincipal;
import com.aijiaoxue.importing.timetable.TimetableImportService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.migration.JavaMigration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(CourseOfferingDetailIntegrationTest.FixtureConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional
class CourseOfferingDetailIntegrationTest {
    private static final String ENDPOINT = "/api/v1/course-offerings";
    private static final String TEST_PASSWORD = "Fixture-Only-2026!";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired WebApplicationContext context;
    @Autowired RequestIdFilter requestIds;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired CourseOfferingDetailService details;
    @Autowired TimetableImportService imports;
    @Autowired DetailSqlCounter detailSqlCounter;
    MockMvc mvc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) throws Exception {
        boolean mysql = Boolean.getBoolean("mysqlMigration");
        String url = mysql ? Objects.requireNonNull(System.getenv("MIGRATION_TEST_URL"))
                : "jdbc:h2:mem:phase1-fixture-detail;MODE=MySQL;DB_CLOSE_DELAY=-1";
        if (mysql && !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/ai_teaching_migration_[a-f0-9]{32}\\?sslMode=DISABLED&allowPublicKeyRetrieval=true"))
            throw new IllegalArgumentException("Dedicated MySQL test database required");
        String user = mysql ? Objects.requireNonNull(System.getenv("MIGRATION_TEST_USERNAME")) : "sa";
        String password = mysql ? Objects.requireNonNull(System.getenv("MIGRATION_TEST_PASSWORD")) : "";
        try (var db = DriverManager.getConnection(url, user, password);
             var tables = db.getMetaData().getTables(db.getCatalog(), mysql ? null : "PUBLIC", "%", new String[]{"TABLE"})) {
            assertFalse(tables.next(), "Detail suite refuses nonempty databases");
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> user);
        properties.add("spring.datasource.password", () -> password);
        properties.add("spring.datasource.driver-class-name",
                () -> mysql ? "com.mysql.cj.jdbc.Driver" : "org.h2.Driver");
        properties.add("server.servlet.session.cookie.secure", () -> false);
    }

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIds).apply(springSecurity()).build();
        detailSqlCounter.reset();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @AfterAll
    void cleanup() throws Exception {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .javaMigrations(new R__Phase1_acceptance_fixture(false, true)).cleanDisabled(true).load().migrate();
        for (String table : R__Phase1_acceptance_fixture.TABLES)
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
    }

    @Test
    void det01AuthorizedUserReadsOrdinaryDetailWithBusinessFields() throws Exception {
        jdbc.update("UPDATE course SET course_code='REAL-DETAIL-201' WHERE id=201");
        mvc.perform(get(ENDPOINT + "/101"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mvc.perform(get(ENDPOINT + "/101").session(login("director-a-test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offeringId").value(101))
                .andExpect(jsonPath("$.displayName").value("普通本科-测试"))
                .andExpect(jsonPath("$.courseId").value(201))
                .andExpect(jsonPath("$.courseName").value("测试课程主体A"))
                .andExpect(jsonPath("$.courseCode").value("REAL-DETAIL-201"))
                .andExpect(jsonPath("$.activityType").value("UNDERGRAD_COURSE"))
                .andExpect(jsonPath("$.classGroupText").value("测试软件2401-03,测试软件2404"))
                .andExpect(jsonPath("$.studentCount").value(40))
                .andExpect(jsonPath("$.semester.semesterCode").value("2026-FALL"))
                .andExpect(jsonPath("$.semester.firstWeekDate").value("2026-08-30"))
                .andExpect(jsonPath("$.sourceScopeCollege.collegeId").value(1))
                .andExpect(jsonPath("$.ownerCollege.collegeId").value(1))
                .andExpect(jsonPath("$.syllabusStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.description").value(
                        org.hamcrest.Matchers.startsWith("匿名测试课程简介")))
                .andExpect(jsonPath("$.schedules.length()").value(2))
                .andExpect(jsonPath("$.sourceOfferingKey").doesNotExist())
                .andExpect(jsonPath("$.importBatchId").doesNotExist())
                .andExpect(jsonPath("$.syllabusRef").doesNotExist())
                .andExpect(jsonPath("$.resources").doesNotExist());
    }

    @Test
    void det02OfferingIdReturnedByListOpensTheSameDetail() throws Exception {
        var session = login("director-a-test");
        var listResponse = mvc.perform(get(ENDPOINT).session(session).param("size", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long offeringId = JSON.readTree(listResponse).get("items").get(0).get("offeringId").asLong();
        mvc.perform(get(ENDPOINT + "/" + offeringId).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.offeringId").value(offeringId));
    }

    @Test
    void det03NullCourseMappingStillReturnsDetailAndExplicitNulls() {
        runAs(2);
        var detail = details.get(102);
        assertEquals("共同授课-测试", detail.displayName());
        assertNull(detail.courseId());
        assertNull(detail.courseName());
        assertNull(detail.courseCode());
        assertNull(detail.ownerCollege());
        assertEquals(SyllabusStatus.MISSING, detail.syllabusStatus());
    }
    @Test
    void det21DescriptionFollowsFormalCourseOnlyAndNullWhenUnmapped() throws Exception {
        runAs(2);
        // Offering 102 has course_id NULL: description is null and is never sourced from scope/name.
        assertNull(details.get(102).description());
        assertEquals("匿名测试课程简介：面向本科生的程序设计基础课程，覆盖数据结构、算法与工程实践入门。",
                details.get(101).description());
        mvc.perform(get(ENDPOINT + "/101").session(login("director-a-test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value(
                        "匿名测试课程简介：面向本科生的程序设计基础课程，覆盖数据结构、算法与工程实践入门。"));
    }


    @Test
    void det04EveryScheduleIsReturnedOnceInStableOrder() {
        runAs(2);
        var first = details.get(101);
        var repeated = details.get(101);
        assertEquals(List.of(1001L, 1002L), scheduleIds(first));
        assertEquals(first.schedules(), repeated.schedules());
        assertEquals(2, new HashSet<>(scheduleIds(first)).size());
    }

    @Test
    void det05DifferentSchedulesKeepTheirOwnTeachers() {
        runAs(2);
        var detail = details.get(103);
        assertEquals(List.of("Teacher Alpha"), teacherNames(schedule(detail, 1004)));
        assertEquals(List.of("Teacher Gamma"), teacherNames(schedule(detail, 1005)));
    }

    @Test
    void det06OneScheduleCanContainMultipleTeachersWithoutDuplication() {
        runAs(2);
        var schedule = schedule(details.get(102), 1003);
        assertEquals(List.of("Teacher Alpha", "Teacher Beta"), teacherNames(schedule));
        assertEquals(2, new HashSet<>(schedule.teachers()).size());
    }

    @Test
    void det07NoncontiguousWeeksComeFromScheduleWeekRows() {
        runAs(2);
        var schedule = schedule(details.get(105), 1008);
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12, 13), schedule.weeks());
        assertTrue(schedule.weeks().contains(8));
        assertFalse(schedule.weeks().contains(9));
        assertTrue(schedule.weeks().contains(10));
        assertEquals("1-8周,10-13周", schedule.rawWeekText());
    }

    @Test
    void det08EvenWeeksAreReturnedAsTheActualDiscreteSet() {
        runAs(2);
        var schedule = schedule(details.get(105), 1009);
        assertEquals(List.of(10, 12, 14), schedule.weeks());
        assertEquals("10-14周(双)", schedule.rawWeekText());
    }

    @Test
    void det09OnlineLocationIsACompleteNormalLocation() {
        runAs(2);
        var location = schedule(details.get(107), 1012).location();
        assertEquals("线上", location.displayName());
        assertEquals("线上", location.locationGroup());
        assertEquals("ONLINE", location.locationType());
    }

    @Test
    void det10TbdLocationIsACompleteNormalLocation() {
        runAs(2);
        var location = schedule(details.get(107), 1013).location();
        assertEquals("待定", location.displayName());
        assertEquals("其它", location.locationGroup());
        assertEquals("TBD", location.locationType());
    }

    @Test
    void det11ZeroStudentCountRemainsNumericZero() {
        runAs(2);
        assertEquals(0, details.get(106).studentCount());
    }

    @Test
    void det12SourceScopeAndOwnerCollegeAreNeverSubstituted() {
        runAs(2);
        var detail = details.get(102);
        assertEquals(1, detail.sourceScopeCollege().collegeId());
        assertEquals("TEST-SOFTWARE", detail.sourceScopeCollege().collegeCode());
        assertNull(detail.ownerCollege());
    }

    @Test
    void det13KnownUnauthorizedOfferingReturnsOnlyNotVisible() throws Exception {
        assertHttpNotVisible(login("teacher-a-test"), 109);
    }

    @Test
    void det14MissingOfferingUsesTheSameExternalNotVisiblePolicy() throws Exception {
        var session = login("teacher-a-test");
        var unauthorized = mvc.perform(get(ENDPOINT + "/109").session(session).header("X-Request-ID", "detail-hidden"))
                .andReturn().getResponse();
        var missing = mvc.perform(get(ENDPOINT + "/99999").session(session).header("X-Request-ID", "detail-hidden"))
                .andReturn().getResponse();
        assertEquals(404, unauthorized.getStatus());
        assertEquals(404, missing.getStatus());
        var unauthorizedBody = JSON.readTree(unauthorized.getContentAsString());
        var missingBody = JSON.readTree(missing.getContentAsString());
        for (String field : List.of("code", "message", "requestId", "fieldErrors"))
            assertEquals(unauthorizedBody.get(field), missingBody.get(field));
        assertEquals("NOT_VISIBLE", missingBody.get("code").asText());
        assertNull(missingBody.get("displayName"));
    }

    @Test
    void det15MalformedOfferingIdUsesTheExistingBadRequestConvention() throws Exception {
        mvc.perform(get(ENDPOINT + "/not-a-number").session(login("director-a-test")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("HTTP_400"));
    }

    @Test
    void det16GlobalReaderCanReadAnyOfferingInTheAuthorizedDataset() throws Exception {
        mvc.perform(get(ENDPOINT + "/109").session(login("director-a-test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.offeringId").value(109));
    }

    @Test
    void det17SelfTeacherAuthorizationUsesScheduleTeacherRelations() throws Exception {
        jdbc.update("DELETE FROM user_role_scope WHERE id=6");
        var session = login("teacher-a-test");
        mvc.perform(get(ENDPOINT + "/103").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.offeringId").value(103));
        assertHttpNotVisible(session, 109);
    }

    @Test
    void det18ExplicitOfferingScopeReadsOnlyItsTarget() throws Exception {
        var session = login("supervisor-a-test");
        mvc.perform(get(ENDPOINT + "/104").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.offeringId").value(104));
        assertHttpNotVisible(session, 101);
    }

    @Test
    void det19OfferingTeacherSummaryIsTheStableDistinctUnion() {
        runAs(2);
        var detail = details.get(103);
        assertEquals(List.of("Teacher Alpha", "Teacher Gamma"),
                detail.teachers().stream().map(teacher -> teacher.displayName()).toList());
        var union = detail.schedules().stream().flatMap(schedule -> schedule.teachers().stream())
                .map(teacher -> teacher.teacherId()).collect(java.util.stream.Collectors.toSet());
        assertEquals(union, detail.teachers().stream().map(teacher -> teacher.teacherId())
                .collect(java.util.stream.Collectors.toSet()));
        assertEquals(detail.teachers().size(), new HashSet<>(detail.teachers()).size());
    }

    @Test
    void det20MultipleWeeksAndTeachersNeverCreateCartesianDuplicates() {
        runAs(2);
        var detail = details.get(102);
        assertEquals(List.of(1003L), scheduleIds(detail));
        var schedule = detail.schedules().get(0);
        assertEquals(List.of(1, 2, 3, 4), schedule.weeks());
        assertEquals(4, new HashSet<>(schedule.weeks()).size());
        assertEquals(List.of("Teacher Alpha", "Teacher Beta"), teacherNames(schedule));
        assertEquals(2, new HashSet<>(schedule.teachers()).size());
    }

    @Test
    void storedSyllabusReferenceProducesAvailableStatusWithoutExposingTheReference() throws Exception {
        mvc.perform(get(ENDPOINT + "/101").session(login("director-a-test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.syllabusStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.syllabusRef").doesNotExist());
    }

    @Test
    void nullStoredSyllabusReferenceProducesMissingStatus() throws Exception {
        mvc.perform(get(ENDPOINT + "/109").session(login("director-a-test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.syllabusStatus").value("MISSING"));
    }

    @Test
    void detailUsesFourBoundedDomainQueriesInsteadOfPerScheduleQueries() {
        runAs(2);
        detailSqlCounter.reset();
        var detail = details.get(107);
        assertEquals(3, detail.schedules().size());
        assertEquals(List.of("selectDetailBase", "selectDetailSchedules", "selectDetailWeeks", "selectDetailTeachers"),
                detailSqlCounter.detailStatements());
    }

    @Test
    void real538RowSnapshotDetailSmokeUsesOnlyAnonymousStatistics() throws Exception {
        Path snapshot = Path.of("..", ".work", "course-source-page.html").toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(snapshot), "local ignored real snapshot is unavailable");
        keepOnlyAdminAndSourceReferences();
        runAs(1);
        var imported = imports.importHtml(snapshot.getFileName().toString(), Files.readAllBytes(snapshot), 1, 1);
        assertEquals(538, imported.successRows());

        long multiScheduleId = firstLong("SELECT offering_id FROM class_schedule GROUP BY offering_id "
                + "HAVING COUNT(*)>1 ORDER BY offering_id LIMIT 1");
        long multiTeacherId = firstLong("SELECT schedule.offering_id FROM class_schedule schedule "
                + "JOIN schedule_teacher relation ON relation.schedule_id=schedule.id GROUP BY schedule.offering_id "
                + "HAVING COUNT(DISTINCT relation.teacher_id)>1 ORDER BY schedule.offering_id LIMIT 1");
        long zeroId = firstLong("SELECT id FROM course_offering WHERE student_count=0 ORDER BY id LIMIT 1");
        long onlineId = firstLong("SELECT schedule.offering_id FROM class_schedule schedule JOIN location "
                + "ON location.id=schedule.location_id WHERE location.location_type='ONLINE' ORDER BY schedule.offering_id LIMIT 1");
        long tbdId = firstLong("SELECT schedule.offering_id FROM class_schedule schedule JOIN location "
                + "ON location.id=schedule.location_id WHERE location.location_type='TBD' ORDER BY schedule.offering_id LIMIT 1");

        var multiSchedule = details.get(multiScheduleId);
        var multiTeacher = details.get(multiTeacherId);
        var zero = details.get(zeroId);
        var online = details.get(onlineId);
        var tbd = details.get(tbdId);
        assertTrue(multiSchedule.schedules().size() > 1);
        assertTrue(multiTeacher.teachers().size() > 1);
        assertEquals(0, zero.studentCount());
        assertTrue(hasLocationType(online, "ONLINE"));
        assertTrue(hasLocationType(tbd, "TBD"));

        printSmoke("multi_schedule", multiSchedule);
        printSmoke("multi_teacher", multiTeacher);
        printSmoke("p_zero", zero);
        printSmoke("online", online);
        printSmoke("tbd", tbd);
    }

    private void assertHttpNotVisible(MockHttpSession session, long offeringId) throws Exception {
        mvc.perform(get(ENDPOINT + "/" + offeringId).session(session))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_VISIBLE"))
                .andExpect(jsonPath("$.displayName").doesNotExist())
                .andExpect(jsonPath("$.schedules").doesNotExist());
    }

    private static ScheduleDetail schedule(CourseOfferingDetail detail, long scheduleId) {
        return detail.schedules().stream().filter(schedule -> schedule.scheduleId() == scheduleId)
                .findFirst().orElseThrow();
    }

    private static List<Long> scheduleIds(CourseOfferingDetail detail) {
        return detail.schedules().stream().map(ScheduleDetail::scheduleId).toList();
    }

    private static List<String> teacherNames(ScheduleDetail schedule) {
        return schedule.teachers().stream().map(teacher -> teacher.displayName()).toList();
    }

    private static boolean hasLocationType(CourseOfferingDetail detail, String type) {
        return detail.schedules().stream().anyMatch(schedule -> type.equals(schedule.location().locationType()));
    }

    private static void printSmoke(String scenario, CourseOfferingDetail detail) {
        System.out.printf("DETAIL_SMOKE scenario=%s schedules=%d teachers=%d result=PASS%n",
                scenario, detail.schedules().size(), detail.teachers().size());
    }

    private long firstLong(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private MockHttpSession login(String username) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/v1/auth/login").with(csrf())
                        .param("username", username).param("password", TEST_PASSWORD))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    private void runAs(long accountId) {
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new AccountPrincipal(accountId, "detail-test", "", true), null, List.of()));
        SecurityContextHolder.setContext(security);
    }

    private void keepOnlyAdminAndSourceReferences() {
        jdbc.update("DELETE FROM source_timetable_row");
        jdbc.update("DELETE FROM import_batch");
        jdbc.update("DELETE FROM user_role_scope WHERE id<>1");
        jdbc.update("UPDATE user_account SET teacher_id=NULL");
        jdbc.update("DELETE FROM schedule_teacher");
        jdbc.update("DELETE FROM schedule_week");
        jdbc.update("DELETE FROM class_schedule");
        jdbc.update("DELETE FROM teaching_resource");
        jdbc.update("DELETE FROM supervision_evaluation");
        jdbc.update("DELETE FROM course_offering");
        jdbc.update("DELETE FROM course");
        jdbc.update("DELETE FROM teacher");
        jdbc.update("DELETE FROM location");
        jdbc.update("DELETE FROM semester WHERE id<>1");
        jdbc.update("DELETE FROM college WHERE id<>1");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {
        @Bean
        JavaMigration detailFixture() throws Exception {
            return new R__Phase1_acceptance_fixture(true, true);
        }

        @Bean
        DetailSqlCounter detailSqlCounter() {
            return new DetailSqlCounter();
        }
    }

    @Intercepts(@Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    static class DetailSqlCounter implements Interceptor {
        private static final String PREFIX = "com.aijiaoxue.course.access.OfferingAccessMapper.selectDetail";
        private final List<String> statements = new CopyOnWriteArrayList<>();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            var statement = (MappedStatement) invocation.getArgs()[0];
            if (statement.getId().startsWith(PREFIX)) statements.add(statement.getId());
            return invocation.proceed();
        }

        void reset() {
            statements.clear();
        }

        List<String> detailStatements() {
            var names = new ArrayList<String>();
            for (String statement : statements)
                names.add(statement.substring(statement.lastIndexOf('.') + 1));
            return List.copyOf(names);
        }
    }
}
