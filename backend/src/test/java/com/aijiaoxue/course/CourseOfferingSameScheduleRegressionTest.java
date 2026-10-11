package com.aijiaoxue.course;

import com.aijiaoxue.common.api.PageResult;
import com.aijiaoxue.course.query.CourseOfferingListItem;
import com.aijiaoxue.course.query.CourseOfferingQueryCriteria;
import com.aijiaoxue.course.query.CourseOfferingQueryService;
import com.aijiaoxue.fixtures.R__Phase1_acceptance_fixture;
import com.aijiaoxue.identity.authentication.AccountPrincipal;
import java.sql.DriverManager;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.migration.JavaMigration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(CourseOfferingSameScheduleRegressionTest.FixtureConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional
class CourseOfferingSameScheduleRegressionTest {
    private static final long AC03 = 104L;
    private static final String AC03_NAME = "AC-03时间地点-测试";
    private static final String LOCATION_A = "测试地点A";
    private static final String LOCATION_B = "测试地点B";
    private static final String TEACHER_ALPHA = "Teacher Alpha";
    private static final String TEACHER_BETA = "Teacher Beta";

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired CourseOfferingQueryService queries;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) throws Exception {
        boolean mysql = Boolean.getBoolean("mysqlMigration");
        String url = mysql ? Objects.requireNonNull(System.getenv("MIGRATION_TEST_URL"))
                : "jdbc:h2:mem:phase1-fixture-same-schedule;MODE=MySQL;DB_CLOSE_DELAY=-1";
        if (mysql && !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/ai_teaching_migration_[a-f0-9]{32}\\?sslMode=DISABLED&allowPublicKeyRetrieval=true"))
            throw new IllegalArgumentException("Dedicated MySQL test database required");
        String user = mysql ? Objects.requireNonNull(System.getenv("MIGRATION_TEST_USERNAME")) : "sa";
        String password = mysql ? Objects.requireNonNull(System.getenv("MIGRATION_TEST_PASSWORD")) : "";
        try (var db = DriverManager.getConnection(url, user, password);
             var tables = db.getMetaData().getTables(db.getCatalog(), mysql ? null : "PUBLIC", "%", new String[]{"TABLE"})) {
            assertFalse(tables.next(), "Same-Schedule suite refuses nonempty databases");
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> user);
        properties.add("spring.datasource.password", () -> password);
        properties.add("spring.datasource.driver-class-name",
                () -> mysql ? "com.mysql.cj.jdbc.Driver" : "org.h2.Driver");
        properties.add("server.servlet.session.cookie.secure", () -> false);
    }

    @BeforeEach
    void applySameScheduleFixtureOverlay() {
        jdbc.update("UPDATE class_schedule SET weekday=1,start_section=1,end_section=2,location_id=1 WHERE id=1006");
        jdbc.update("UPDATE class_schedule SET weekday=2,start_section=3,end_section=4,location_id=2 WHERE id=1007");
        jdbc.update("DELETE FROM schedule_teacher WHERE schedule_id IN (1006,1007)");
        jdbc.update("INSERT INTO schedule_teacher(schedule_id,teacher_id) VALUES (1006,1),(1007,2)");
        runAs(1);
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
    void ac03WeekAndLocationCannotCrossSchedules() {
        assertAc03Hit(null, 1, null, null, LOCATION_A);
        assertAc03Hit(null, 2, null, null, LOCATION_B);
        assertAc03Miss(null, 1, null, null, LOCATION_B);
        assertAc03Miss(null, 2, null, null, LOCATION_A);
    }

    @Test
    void weekAndWeekdayCannotCrossSchedules() {
        assertAc03Hit(null, 1, 1, null, null);
        assertAc03Hit(null, 2, 2, null, null);
        assertAc03Miss(null, 1, 2, null, null);
        assertAc03Miss(null, 2, 1, null, null);
    }

    @Test
    void weekAndSectionCannotCrossSchedules() {
        assertAc03Hit(null, 1, null, 1, null);
        assertAc03Hit(null, 2, null, 3, null);
        assertAc03Miss(null, 1, null, 3, null);
        assertAc03Miss(null, 2, null, 1, null);
    }

    @Test
    void weekdayAndLocationCannotCrossSchedules() {
        assertAc03Hit(null, null, 1, null, LOCATION_A);
        assertAc03Hit(null, null, 2, null, LOCATION_B);
        assertAc03Miss(null, null, 1, null, LOCATION_B);
        assertAc03Miss(null, null, 2, null, LOCATION_A);
    }

    @Test
    void sectionAndLocationCannotCrossSchedules() {
        assertAc03Hit(null, null, null, 1, LOCATION_A);
        assertAc03Hit(null, null, null, 3, LOCATION_B);
        assertAc03Miss(null, null, null, 1, LOCATION_B);
        assertAc03Miss(null, null, null, 3, LOCATION_A);
    }

    @Test
    void teacherAndWeekCannotCrossSchedules() {
        assertAc03Hit(TEACHER_ALPHA, 1, null, null, null);
        assertAc03Hit(TEACHER_BETA, 2, null, null, null);
        assertAc03Miss(TEACHER_ALPHA, 2, null, null, null);
        assertAc03Miss(TEACHER_BETA, 1, null, null, null);
    }

    @Test
    void teacherAndLocationCannotCrossSchedules() {
        assertAc03Hit(TEACHER_ALPHA, null, null, null, LOCATION_A);
        assertAc03Hit(TEACHER_BETA, null, null, null, LOCATION_B);
        assertAc03Miss(TEACHER_ALPHA, null, null, null, LOCATION_B);
        assertAc03Miss(TEACHER_BETA, null, null, null, LOCATION_A);
    }

    @Test
    void teacherAndWeekdayCannotCrossSchedules() {
        assertAc03Hit(TEACHER_ALPHA, null, 1, null, null);
        assertAc03Hit(TEACHER_BETA, null, 2, null, null);
        assertAc03Miss(TEACHER_ALPHA, null, 2, null, null);
        assertAc03Miss(TEACHER_BETA, null, 1, null, null);
    }

    @Test
    void teacherAndSectionCannotCrossSchedules() {
        assertAc03Hit(TEACHER_ALPHA, null, null, 1, null);
        assertAc03Hit(TEACHER_BETA, null, null, 3, null);
        assertAc03Miss(TEACHER_ALPHA, null, null, 3, null);
        assertAc03Miss(TEACHER_BETA, null, null, 1, null);
    }

    @Test
    void fullSchedulePredicatesUseOneScheduleAlias() {
        assertAc03Hit(TEACHER_ALPHA, 1, 1, 1, LOCATION_A);
        assertAc03Hit(TEACHER_BETA, 2, 2, 3, LOCATION_B);

        assertAc03Miss(TEACHER_ALPHA, 2, 1, 1, LOCATION_A);
        assertAc03Miss(TEACHER_ALPHA, 1, 2, 1, LOCATION_A);
        assertAc03Miss(TEACHER_ALPHA, 1, 1, 3, LOCATION_A);
        assertAc03Miss(TEACHER_ALPHA, 1, 1, 1, LOCATION_B);
        assertAc03Miss(TEACHER_BETA, 1, 1, 1, LOCATION_A);
    }

    @Test
    void everySingleScheduleFilterCanFindEitherAc03Schedule() {
        assertContainsAc03ExactlyOnce(search(null, null, null, null, 1, null, null, null, 1, 100));
        assertContainsAc03ExactlyOnce(search(null, null, null, null, 2, null, null, null, 1, 100));
        assertContainsAc03ExactlyOnce(search(null, null, null, null, null, 1, null, null, 1, 100));
        assertContainsAc03ExactlyOnce(search(null, null, null, null, null, 2, null, null, 1, 100));
        assertContainsAc03ExactlyOnce(search(null, null, null, null, null, null, 1, null, 1, 100));
        assertContainsAc03ExactlyOnce(search(null, null, null, null, null, null, 3, null, 1, 100));
        assertContainsAc03ExactlyOnce(search(null, null, TEACHER_ALPHA, null, null, null, null, null, 1, 100));
        assertContainsAc03ExactlyOnce(search(null, null, TEACHER_BETA, null, null, null, null, null, 1, 100));
        assertContainsAc03ExactlyOnce(search(null, null, null, null, null, null, null, LOCATION_A, 1, 100));
        assertContainsAc03ExactlyOnce(search(null, null, null, null, null, null, null, LOCATION_B, 1, 100));
    }

    @Test
    void offeringFiltersDoNotWeakenSameSchedulePredicates() {
        assertOnlyAc03(search(1L, AC03_NAME, null, 1L, 1, null, null, LOCATION_A, 1, 100));
        assertEmpty(search(1L, AC03_NAME, null, 1L, 1, null, null, LOCATION_B, 1, 100));
    }

    @Test
    void multipleSchedulesNeverDuplicateItemsTotalOrPagination() {
        var unfiltered = search(null, null, null, null, null, null, null, null, 1, 100);
        assertEquals(10, unfiltered.total());
        assertEquals(1, occurrenceCount(unfiltered, AC03));

        var first = search(null, AC03_NAME, null, null, null, null, null, null, 1, 1);
        var beyondLast = search(null, AC03_NAME, null, null, null, null, null, null, 2, 1);
        assertOnlyAc03(first);
        assertEquals(1, beyondLast.total());
        assertTrue(beyondLast.items().isEmpty());
    }

    @Test
    void equalNamesKeepOfferingIdAsTheStableTieBreaker() {
        jdbc.update("UPDATE course_offering SET display_name='AA-STABLE-ORDER' WHERE id IN (101,102)");
        var first = search(null, "AA-STABLE-ORDER", null, null, null, null, null, null, 1, 100);
        var repeated = search(null, "AA-STABLE-ORDER", null, null, null, null, null, null, 1, 100);
        assertEquals(List.of(101L, 102L), offeringIds(first));
        assertEquals(first.items(), repeated.items());
        assertEquals(2, first.total());
    }

    @Test
    void coTeachersOnOneScheduleEachFindOneOffering() {
        var alpha = search(null, "共同授课-测试", TEACHER_ALPHA, null,
                null, null, null, null, 1, 100);
        var beta = search(null, "共同授课-测试", TEACHER_BETA, null,
                null, null, null, null, 1, 100);
        assertEquals(List.of(102L), offeringIds(alpha));
        assertEquals(List.of(102L), offeringIds(beta));
        assertEquals(1, alpha.total());
        assertEquals(1, beta.total());
        assertEquals(List.of(TEACHER_ALPHA, TEACHER_BETA), alpha.items().get(0).teacherNames());
    }

    @Test
    void globalSelfTeacherAndOfferingScopesStillUseAuthorizedDatasetFirst() {
        runAs(1);
        assertAc03Hit(TEACHER_ALPHA, 1, 1, 1, LOCATION_A);

        jdbc.update("DELETE FROM user_role_scope WHERE id=6");
        runAs(3);
        assertAc03Hit(TEACHER_ALPHA, 1, 1, 1, LOCATION_A);

        runAs(5);
        assertAc03Hit(TEACHER_BETA, 2, 2, 3, LOCATION_B);

        runAs(3);
        assertEmpty(search(null, "人工范围B-测试", null, 2L,
                null, null, null, null, 1, 100));
        assertEmpty(search(null, null, "Teacher Epsilon", 2L,
                null, null, null, null, 1, 100));
        assertEmpty(search(null, null, null, 2L,
                null, null, null, LOCATION_B, 1, 100));
        assertEmpty(search(null, null, null, 2L,
                1, null, null, null, 1, 100));
    }

    @Test
    void onlineTbdAndZeroStudentsRemainQueryable() {
        assertOnlyOffering(107L, search(null, "混合地点-测试", null, null,
                null, null, null, "线上", 1, 100));
        assertOnlyOffering(107L, search(null, "混合地点-测试", null, null,
                null, null, null, "待定", 1, 100));
        var zero = search(null, "零人数-测试", null, null,
                null, null, null, null, 1, 100);
        assertOnlyOffering(106L, zero);
        assertEquals(0, zero.items().get(0).studentCount());
    }

    private void assertAc03Hit(String teacher, Integer week, Integer weekday,
                               Integer section, String location) {
        assertOnlyAc03(search(null, AC03_NAME, teacher, null, week, weekday, section, location, 1, 100));
    }

    private void assertAc03Miss(String teacher, Integer week, Integer weekday,
                                Integer section, String location) {
        assertEmpty(search(null, AC03_NAME, teacher, null, week, weekday, section, location, 1, 100));
    }

    private void assertOnlyAc03(PageResult<CourseOfferingListItem> result) {
        assertOnlyOffering(AC03, result);
    }

    private void assertOnlyOffering(long offeringId, PageResult<CourseOfferingListItem> result) {
        assertEquals(1, result.total());
        assertEquals(List.of(offeringId), offeringIds(result));
        assertEquals(1, occurrenceCount(result, offeringId));
    }

    private static void assertEmpty(PageResult<CourseOfferingListItem> result) {
        assertEquals(0, result.total());
        assertTrue(result.items().isEmpty());
    }

    private static void assertContainsAc03ExactlyOnce(PageResult<CourseOfferingListItem> result) {
        assertEquals(1, occurrenceCount(result, AC03));
    }

    private PageResult<CourseOfferingListItem> search(Long semesterId, String courseName, String teacher,
                                                       Long sourceScopeCollegeId, Integer week, Integer weekday,
                                                       Integer section, String location, int page, int size) {
        return queries.search(new CourseOfferingQueryCriteria(semesterId, courseName, null, teacher,
                sourceScopeCollegeId, null, week, weekday, section, location, page, size));
    }

    private static long occurrenceCount(PageResult<CourseOfferingListItem> result, long offeringId) {
        return result.items().stream().filter(item -> item.offeringId() == offeringId).count();
    }

    private static List<Long> offeringIds(PageResult<CourseOfferingListItem> result) {
        return result.items().stream().map(CourseOfferingListItem::offeringId).toList();
    }

    private void runAs(long accountId) {
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new AccountPrincipal(accountId, "same-schedule-test", "", true), null, List.of()));
        SecurityContextHolder.setContext(security);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {
        @Bean
        JavaMigration sameScheduleFixture() throws Exception {
            return new R__Phase1_acceptance_fixture(true, true);
        }
    }
}
