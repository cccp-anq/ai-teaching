package com.aijiaoxue;

import com.aijiaoxue.course.WeekMask;
import com.aijiaoxue.fixtures.R__Phase1_acceptance_fixture;
import com.aijiaoxue.importing.SourceKeys;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FixtureIntegrationTest {
    private Connection db;
    private String url, user, password;
    private int initialMigrations;
    private boolean initialized;
    private static final JsonMapper JSON=JsonMapper.builder().build();

    @BeforeAll void initialize() throws Exception {
        boolean mysql=Boolean.getBoolean("mysqlMigration");
        url=mysql ? Objects.requireNonNull(System.getenv("MIGRATION_TEST_URL"))
                : "jdbc:h2:mem:phase1-fixture-acceptance;MODE=MySQL;DB_CLOSE_DELAY=-1";
        if(mysql) assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/ai_teaching_migration_[a-f0-9]{32}\\?sslMode=DISABLED&allowPublicKeyRetrieval=true"));
        user=mysql ? Objects.requireNonNull(System.getenv("MIGRATION_TEST_USERNAME")) : "sa";
        password=mysql ? Objects.requireNonNull(System.getenv("MIGRATION_TEST_PASSWORD")) : "";
        db=DriverManager.getConnection(url,user,password);
        try(var tables=db.getMetaData().getTables(db.getCatalog(),mysql ? null : "PUBLIC","%",new String[]{"TABLE"})) {
            assertFalse(tables.next(),"Fixture suite requires a fresh database; existing data is never dropped");
        }
        initialMigrations=migration(true).migrate().migrationsExecuted;
        initialized=true;
    }

    @AfterAll void cleanup() throws Exception {
        if(db==null) return;
        try { if(initialized) { migration(false).migrate(); assertEmpty(); } } finally { db.close(); }
    }

    private Flyway migration(boolean populated) throws Exception {
        return Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration")
                .javaMigrations(new R__Phase1_acceptance_fixture(populated))
                .cleanDisabled(true).baselineOnMigrate(false).load();
    }

    @Test void initializationAndUnchangedMigrationAreRepeatable() throws Exception {
        assertEquals(22,initialMigrations); // Production V1..V21 plus explicitly registered repeatable seed.
        assertEquals("21",migration(true).info().current().getVersion().toString());
        var before=snapshot();
        assertEquals(0,migration(true).migrate().migrationsExecuted);
        migration(true).validate();
        assertEquals(before,snapshot());
    }

    @Test void clearAndReloadPreserveEveryValueAndStableId() throws Exception {
        var before=snapshot();
        try {
            assertEquals(1,migration(false).migrate().migrationsExecuted);
            assertEmpty();
            assertEquals(0,migration(false).migrate().migrationsExecuted);
        } finally { migration(true).migrate(); }
        assertEquals(before,snapshot(),"All columns, source keys, JSON, links and timestamps must repeat");
    }

    @Test void cleanupDoesNotDeleteUnrelatedData() throws Exception {
        execute("INSERT INTO college(id,college_code,college_name,created_at,updated_at) VALUES (99999,'OTHER-TEST','Unrelated','2026-08-30','2026-08-30')");
        try {
            migration(false).migrate();
            assertEquals(1,number("SELECT COUNT(*) FROM college"));
            assertEquals("Unrelated",string("SELECT college_name FROM college WHERE id=99999"));
        } finally {
            execute("DELETE FROM college WHERE id=99999");
            migration(true).migrate();
        }
    }

    @Test void failedCleanupRollsBackAlreadyDeletedSchedulesAndWeeks() throws Exception {
        // External reference intentionally blocks a late DELETE, after child rows were removed.
        execute("INSERT INTO user_account(id,username,password_hash,teacher_id,enabled,created_at,updated_at) VALUES (99999,'external-test','disabled',1,FALSE,'2026-08-30','2026-08-30')");
        var before=snapshot();
        Configuration configuration=migration(false).getConfiguration();
        try {
            assertThrows(SQLException.class,()->new R__Phase1_acceptance_fixture(false).migrate(new Context() {
                public Configuration getConfiguration() { return configuration; }
                public Connection getConnection() { return db; }
            }));
            assertEquals(before,snapshot());
        } finally { execute("DELETE FROM user_account WHERE id=99999"); }
    }

    @Test void exactDatasetCountsAreStable() throws Exception {
        String[] tables={"college","semester","teacher","course_offering","class_schedule","schedule_week","schedule_teacher","location","source_timetable_row","course","import_batch","user_account","role","user_role_scope"};
        long[] expected={2,2,5,10,16,61,17,4,16,2,3,5,4,6};
        for(int i=0;i<tables.length;i++) assertEquals(expected[i],number("SELECT COUNT(*) FROM "+tables[i]),tables[i]);
    }

    @Test void failedLoadRollsBackSchedulesWeeksAndTeachersTogether() throws Exception {
        migration(false).migrate();
        execute("INSERT INTO user_account(id,username,password_hash,enabled,created_at,updated_at) VALUES (99999,'admin-test','disabled',FALSE,'2026-08-30','2026-08-30')");
        var before=snapshot();
        Configuration configuration=migration(true).getConfiguration();
        try {
            // User insertion comes after schedules/weeks/teachers; the duplicate username fails late.
            assertThrows(SQLException.class,()->new R__Phase1_acceptance_fixture(true).migrate(new Context() {
                public Configuration getConfiguration() { return configuration; }
                public Connection getConnection() { return db; }
            }));
            assertEquals(before,snapshot());
        } finally {
            execute("DELETE FROM user_account WHERE id=99999");
            migration(true).migrate();
        }
    }

    @Test void fixtureMigrationRefusesOrdinaryDatabaseUrls() throws Exception {
        try(var ordinary=DriverManager.getConnection("jdbc:h2:mem:ordinary-database","sa","")) {
            Configuration configuration=migration(true).getConfiguration();
            var failure=assertThrows(SQLException.class,()->new R__Phase1_acceptance_fixture(true).migrate(new Context() {
                public Configuration getConfiguration() { return configuration; }
                public Connection getConnection() { return ordinary; }
            }));
            assertTrue(failure.getMessage().contains("isolated acceptance test database"));
        }
    }

    @Test void fixture01OrdinaryOfferingHasOneTeacherAndPhysicalRooms() throws Exception {
        assertEquals(1,number("SELECT COUNT(*) FROM course_offering WHERE id=101 AND activity_type='UNDERGRAD_COURSE' AND student_count>0"));
        assertEquals(List.of(1),integers("SELECT DISTINCT st.teacher_id FROM schedule_teacher st JOIN class_schedule s ON s.id=st.schedule_id WHERE s.offering_id=101"));
        assertEquals(2,number("SELECT COUNT(*) FROM class_schedule s JOIN location l ON l.id=s.location_id WHERE s.offering_id=101 AND l.location_type='PHYSICAL'"));
    }

    @Test void fixture02FiveAnonymousTeachersHaveActualSchedules() throws Exception {
        assertEquals(List.of("Teacher Alpha","Teacher Beta","Teacher Gamma","Teacher Delta","Teacher Epsilon"),strings("SELECT display_name FROM teacher ORDER BY id"));
        assertEquals(5,number("SELECT COUNT(DISTINCT teacher_id) FROM schedule_teacher"));
    }

    @Test void fixture03CoTeachingIsTwoRelationshipsOnOneSchedule() throws Exception {
        assertEquals(List.of(1,2),integers("SELECT teacher_id FROM schedule_teacher WHERE schedule_id=1003 ORDER BY teacher_id"));
        for(int teacher:List.of(1,2)) assertEquals(1,number("SELECT COUNT(DISTINCT s.offering_id) FROM class_schedule s JOIN schedule_teacher t ON t.schedule_id=s.id WHERE s.offering_id=102 AND t.teacher_id=?",teacher));
    }

    @Test void fixture04ChangingTeachersStayOnTheirOwnSchedules() throws Exception {
        assertEquals(List.of(1004,1005),integers("SELECT id FROM class_schedule WHERE offering_id=103 ORDER BY id"));
        assertEquals(List.of(1),integers("SELECT teacher_id FROM schedule_teacher WHERE schedule_id=1004"));
        assertEquals(List.of(3),integers("SELECT teacher_id FROM schedule_teacher WHERE schedule_id=1005"));
        assertEquals(List.of(1,2,3,4,5,6,7,8),weeks(1004));
        assertEquals(List.of(10,11,12,13,14,15,16,17),weeks(1005));
        assertEquals(1,number("SELECT COUNT(DISTINCT s.offering_id) FROM class_schedule s JOIN schedule_teacher t ON t.schedule_id=s.id WHERE t.teacher_id IN (1,3) AND s.offering_id=103"));
    }

    @Test void ac03ImmutableScheduleDefinition() throws Exception {
        assertEquals(List.of(1006,1007),integers("SELECT id FROM class_schedule WHERE offering_id=104 ORDER BY id"));
        assertEquals(List.of(1),weeks(1006)); assertEquals(List.of(2),weeks(1007));
        assertEquals(2,number("SELECT COUNT(*) FROM class_schedule WHERE offering_id=104 AND weekday=1 AND start_section=1 AND end_section=2"));
        assertEquals(1,number("SELECT location_id FROM class_schedule WHERE id=1006"));
        assertEquals(2,number("SELECT location_id FROM class_schedule WHERE id=1007"));
    }

    @ParameterizedTest(name="AC-03 week {0}, location {1}: expected {2}")
    @CsvSource({"1,1,1","2,2,1","1,2,0","2,1,0"})
    void ac03WeekAndLocationMustMatchTheSameSchedule(int week,int location,int expected) throws Exception {
        assertEquals(expected,number("SELECT COUNT(*) FROM course_offering o WHERE o.id=104 AND EXISTS (SELECT 1 FROM class_schedule s JOIN schedule_week w ON w.schedule_id=s.id WHERE s.offering_id=o.id AND w.week_no=? AND s.location_id=? AND s.weekday=1 AND s.start_section=1 AND s.end_section=2)",week,location));
    }

    @Test void fixture05NoncontiguousWeeksDoNotInventWeekNine() throws Exception {
        assertEquals(List.of(1,2,3,4,5,6,7,8,10,11,12,13),weeks(1008));
        assertTrue(weeks(1008).containsAll(List.of(8,10))); assertFalse(weeks(1008).contains(9));
        assertEquals("1-8周,10-13周",string("SELECT raw_week_text FROM class_schedule WHERE id=1008"));
    }

    @Test void fixture06AlternateWeeksKeepAllThreeRepresentations() throws Exception {
        assertEquals(List.of(10,12,14),weeks(1009));
        assertEquals("000010101000000000",string("SELECT week_mask FROM class_schedule WHERE id=1009"));
        assertEquals("10-14周(双)",string("SELECT raw_week_text FROM class_schedule WHERE id=1009"));
        assertEquals("10-14周(双)",string("SELECT raw_rw FROM source_timetable_row WHERE id=1009"));
    }

    @Test void allMasksEqualTheirDiscreteWeeks() throws Exception {
        try(var st=db.createStatement();var rows=st.executeQuery("SELECT id,week_mask FROM class_schedule ORDER BY id")) {
            while(rows.next()) assertEquals(WeekMask.weeks(rows.getString(2)),weeks(rows.getInt(1)),"schedule "+rows.getInt(1));
        }
        assertEquals(List.of(1,2,3,4),weeks(1001));
        assertEquals(List.of(18),weeks(1002));
    }

    @Test void fixture07ZeroSurvivesBothSourceAndOfferingWithoutNullConversion() throws Exception {
        assertEquals(1,number("SELECT COUNT(*) FROM course_offering o JOIN source_timetable_row r ON r.offering_id=o.id WHERE o.id=106 AND o.student_count=0 AND r.raw_p=0"));
        var raw=JSON.readTree(string("SELECT raw_json FROM source_timetable_row WHERE id=1010"));
        assertTrue(raw.get("p").isIntegralNumber()); assertEquals(0,raw.get("p").asInt());
    }

    @ParameterizedTest @CsvSource({"3,线上,线上,ONLINE,1012","4,待定,其它,TBD,1013"})
    void fixture08And09LegalLocationStates(long location,String name,String group,String type,long schedule) throws Exception {
        assertEquals(1,number("SELECT COUNT(*) FROM location l JOIN class_schedule s ON s.location_id=l.id WHERE l.id=? AND l.display_name=? AND l.location_group=? AND l.location_type=? AND s.id=?",location,name,group,type,schedule));
        assertEquals(1,number("SELECT COUNT(*) FROM source_timetable_row WHERE schedule_id=? AND normalize_error IS NULL",schedule));
    }

    @Test void fixture10OneOfferingHasAllLocationTypes() throws Exception {
        assertEquals(List.of("ONLINE","PHYSICAL","TBD"),strings("SELECT l.location_type FROM class_schedule s JOIN location l ON l.id=s.location_id WHERE s.offering_id=107 ORDER BY l.location_type"));
    }

    @Test void fixture11MissingOfficialCourseCodesAndSyllabusStates() throws Exception {
        assertEquals(2,number("SELECT COUNT(*) FROM course WHERE course_code IS NULL"));
        assertEquals("fixture:syllabus-present",string("SELECT syllabus_ref FROM course WHERE id=201"));
        assertNull(string("SELECT syllabus_ref FROM course WHERE id=202"));
    }

    @Test void fixture12SourceKeysAreStableButNotOfficialOfferingCodes() throws Exception {
        assertEquals(10,number("SELECT COUNT(DISTINCT source_offering_key) FROM course_offering WHERE official_offering_code IS NULL"));
        assertEquals(SourceKeys.offering("PHASE1-ACCEPTANCE","2026-FALL","TEST-SOFTWARE","普通本科-测试","本","测试软件2401-03,测试软件2404"),string("SELECT source_offering_key FROM course_offering WHERE id=101"));
        assertEquals(16,number("SELECT COUNT(DISTINCT source_schedule_key) FROM class_schedule"));
    }

    @Test void fixture13NameOnlyDoesNotInventEmployeeNumbers() throws Exception {
        assertEquals(5,number("SELECT COUNT(*) FROM teacher WHERE employee_no IS NULL AND identity_status='NAME_ONLY'"));
    }

    @Test void practiceRemainsUnmappedAndClassTextIsNotExpanded() throws Exception {
        assertEquals(1,number("SELECT COUNT(*) FROM course_offering WHERE id=108 AND display_name='数据结构实验-测试活动' AND activity_type='PRACTICE' AND course_id IS NULL"));
        assertEquals("测试软件2401-03,测试软件2404",string("SELECT class_group_text FROM course_offering WHERE id=101"));
        assertEquals("测试软件2401-03,测试软件2404",string("SELECT raw_c FROM source_timetable_row WHERE id=1001"));
    }

    @Test void everySourceTracesToMatchingBatchOfferingAndSchedule() throws Exception {
        assertEquals(16,number("SELECT COUNT(*) FROM source_timetable_row r JOIN import_batch b ON b.id=r.import_batch_id JOIN course_offering o ON o.id=r.offering_id AND o.semester_id=b.semester_id AND o.source_scope_college_id=b.source_scope_college_id JOIN class_schedule s ON s.id=r.schedule_id AND s.offering_id=o.id WHERE r.normalize_error IS NULL"));
        assertEquals(List.of(14,1,1),integers("SELECT total_rows FROM import_batch ORDER BY id"));
        assertEquals(0,number("SELECT COUNT(*) FROM import_batch WHERE total_rows<>success_rows OR failure_rows<>0"));
    }

    @Test void sourceJsonContainsExactlyElevenFieldsAndMatchesRawColumns() throws Exception {
        var fields=List.of("n","t","c","p","w","rw","d","s","h","l","g");
        try(var st=db.createStatement();var rows=st.executeQuery("SELECT * FROM source_timetable_row ORDER BY id")) {
            while(rows.next()) {
                var raw=JSON.readTree(rows.getString("raw_json"));
                assertTrue(raw.isObject()); assertEquals(11,raw.size());
                for(String field:fields) assertEquals(rows.getString("raw_"+field),raw.get(field).asText(),field);
            }
        }
        assertEquals("Teacher Alpha,Teacher Beta",string("SELECT raw_h FROM source_timetable_row WHERE id=1003"));
    }

    @Test void allOfferingsAndSchedulesAreCompleteWithoutOrphans() throws Exception {
        assertEquals(0,number("SELECT COUNT(*) FROM course_offering o WHERE NOT EXISTS (SELECT 1 FROM class_schedule s WHERE s.offering_id=o.id)"));
        assertEquals(0,number("SELECT COUNT(*) FROM class_schedule s WHERE NOT EXISTS (SELECT 1 FROM schedule_teacher t WHERE t.schedule_id=s.id) OR NOT EXISTS (SELECT 1 FROM schedule_week w WHERE w.schedule_id=s.id)"));
        assertEquals(0,number("SELECT COUNT(*) FROM schedule_week w LEFT JOIN class_schedule s ON s.id=w.schedule_id WHERE s.id IS NULL"));
        assertEquals(0,number("SELECT COUNT(*) FROM schedule_teacher st LEFT JOIN class_schedule s ON s.id=st.schedule_id LEFT JOIN teacher t ON t.id=st.teacher_id WHERE s.id IS NULL OR t.id IS NULL"));
    }

    @Test void artificialScopesAndTermAreDistinguishable() throws Exception {
        assertEquals(1,number("SELECT source_scope_college_id FROM course_offering WHERE id=101"));
        assertEquals(2,number("SELECT source_scope_college_id FROM course_offering WHERE id=109"));
        assertEquals(2,number("SELECT semester_id FROM course_offering WHERE id=110"));
        assertEquals("2026-08-30",string("SELECT first_week_date FROM semester WHERE id=1"));
        assertNull(string("SELECT first_week_date FROM semester WHERE id=2"));
    }

    @Test void accountsAreDisabledAndRoleTargetsRemainSeparate() throws Exception {
        assertEquals(5,number("SELECT COUNT(*) FROM user_account WHERE enabled=FALSE AND password_hash='TEST_ONLY_DISABLED_NOT_A_LOGIN_HASH'"));
        assertEquals(2,number("SELECT COUNT(*) FROM user_role_scope WHERE scope_type='GLOBAL'"));
        assertEquals(List.of("OFFERING","SELF_TEACHER"),strings("SELECT scope_type FROM user_role_scope WHERE user_id=3 ORDER BY scope_type"));
        assertEquals(104,number("SELECT offering_id FROM user_role_scope WHERE user_id=3 AND scope_type='OFFERING'"));
        assertEquals(0,number("SELECT COUNT(*) FROM class_schedule s JOIN schedule_teacher t ON t.schedule_id=s.id WHERE s.offering_id=109 AND t.teacher_id=1"));
    }

    @Test void allFixtureTimestampsAreFixed() throws Exception {
        for(String table:R__Phase1_acceptance_fixture.TABLES) {
            try(var st=db.createStatement();var rows=st.executeQuery("SELECT * FROM "+table)) {
                var meta=rows.getMetaData();
                while(rows.next()) for(int col=1;col<=meta.getColumnCount();col++) {
                    String name=meta.getColumnName(col).toLowerCase(Locale.ROOT);
                    // completed_at is nullable by contract (a non-completed evaluation has no completion time).
                    if(name.endsWith("_at") && rows.getTimestamp(col)!=null)
                        assertEquals(Timestamp.valueOf("2026-08-30 00:00:00"),rows.getTimestamp(col),table+"."+name);
                }
            }
        }
    }

    private void assertEmpty() throws Exception {
        for(String table:R__Phase1_acceptance_fixture.TABLES) assertEquals(0,number("SELECT COUNT(*) FROM "+table),table);
    }
    private Map<String,List<List<String>>> snapshot() throws Exception {
        Map<String,List<List<String>>> result=new LinkedHashMap<>();
        for(String table:R__Phase1_acceptance_fixture.TABLES) {
            var content=new ArrayList<List<String>>();
            try(var st=db.createStatement();var rows=st.executeQuery("SELECT * FROM "+table+" ORDER BY 1,2")) {
                while(rows.next()) {
                    var values=new ArrayList<String>();
                    for(int i=1;i<=rows.getMetaData().getColumnCount();i++) values.add(rows.getString(i));
                    content.add(values);
                }
            }
            result.put(table,content);
        }
        return result;
    }
    private PreparedStatement statement(String sql,Object... args) throws SQLException {
        var st=db.prepareStatement(sql);
        for(int i=0;i<args.length;i++) st.setObject(i+1,args[i]);
        return st;
    }
    private void execute(String sql) throws SQLException { try(var st=db.createStatement()) { st.executeUpdate(sql); } }
    private long number(String sql,Object... args) throws SQLException { try(var st=statement(sql,args);var rows=st.executeQuery()) { assertTrue(rows.next()); return rows.getLong(1); } }
    private String string(String sql) throws SQLException { try(var st=db.createStatement();var rows=st.executeQuery(sql)) { assertTrue(rows.next()); return rows.getString(1); } }
    private List<String> strings(String sql) throws SQLException {
        var result=new ArrayList<String>();
        try(var st=db.createStatement();var rows=st.executeQuery(sql)) { while(rows.next()) result.add(rows.getString(1)); }
        return result;
    }
    private List<Integer> integers(String sql) throws SQLException { return strings(sql).stream().map(Integer::valueOf).toList(); }
    private List<Integer> weeks(int schedule) throws SQLException { return integers("SELECT week_no FROM schedule_week WHERE schedule_id="+schedule+" ORDER BY week_no"); }
}
