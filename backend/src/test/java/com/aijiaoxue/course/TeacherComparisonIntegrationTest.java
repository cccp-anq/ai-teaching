package com.aijiaoxue.course;

import com.aijiaoxue.common.web.RequestIdFilter;
import com.aijiaoxue.course.comparison.TeacherComparison;
import com.aijiaoxue.course.comparison.TeacherComparisonOffering;
import com.aijiaoxue.course.comparison.TeacherComparisonResult;
import com.aijiaoxue.course.comparison.TeacherComparisonService;
import com.aijiaoxue.course.detail.ScheduleDetail;
import com.aijiaoxue.fixtures.R__Phase1_acceptance_fixture;
import com.aijiaoxue.identity.authentication.AccountPrincipal;
import com.aijiaoxue.importing.timetable.TimetableImportService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(TeacherComparisonIntegrationTest.FixtureConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional
class TeacherComparisonIntegrationTest {
    private static final String ENDPOINT = "/api/v1/teachers/comparison";
    private static final String TEST_PASSWORD = "Fixture-Only-2026!";

    @Autowired WebApplicationContext context;
    @Autowired RequestIdFilter requestIds;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired TeacherComparisonService comparisons;
    @Autowired TimetableImportService imports;
    @Autowired ComparisonSqlCounter comparisonSqlCounter;
    MockMvc mvc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) throws Exception {
        boolean mysql = Boolean.getBoolean("mysqlMigration");
        String url = mysql ? Objects.requireNonNull(System.getenv("MIGRATION_TEST_URL"))
                : "jdbc:h2:mem:phase1-fixture-comparison;MODE=MySQL;DB_CLOSE_DELAY=-1";
        if (mysql && !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/ai_teaching_migration_[a-f0-9]{32}\\?sslMode=DISABLED&allowPublicKeyRetrieval=true"))
            throw new IllegalArgumentException("Dedicated MySQL test database required");
        String user = mysql ? Objects.requireNonNull(System.getenv("MIGRATION_TEST_USERNAME")) : "sa";
        String password = mysql ? Objects.requireNonNull(System.getenv("MIGRATION_TEST_PASSWORD")) : "";
        try (var db = DriverManager.getConnection(url, user, password);
             var tables = db.getMetaData().getTables(db.getCatalog(), mysql ? null : "PUBLIC", "%", new String[]{"TABLE"})) {
            assertFalse(tables.next(), "Comparison suite refuses nonempty databases");
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
        comparisonSqlCounter.reset();
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
    void cmp01FiveAuthorizedTeachersKeepRequestOrderAndAllGroups() throws Exception {
        var session = login("director-a-test");
        mvc.perform(get(ENDPOINT).session(session).param("teacherIds", "1,2,3,4,5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teachers.length()").value(5))
                .andExpect(jsonPath("$.teachers[0].teacherId").value(1))
                .andExpect(jsonPath("$.teachers[1].teacherId").value(2))
                .andExpect(jsonPath("$.teachers[2].teacherId").value(3))
                .andExpect(jsonPath("$.teachers[3].teacherId").value(4))
                .andExpect(jsonPath("$.teachers[4].teacherId").value(5));
    }

    @Test
    void cmp02OrdinaryTeacherReturnsItsOfferings() {
        runAs(1);
        var alpha = teacher(compare(1), 1);
        assertTrue(offeringIds(alpha).containsAll(List.of(101L, 102L, 103L)));
        var ordinary = offering(alpha, 101);
        assertEquals("普通本科-测试", ordinary.displayName());
        assertEquals("UNDERGRAD_COURSE", ordinary.activityType());
        assertEquals("测试软件2401-03,测试软件2404", ordinary.classGroupText());
        assertEquals(40, ordinary.studentCount());
        assertEquals(1, ordinary.semester().semesterId());
        assertEquals("2026-FALL", ordinary.semester().semesterCode());
        assertEquals("2026秋", ordinary.semester().semesterName());
    }

    @Test
    void cmp03CoTeachersBothReceiveOfferingAndCompleteCoTeacherRelation() {
        runAs(1);
        var result = compare(1, 2);
        for (long teacherId : List.of(1L, 2L)) {
            var schedule = schedule(offering(teacher(result, teacherId), 102), 1003);
            assertEquals(List.of(1L, 2L), teacherIds(schedule));
        }
    }

    @Test
    void cmp04DifferentTeachersOnOneOfferingReceiveOnlyTheirActualSchedules() {
        runAs(1);
        var result = compare(1, 3);
        assertEquals(List.of(1004L), scheduleIds(offering(teacher(result, 1), 103)));
        assertEquals(List.of(1005L), scheduleIds(offering(teacher(result, 3), 103)));
    }

    @Test
    void cmp05OneTeacherWithTwoSchedulesGetsOneOffering() {
        runAs(1);
        var alpha = teacher(compare(1), 1);
        assertEquals(1, offeringIds(alpha).stream().filter(id -> id == 101).count());
        assertEquals(List.of(1001L, 1002L), scheduleIds(offering(alpha, 101)));
    }

    @Test
    void cmp06TeacherWithoutAuthorizedScheduleIsRejected() {
        jdbc.update("DELETE FROM schedule_teacher WHERE teacher_id=4");
        runAs(1);
        assertThrows(com.aijiaoxue.course.comparison.TeacherComparisonRejectedException.class,
                () -> compare(4));
    }

    @Test
    void cmp07PartiallyAuthorizedTeacherOnlyShowsVisibleOffering() {
        runAs(5);
        assertEquals(List.of(104L), offeringIds(teacher(compare(2), 2)));
    }

    @Test
    void cmp08TeacherWithOnlyUnauthorizedOfferingsIsRejectedWithoutIdentityLeak() {
        runAs(5);
        assertThrows(com.aijiaoxue.course.comparison.TeacherComparisonRejectedException.class,
                () -> compare(5));
    }

    @Test
    void cmp09DuplicateTeacherIdsAreStablyDeduplicated() throws Exception {
        var session = login("director-a-test");
        mvc.perform(get(ENDPOINT).session(session).param("teacherIds", "5,2,5,1,2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teachers.length()").value(3))
                .andExpect(jsonPath("$.teachers[0].teacherId").value(5))
                .andExpect(jsonPath("$.teachers[1].teacherId").value(2))
                .andExpect(jsonPath("$.teachers[2].teacherId").value(1));
    }

    @Test
    void cmp10UnknownTeacherRejectsTheWholeRequestWithoutComparisonFacts() throws Exception {
        mvc.perform(get(ENDPOINT).session(login("director-a-test")).param("teacherIds", "1,999999"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNKNOWN_TEACHER"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("teacherIds"))
                .andExpect(jsonPath("$.teachers").doesNotExist());
    }

    @Test
    void cmp11ZeroStudentCountRemainsNumericZero() {
        runAs(1);
        assertEquals(0, offering(teacher(compare(5), 5), 106).studentCount());
    }

    @Test
    void cmp12NullCourseMappingStillReturnsOfferingWithMissingSyllabus() {
        runAs(1);
        var offering = offering(teacher(compare(5), 5), 108);
        assertEquals("数据结构实验-测试活动", offering.displayName());
        assertEquals("MISSING", offering.syllabusStatus().name());
    }

    @Test
    void cmp13OnlineScheduleIsReturnedNormally() {
        runAs(1);
        assertTrue(offering(teacher(compare(3), 3), 107).teachingSchedules().stream()
                .anyMatch(schedule -> "ONLINE".equals(schedule.location().locationType())));
    }

    @Test
    void cmp14TbdScheduleIsReturnedNormally() {
        runAs(1);
        assertTrue(offering(teacher(compare(3), 3), 107).teachingSchedules().stream()
                .anyMatch(schedule -> "TBD".equals(schedule.location().locationType())));
    }

    @Test
    void cmp15NoncontiguousWeeksDoNotInventWeekNine() {
        runAs(1);
        var weeks = schedule(offering(teacher(compare(4), 4), 105), 1008).weeks();
        assertTrue(weeks.containsAll(List.of(8, 10)));
        assertFalse(weeks.contains(9));
    }

    @Test
    void cmp16CoTeachingNeverDuplicatesOfferingWithinTeacherGroup() {
        runAs(1);
        for (long teacherId : List.of(1L, 2L))
            assertEquals(1, offeringIds(teacher(compare(teacherId), teacherId)).stream()
                    .filter(id -> id == 102).count());
    }

    @Test
    void cmp17MultipleWeekRowsNeverDuplicateSchedule() {
        runAs(1);
        var schedules = offering(teacher(compare(4), 4), 105).teachingSchedules();
        assertEquals(1, scheduleIds(schedules).stream().filter(id -> id == 1008).count());
        assertEquals(new HashSet<>(schedule(schedules, 1008).weeks()).size(), schedule(schedules, 1008).weeks().size());
    }

    @Test
    void cmp18GlobalSelfTeacherAndOfferingScopesCannotBeExpandedBySelection() {
        runAs(1);
        assertTrue(offeringIds(teacher(compare(4), 4)).contains(105L));

        jdbc.update("DELETE FROM user_role_scope WHERE id=6");
        runAs(3);
        var self = compare(1, 2, 3);
        assertEquals(List.of(102L, 103L, 101L), offeringIds(teacher(self, 1)));
        assertEquals(List.of(102L), offeringIds(teacher(self, 2)));
        assertEquals(List.of(103L), offeringIds(teacher(self, 3)));

        runAs(5);
        assertEquals(List.of(104L), offeringIds(teacher(compare(2), 2)));
    }

    @Test
    void endpointRequiresSessionButNotCsrfForGet() throws Exception {
        mvc.perform(get(ENDPOINT).param("teacherIds", "1"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get(ENDPOINT).session(login("director-a-test")).param("teacherIds", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.teachers[0].teacherId").value(1));
    }

    @Test
    void invalidSelectionsUseUnifiedBadRequestBehavior() throws Exception {
        var session = login("director-a-test");
        mvc.perform(get(ENDPOINT).session(session))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get(ENDPOINT).session(session).param("teacherIds", "0"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get(ENDPOINT).session(session).param("teacherIds", "1,2,3,4,5,6"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get(ENDPOINT).session(session).param("teacherIds", "invalid"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("HTTP_400"));
    }

    @Test
    void offeringOrderIsStableByDisplayNameThenId() {
        jdbc.update("UPDATE course_offering SET display_name='AA-COMPARE-STABLE' WHERE id IN (101,103)");
        runAs(1);
        var first = teacher(compare(1), 1);
        var repeated = teacher(compare(1), 1);
        assertEquals(List.of(101L, 103L), offeringIds(first).subList(0, 2));
        assertEquals(first, repeated);
    }

    @Test
    void syllabusStatusReusesStoredReferenceRule() {
        runAs(1);
        var alpha = teacher(compare(1), 1);
        assertEquals("AVAILABLE", offering(alpha, 101).syllabusStatus().name());
        assertEquals("MISSING", offering(alpha, 102).syllabusStatus().name());
    }

    @Test
    void descriptionFollowsFormalCourseAndStaysNullForUnmappedOfferings() {
        runAs(1);
        var alpha = teacher(compare(1), 1);
        assertEquals("匿名测试课程简介：面向本科生的程序设计基础课程，覆盖数据结构、算法与工程实践入门。",
                offering(alpha, 101).description(), "101 maps to Course 201 with a stored description");
        assertEquals(null, offering(alpha, 102).description(), "102 has course_id NULL; no invented description");
    }

    @Test
    void typicalFiveTeacherRequestUsesSevenBoundedQueriesIncludingCurrentUser() {
        runAs(1);
        comparisonSqlCounter.reset();
        compare(1, 2, 3, 4, 5);
        assertEquals(List.of("identity", "scopes", "selectComparisonTeachers", "selectComparisonOfferings",
                "selectComparisonSchedules", "selectDetailWeeks", "selectDetailTeachers"),
                comparisonSqlCounter.statements());
    }

    @Test
    void real538RowSnapshotComparisonSmokeUsesOnlyAnonymousStatistics() throws Exception {
        Path snapshot = Path.of("..", ".work", "course-source-page.html").toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(snapshot), "local ignored real snapshot is unavailable");
        keepOnlyAdminAndSourceReferences();
        runAs(1);
        var imported = imports.importHtml(snapshot.getFileName().toString(), Files.readAllBytes(snapshot), 1, 1);
        assertEquals(538, imported.successRows());

        var selected = new LinkedHashSet<Long>();
        long sharedSchedule = jdbc.queryForObject("SELECT schedule_id FROM schedule_teacher GROUP BY schedule_id "
                + "HAVING COUNT(*)>1 ORDER BY schedule_id LIMIT 1", Long.class);
        selected.addAll(jdbc.queryForList("SELECT teacher_id FROM schedule_teacher WHERE schedule_id=? "
                + "ORDER BY teacher_id", Long.class, sharedSchedule));
        selected.add(jdbc.queryForObject("SELECT relation.teacher_id FROM schedule_teacher relation "
                + "JOIN class_schedule schedule ON schedule.id=relation.schedule_id "
                + "GROUP BY relation.teacher_id,schedule.offering_id HAVING COUNT(*)>1 "
                + "ORDER BY relation.teacher_id LIMIT 1", Long.class));
        for (Long teacherId : jdbc.queryForList("SELECT id FROM teacher ORDER BY id", Long.class)) {
            if (selected.size() == 5) break;
            selected.add(teacherId);
        }
        assertEquals(5, selected.size());

        comparisonSqlCounter.reset();
        var result = comparisons.compare(List.copyOf(selected));
        assertEquals(5, result.teachers().size());
        assertTrue(result.teachers().stream().flatMap(group -> group.offerings().stream())
                .flatMap(offering -> offering.teachingSchedules().stream())
                .anyMatch(schedule -> schedule.teachers().size() > 1));
        assertTrue(result.teachers().stream().flatMap(group -> group.offerings().stream())
                .anyMatch(offering -> offering.teachingSchedules().size() > 1));
        assertNoDuplicatesOrBrokenTeacherRelations(result);
        assertEquals(7, comparisonSqlCounter.statements().size());

        var offeringCounts = result.teachers().stream().map(group -> group.offerings().size()).toList();
        var scheduleCounts = result.teachers().stream()
                .map(group -> group.offerings().stream().mapToInt(o -> o.teachingSchedules().size()).sum()).toList();
        System.out.printf("COMPARISON_SMOKE teacher_groups=%d offering_counts=%s schedule_counts=%s result=PASS%n",
                result.teachers().size(), offeringCounts, scheduleCounts);
    }

    private void assertNoDuplicatesOrBrokenTeacherRelations(TeacherComparisonResult result) {
        for (var group : result.teachers()) {
            assertEquals(group.offerings().size(), new HashSet<>(offeringIds(group)).size());
            for (var offering : group.offerings()) {
                assertEquals(offering.teachingSchedules().size(), new HashSet<>(scheduleIds(offering)).size());
                for (var schedule : offering.teachingSchedules()) {
                    assertEquals(schedule.weeks().size(), new HashSet<>(schedule.weeks()).size());
                    assertTrue(teacherIds(schedule).contains(group.teacherId()));
                }
            }
        }
    }

    private TeacherComparisonResult compare(long... teacherIds) {
        var selection = new ArrayList<Long>();
        for (long teacherId : teacherIds) selection.add(teacherId);
        return comparisons.compare(selection);
    }

    private static TeacherComparison teacher(TeacherComparisonResult result, long teacherId) {
        return result.teachers().stream().filter(teacher -> teacher.teacherId() == teacherId).findFirst().orElseThrow();
    }

    private static TeacherComparisonOffering offering(TeacherComparison teacher, long offeringId) {
        return teacher.offerings().stream().filter(offering -> offering.offeringId() == offeringId)
                .findFirst().orElseThrow();
    }

    private static ScheduleDetail schedule(TeacherComparisonOffering offering, long scheduleId) {
        return schedule(offering.teachingSchedules(), scheduleId);
    }

    private static ScheduleDetail schedule(List<ScheduleDetail> schedules, long scheduleId) {
        return schedules.stream().filter(schedule -> schedule.scheduleId() == scheduleId).findFirst().orElseThrow();
    }

    private static List<Long> offeringIds(TeacherComparison teacher) {
        return teacher.offerings().stream().map(TeacherComparisonOffering::offeringId).toList();
    }

    private static List<Long> scheduleIds(TeacherComparisonOffering offering) {
        return scheduleIds(offering.teachingSchedules());
    }

    private static List<Long> scheduleIds(List<ScheduleDetail> schedules) {
        return schedules.stream().map(ScheduleDetail::scheduleId).toList();
    }

    private static List<Long> teacherIds(ScheduleDetail schedule) {
        return schedule.teachers().stream().map(teacher -> teacher.teacherId()).toList();
    }

    private MockHttpSession login(String username) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/v1/auth/login").with(csrf())
                        .param("username", username).param("password", TEST_PASSWORD))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    private void runAs(long accountId) {
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new AccountPrincipal(accountId, "comparison-test", "", true), null, List.of()));
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
        JavaMigration comparisonFixture() throws Exception {
            return new R__Phase1_acceptance_fixture(true, true);
        }

        @Bean
        ComparisonSqlCounter comparisonSqlCounter() {
            return new ComparisonSqlCounter();
        }
    }

    @Intercepts(@Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    static class ComparisonSqlCounter implements Interceptor {
        private static final Set<String> NAMES = Set.of(
                "identity", "scopes",
                "selectComparisonTeachers", "selectComparisonOfferings", "selectComparisonSchedules",
                "selectDetailWeeks", "selectDetailTeachers");
        private final List<String> statements = new CopyOnWriteArrayList<>();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            var statement = (MappedStatement) invocation.getArgs()[0];
            String name = statement.getId().substring(statement.getId().lastIndexOf('.') + 1);
            if (NAMES.contains(name)) statements.add(name);
            return invocation.proceed();
        }

        void reset() {
            statements.clear();
        }

        List<String> statements() {
            return List.copyOf(statements);
        }
    }
}
