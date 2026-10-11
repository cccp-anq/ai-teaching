package com.aijiaoxue.course;

import com.aijiaoxue.common.api.PageResult;
import com.aijiaoxue.common.web.RequestIdFilter;
import com.aijiaoxue.course.query.CourseOfferingListItem;
import com.aijiaoxue.course.query.CourseOfferingQueryCriteria;
import com.aijiaoxue.course.query.CourseOfferingQueryService;
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
import javax.sql.DataSource;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(CourseOfferingQueryIntegrationTest.FixtureConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional
class CourseOfferingQueryIntegrationTest {
    private static final String ENDPOINT = "/api/v1/course-offerings";
    private static final String TEST_PASSWORD = "Fixture-Only-2026!";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired WebApplicationContext context;
    @Autowired RequestIdFilter requestIds;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired CourseOfferingQueryService queries;
    @Autowired TimetableImportService imports;
    MockMvc mvc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) throws Exception {
        boolean mysql = Boolean.getBoolean("mysqlMigration");
        String url = mysql ? Objects.requireNonNull(System.getenv("MIGRATION_TEST_URL"))
                : "jdbc:h2:mem:phase1-fixture-query;MODE=MySQL;DB_CLOSE_DELAY=-1";
        if (mysql && !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/ai_teaching_migration_[a-f0-9]{32}\\?sslMode=DISABLED&allowPublicKeyRetrieval=true"))
            throw new IllegalArgumentException("Dedicated MySQL test database required");
        String user = mysql ? Objects.requireNonNull(System.getenv("MIGRATION_TEST_USERNAME")) : "sa";
        String password = mysql ? Objects.requireNonNull(System.getenv("MIGRATION_TEST_PASSWORD")) : "";
        try (var db = DriverManager.getConnection(url, user, password);
             var tables = db.getMetaData().getTables(db.getCatalog(), mysql ? null : "PUBLIC", "%", new String[]{"TABLE"})) {
            assertFalse(tables.next(), "Query suite refuses nonempty databases");
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> user);
        properties.add("spring.datasource.password", () -> password);
        properties.add("spring.datasource.driver-class-name", () -> mysql ? "com.mysql.cj.jdbc.Driver" : "org.h2.Driver");
        properties.add("server.servlet.session.cookie.secure", () -> false);
    }

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIds).apply(springSecurity()).build();
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
    void qry01UnfilteredQueryStartsFromAuthorizedDataset() {
        runAs(3);
        var result = search(null, null, null, null, null, null, null, null, null);
        assertEquals(4, result.total());
        assertEquals(Set.of(101L, 102L, 103L, 104L), ids(result));
        assertEquals(result.items(), search(null, null, null, null, null, null, null, null, null).items());
    }

    @Test
    void qry02NameUsesOfferingDisplayNameWithoutFormalCourseMapping() {
        runAs(2);
        var result = search(null, " 共同授课-测试 ", null, null, null, null, null, null, null);
        assertEquals(List.of(102L), orderedIds(result));
        assertEquals(null, result.items().get(0).courseCode());
    }

    @Test
    void qry28ApprovedTextMatchingAndListProjectionAreComplete() {
        jdbc.update("UPDATE course SET course_code='Case-Code-201' WHERE id=201");
        runAs(2);
        var byName = search(null, "共同授课", null, null, null, null, null, null, null);
        assertEquals(List.of(102L), orderedIds(byName), "courseName uses literal contains matching");
        assertEquals(Set.of(101L, 102L, 103L), ids(search(
                null, null, null, "alpha", null, null, null, null, null)));
        assertEquals(Set.of(101L, 110L), ids(search(
                null, null, "case-code-201", null, null, null, null, null, null)));
        assertTrue(ids(search(null, null, null, null, null, null, null, null, "地点a")).contains(104L));

        var item = search(null, "普通本科", null, null, null, null, null, null, null).items().get(0);
        assertNotNull(item.ownerCollege());
        assertEquals(1L, item.ownerCollege().collegeId());
        assertEquals(2, item.schedules().size(), "list returns every schedule without duplicating the offering");
        assertFalse(item.schedules().get(0).weeks().isEmpty());
        assertFalse(item.schedules().get(0).teachers().isEmpty());
        assertNotNull(item.syllabusStatus());
    }

    @Test
    void qry03CourseCodeOnlyMatchesAStoredFormalCodeAndNullsRemainSafe() {
        jdbc.update("UPDATE course SET course_code='REAL-TEST-201' WHERE id=201");
        runAs(2);
        var result = search(null, null, " REAL-TEST-201 ", null, null, null, null, null, null);
        assertEquals(Set.of(101L, 110L), ids(result));
        assertTrue(result.items().stream().allMatch(item -> "REAL-TEST-201".equals(item.courseCode())));
        assertEquals(0, search(null, null, "MISSING-CODE", null, null, null, null, null, null).total());
    }

    @Test
    void qry04SingleTeacherFindsEveryParticipatingOfferingOnce() {
        runAs(2);
        var result = search(null, null, null, "Teacher Alpha", null, null, null, null, null);
        assertEquals(Set.of(101L, 102L, 103L), ids(result));
        assertEquals(3, result.total());
    }

    @Test
    void qry05EitherCoTeacherFindsTheSameOfferingAndSummaryIsDeduplicated() {
        runAs(2);
        var alpha = search(null, "共同授课-测试", null, "Teacher Alpha", null, null, null, null, null);
        var beta = search(null, "共同授课-测试", null, "Teacher Beta", null, null, null, null, null);
        assertEquals(List.of(102L), orderedIds(alpha));
        assertEquals(List.of(102L), orderedIds(beta));
        assertEquals(List.of("Teacher Alpha", "Teacher Beta"), alpha.items().get(0).teacherNames());
    }

    @Test
    void qry06TeachersOnDifferentSchedulesEachFindOneOffering() {
        runAs(2);
        assertEquals(List.of(103L), orderedIds(search(null, "分周换教师-测试", null,
                "Teacher Alpha", null, null, null, null, null)));
        assertEquals(List.of(103L), orderedIds(search(null, "分周换教师-测试", null,
                "Teacher Gamma", null, null, null, null, null)));
    }

    @Test
    void qry07MultipleSchedulesTeachersAndWeeksDoNotBreakPagingOrTotal() {
        runAs(2);
        var result = search(null, "混合地点-测试", null, null, null, null, null, null, null);
        assertEquals(1, result.total());
        assertEquals(List.of(107L), orderedIds(result));
        assertEquals(List.of("Teacher Gamma"), result.items().get(0).teacherNames());

        var first = queries.search(criteria(null, null, null, null, null, null, null, null, null, 1, 1));
        var second = queries.search(criteria(null, null, null, null, null, null, null, null, null, 2, 1));
        assertEquals(10, first.total());
        assertEquals(10, second.total());
        assertNotEquals(first.items().get(0).offeringId(), second.items().get(0).offeringId());
        assertEquals(first.items(), queries.search(criteria(null, null, null, null, null, null, null, null, null, 1, 1)).items());
    }

    @Test
    void qry08SemesterUsesOfferingSemesterId() {
        runAs(2);
        assertEquals(List.of(110L), orderedIds(search(2L, null, null, null, null, null, null, null, null)));
        assertEquals(0, search(999L, null, null, null, null, null, null, null, null).total());
    }

    @Test
    void qry09SourceScopeIsNotOwnerCollege() {
        jdbc.update("UPDATE course SET owner_college_id=1 WHERE id=202");
        runAs(2);
        assertEquals(List.of(109L), orderedIds(search(null, null, null, null, 2L, null, null, null, null)));
    }

    @Test
    void qry10WeekUsesNormalizedScheduleWeekRows() {
        runAs(2);
        assertEquals(List.of(101L), orderedIds(search(null, null, null, null, null, 18, null, null, null)));
        assertEquals(0, search(null, "离散周次-测试", null, null, null, 9, null, null, null).total());
    }

    @Test
    void qry11WeekdayFiltersSchedules() {
        runAs(2);
        assertEquals(List.of(109L), orderedIds(search(null, null, null, null, null, null, 6, null, null)));
    }

    @Test
    void qry12SectionMeansPointContainedByScheduleRange() {
        runAs(2);
        assertEquals(List.of(105L), orderedIds(search(null, null, null, null, null, null, null, 11, null)));
    }

    @Test
    void qry13And14OnlineAndTbdAreLegalLocationFilters() {
        runAs(2);
        assertEquals(List.of(107L), orderedIds(search(null, null, null, null, null, null, null, null, "线上")));
        assertEquals(List.of(107L), orderedIds(search(null, null, null, null, null, null, null, null, "待定")));
    }

    @Test
    void qry15And16Ac03UsesOneScheduleForWeekAndLocation() {
        runAs(2);
        assertEquals(List.of(104L), orderedIds(search(null, "AC-03时间地点-测试", null, null,
                null, 1, null, null, "测试地点A")));
        assertEquals(List.of(104L), orderedIds(search(null, "AC-03时间地点-测试", null, null,
                null, 2, null, null, "测试地点B")));
        assertEquals(0, search(null, "AC-03时间地点-测试", null, null,
                null, 1, null, null, "测试地点B").total());
        assertEquals(0, search(null, "AC-03时间地点-测试", null, null,
                null, 2, null, null, "测试地点A").total());
    }

    @Test
    void qry17AllScheduleConditionsMustMatchTheSameSchedule() {
        runAs(2);
        assertEquals(List.of(105L), orderedIds(search(null, "离散周次-测试", null, null,
                null, 10, 5, 11, "测试地点B")));
        assertEquals(0, search(null, "离散周次-测试", null, null,
                null, 1, 5, 11, "测试地点B").total());
    }

    @Test
    void qry18TeacherAndScheduleFiltersCannotCrossSchedules() {
        jdbc.update("UPDATE class_schedule SET location_id=2 WHERE id=1005");
        runAs(2);
        assertEquals(0, search(null, "分周换教师-测试", null, "Teacher Alpha",
                null, null, null, null, "测试地点B").total());
        assertEquals(List.of(103L), orderedIds(search(null, "分周换教师-测试", null, "Teacher Gamma",
                null, null, null, null, "测试地点B")));
    }

    @Test
    void qry19IndependentAndScheduleFiltersComposeWithAnd() {
        runAs(2);
        assertEquals(List.of(104L), orderedIds(search(1L, "AC-03时间地点-测试", null, "Teacher Beta",
                1L, 1, 1, 1, "测试地点A")));
    }

    @Test
    void qry20LegalNoResultReturnsAnEmptyPage() throws Exception {
        var session = login("director-a-test");
        mvc.perform(get(ENDPOINT).session(session).param("courseName", "不存在的测试课程"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void qry25OwnerCollegeFiltersUseFormalCourseOwnershipOnly() {
        runAs(2);
        // Fixture: 101/110 map to Course 201 (owner college 1), 109 to Course 202 (owner college 2),
        // 102..108 have course_id NULL and therefore match no owner college at all.
        assertEquals(Set.of(101L, 110L), ids(queries.search(new CourseOfferingQueryCriteria(null, null, null, null, null, 1L, null, null, null, null, 1, 100))));
        assertEquals(Set.of(109L), ids(queries.search(new CourseOfferingQueryCriteria(null, null, null, null, null, 2L, null, null, null, null, 1, 100))));
        // Null Course offerings never match a concrete owner college; no "unmapped" filter exists.
        assertEquals(Set.of(), ids(queries.search(new CourseOfferingQueryCriteria(null, null, null, null, 2L, 1L, null, null, null, null, 1, 100))));
    }

    @Test
    void qry26OwnerCollegeComposesWithOfferingAndScheduleFilters() {
        runAs(2);
        assertEquals(Set.of(101L), ids(queries.search(new CourseOfferingQueryCriteria(1L, null, null, null, null, 1L, null, null, null, null, 1, 100))));
        // sourceScope stays an independent Offering-level condition: 101 and 110 are scope 1 + owner 1,
        // so combining both filters still returns both; scope 2 + owner 1 has no offering.
        assertEquals(Set.of(101L, 110L), ids(queries.search(new CourseOfferingQueryCriteria(null, null, null, null, 1L, 1L, null, null, null, null, 1, 100))));
        assertEquals(Set.of(), ids(queries.search(new CourseOfferingQueryCriteria(null, null, null, null, 2L, 1L, null, null, null, null, 1, 100))));
        // Owner college is a Course-level predicate: it must stay outside the Same-Schedule EXISTS.
        // week=18 only exists on offering 101 (schedule 1002); 110's schedule 1016 covers weeks 1-4.
        assertEquals(Set.of(101L), ids(queries.search(new CourseOfferingQueryCriteria(null, null, null, null, null, 1L, 18, null, null, null, 1, 100))));
        assertEquals(Set.of(101L, 110L), ids(queries.search(new CourseOfferingQueryCriteria(null, null, null, null, null, 1L, 1, null, null, null, 1, 100))));
    }

    @Test
    void qry27OwnerCollegeCannotWidenAuthorizationOrLeakHiddenOfferings() {
        runAs(3);
        assertEquals(Set.of(101L), ids(queries.search(new CourseOfferingQueryCriteria(null, null, null, null, null, 1L, null, null, null, null, 1, 100))));
        // Offering 109 (owner college 2) is outside the teacher's dataset: no leak via ownerCollegeId=2.
        assertEquals(Set.of(), ids(queries.search(new CourseOfferingQueryCriteria(null, null, null, null, null, 2L, null, null, null, null, 1, 100))));
    }

    @Test
    void qry21And22ItemsAndTotalCannotRevealAnUnauthorizedScope() throws Exception {
        var session = login("teacher-a-test");
        mvc.perform(get(ENDPOINT).session(session)
                        .param("courseName", "人工范围B-测试")
                        .param("sourceScopeCollegeId", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void qry23ZeroStudentCountRemainsNumericZero() throws Exception {
        var session = login("director-a-test");
        mvc.perform(get(ENDPOINT).session(session).param("courseName", "零人数-测试"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].studentCount").value(0));
    }

    @Test
    void qry24BlankTextFiltersAreNormalizedAsAbsent() throws Exception {
        var session = login("teacher-a-test");
        mvc.perform(get(ENDPOINT).session(session)
                        .param("courseName", "   ").param("teacher", " ").param("location", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4));
    }

    @Test
    void myScheduleSeparatesTeachingAndSupervisionWithoutUsingGlobalReadAsPersonalWork() throws Exception {
        mvc.perform(get("/api/v1/my-schedule").session(login("teacher-a-test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teaching.length()").value(3))
                .andExpect(jsonPath("$.supervision.length()").value(1))
                .andExpect(jsonPath("$.supervision[0].offeringId").value(104));
        mvc.perform(get("/api/v1/my-schedule").session(login("supervisor-a-test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teaching.length()").value(0))
                .andExpect(jsonPath("$.supervision.length()").value(1))
                .andExpect(jsonPath("$.supervision[0].offeringId").value(104));
        mvc.perform(get("/api/v1/my-schedule").session(login("director-a-test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teaching.length()").value(0))
                .andExpect(jsonPath("$.supervision.length()").value(0));
    }

    @Test
    void apiRequiresAuthenticationButGetNeedsNoCsrfAndValidatesRanges() throws Exception {
        mvc.perform(get(ENDPOINT)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        var session = login("director-a-test");
        mvc.perform(get(ENDPOINT).session(session).param("week", "1"))
                .andExpect(status().isOk());
        for (String week : List.of("0", "19"))
            mvc.perform(get(ENDPOINT).session(session).param("week", week))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        for (String weekday : List.of("0", "8"))
            mvc.perform(get(ENDPOINT).session(session).param("weekday", weekday))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        for (String section : List.of("0", "32768"))
            mvc.perform(get(ENDPOINT).session(session).param("section", section))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void real538RowSnapshotQuerySmokeUsesOnlyAnonymousAssertions() throws Exception {
        Path snapshot = Path.of("..", ".work", "course-source-page.html").toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(snapshot), "local ignored real snapshot is unavailable");
        keepOnlyAdminAndSourceReferences();
        runAs(1);
        var imported = imports.importHtml(snapshot.getFileName().toString(), Files.readAllBytes(snapshot), 1, 1);
        assertEquals(538, imported.successRows());
        assertEquals(282, imported.createdOfferings());

        var firstPage = queries.search(criteria(null, null, null, null, null, null, null, null, null, 1, 100));
        assertEquals(282, firstPage.total());
        var all = new ArrayList<CourseOfferingListItem>();
        for (int page = 1; page <= 3; page++)
            all.addAll(queries.search(criteria(null, null, null, null, null, null, null, null, null, page, 100)).items());
        assertEquals(282, all.size());
        assertEquals(15, all.stream().filter(item -> Integer.valueOf(0).equals(item.studentCount())).count());
        assertEquals(11, search(null, null, null, null, null, null, null, null, "线上").total());
        assertEquals(1, search(null, null, null, null, null, null, null, null, "待定").total());
        String anonymousTeacher = jdbc.queryForObject("SELECT normalized_name FROM teacher ORDER BY id LIMIT 1", String.class);
        assertEquals(5, search(null, null, null, anonymousTeacher, null, null, null, null, null).total());
    }

    private PageResult<CourseOfferingListItem> search(Long semesterId, String courseName, String courseCode,
                                                       String teacherName, Long sourceScopeCollegeId, Integer week,
                                                       Integer weekday, Integer section, String location) {
        return queries.search(criteria(semesterId, courseName, courseCode, teacherName, sourceScopeCollegeId,
                week, weekday, section, location, 1, 100));
    }

    private static CourseOfferingQueryCriteria criteria(Long semesterId, String courseName, String courseCode,
                                                         String teacherName, Long sourceScopeCollegeId, Integer week,
                                                         Integer weekday, Integer section, String location,
                                                         int page, int size) {
        return new CourseOfferingQueryCriteria(semesterId, courseName, courseCode, teacherName,
                sourceScopeCollegeId, null, week, weekday, section, location, page, size);
    }

    private static Set<Long> ids(PageResult<CourseOfferingListItem> result) {
        var ids = new HashSet<Long>();
        result.items().forEach(item -> ids.add(item.offeringId()));
        return ids;
    }

    private static List<Long> orderedIds(PageResult<CourseOfferingListItem> result) {
        return result.items().stream().map(CourseOfferingListItem::offeringId).toList();
    }

    private MockHttpSession login(String username) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/v1/auth/login").with(csrf())
                        .param("username", username).param("password", TEST_PASSWORD))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    private void runAs(long accountId) {
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new AccountPrincipal(accountId, "query-test", "", true), null, List.of()));
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
        JavaMigration queryFixture() throws Exception {
            return new R__Phase1_acceptance_fixture(true, true);
        }
    }
}
