package com.aijiaoxue;

import com.aijiaoxue.course.WeekMask;
import com.aijiaoxue.importing.SourceKeys;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Same constraint suite runs on H2 and, explicitly, a fresh isolated MySQL schema. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataModelTest {
    private Connection db;
    private Flyway flyway;
    private boolean mysql;
    private int migrationsApplied;
    private long college, semester, offering, place, teacherA, teacherB, first, second;
    private String offeringKey;
    private static final String MASK = "000000000000001111";
    private static final String RAW = "{\"n\":\"数据结构与算法-Array\",\"t\":\"实\",\"c\":\"软件2401-09\",\"p\":0,\"w\":\"000000000000001111\",\"rw\":\"1-4周\",\"d\":1,\"s\":\"9-12\",\"h\":\"教师甲,教师乙\",\"l\":\"线上\",\"g\":\"线上\"}";

    @BeforeAll void migrateFreshDatabase() throws Exception {
        mysql = Boolean.getBoolean("mysqlMigration");
        String url = mysql ? System.getenv("MIGRATION_TEST_URL")
                : "jdbc:h2:mem:data_model_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        if (mysql) {
            assertNotNull(url, "Use scripts/Test-MigrationDatabase.ps1 for MySQL tests");
            assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/ai_teaching_migration_[a-f0-9]{32}"
                    + "\\?sslMode=DISABLED&allowPublicKeyRetrieval=true"), "Dedicated loopback schema required");
        }
        String user = mysql ? Objects.requireNonNull(System.getenv("MIGRATION_TEST_USERNAME")) : "sa";
        String password = mysql ? Objects.requireNonNull(System.getenv("MIGRATION_TEST_PASSWORD")) : "";
        db = DriverManager.getConnection(url, user, password);
        try (var tables = db.getMetaData().getTables(db.getCatalog(), mysql ? null : "PUBLIC", "%", new String[]{"TABLE"})) {
            assertFalse(tables.next(), "Data model suite requires a fresh schema; never drops existing data");
        }
        flyway = Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration")
                .cleanDisabled(true).baselineOnMigrate(false).load();
        migrationsApplied = flyway.migrate().migrationsExecuted;
        db.setAutoCommit(false);
    }

    @BeforeEach void fixture() throws Exception {
        college = insert("INSERT INTO college(college_code,college_name) VALUES (?,?)", "TEST-SCOPE", "测试源范围");
        semester = insert("INSERT INTO semester(semester_code,semester_name,teaching_week_count) VALUES (?,?,?)", "2026-FALL", "2026年秋季学期", 18);
        offeringKey = SourceKeys.offering("TEST", "2026-FALL", "TEST-SCOPE", "数据结构与算法-Array", "实", "软件2401-09");
        offering = insert("INSERT INTO course_offering(semester_id,source_scope_college_id,source_offering_key,display_name,activity_type,class_group_text,status) VALUES (?,?,?,?,?,?,?)",
                semester,college,offeringKey,"数据结构与算法-Array","PRACTICE","软件2401-09","SOURCE_ONLY");
        place = insert("INSERT INTO location(display_name,normalized_name,location_group,location_type) VALUES (?,?,?,?)", "线上","线上","线上","ONLINE");
        teacherA = teacher("教师甲");
        teacherB = teacher("教师乙");
        first = schedule(1,9,12,MASK,place);
        second = schedule(3,3,4,"000001111011111111",place);
    }

    @AfterEach void rollback() throws Exception { db.rollback(); }
    @AfterAll void close() throws Exception { if (db != null) db.close(); }

    @Test void dm01_multipleTeachersOnOneSchedule() throws Exception {
        exec("INSERT INTO schedule_teacher VALUES (?,?)",first,teacherA);
        exec("INSERT INTO schedule_teacher VALUES (?,?)",first,teacherB);
        assertEquals(2, number("SELECT COUNT(*) FROM schedule_teacher WHERE schedule_id=?",first));
        assertThrows(SQLException.class, () -> exec("INSERT INTO schedule_teacher VALUES (?,?)",first,teacherA));
    }
    @Test void dm02_eachScheduleKeepsItsActualTeacher() throws Exception {
        exec("INSERT INTO schedule_teacher VALUES (?,?)",first,teacherA);
        exec("INSERT INTO schedule_teacher VALUES (?,?)",second,teacherB);
        assertEquals(teacherA, number("SELECT teacher_id FROM schedule_teacher WHERE schedule_id=?",first));
        assertEquals(teacherB, number("SELECT teacher_id FROM schedule_teacher WHERE schedule_id=?",second));
        assertEquals(2, number("SELECT COUNT(*) FROM class_schedule WHERE offering_id=?",offering));
    }
    @Test void dm03_noncontiguousWeeksDoNotInventWeekNine() throws Exception {
        addWeeks(second,"000001111011111111");
        assertEquals(12,number("SELECT COUNT(*) FROM schedule_week WHERE schedule_id=?",second));
        assertEquals(0,number("SELECT COUNT(*) FROM schedule_week WHERE schedule_id=? AND week_no=9",second));
        assertEquals(1,number("SELECT COUNT(*) FROM schedule_week WHERE schedule_id=? AND week_no=13",second));
    }
    @Test void dm04_alternateWeeksAreDiscrete() throws Exception {
        exec("UPDATE class_schedule SET week_mask=? WHERE id=?","000010101000000000",first);
        addWeeks(first,"000010101000000000");
        assertEquals(List.of(10,12,14), integers("SELECT week_no FROM schedule_week WHERE schedule_id=? ORDER BY week_no",first));
    }
    @Test void dm05_timeAndPlaceMustMatchOneSchedule() throws Exception {
        long other = insert("INSERT INTO location(display_name,normalized_name,location_group,location_type) VALUES (?,?,?,?)","待定","待定","其它","TBD");
        exec("UPDATE class_schedule SET location_id=? WHERE id=?",other,second);
        String sql = "SELECT COUNT(*) FROM course_offering o WHERE o.id=? AND EXISTS (SELECT 1 FROM class_schedule s WHERE s.offering_id=o.id AND s.weekday=? AND s.start_section=? AND s.end_section=? AND s.location_id=?)";
        assertEquals(1,number(sql,offering,1,9,12,place));
        assertEquals(0,number(sql,offering,1,9,12,other));
    }
    @Test void dm06_practiceCanRemainUnmapped() throws Exception {
        assertEquals(1,number("SELECT COUNT(*) FROM course_offering WHERE id=? AND course_id IS NULL AND official_offering_code IS NULL AND activity_type='PRACTICE' AND student_count IS NULL",offering));
        assertEquals("软件2401-09",string("SELECT class_group_text FROM course_offering WHERE id=?",offering));
    }
    @Test void dm07_missingOfficialCodesAndNonuniqueNamesAreLegal() throws Exception {
        long course = insert("INSERT INTO course(course_name,verification_status) VALUES (?,?)","数据结构与算法","SOURCE_ONLY");
        assertEquals(1,number("SELECT COUNT(*) FROM course WHERE id=? AND course_code IS NULL AND owner_college_id IS NULL",course));
        teacher("教师甲");
        assertEquals(2,number("SELECT COUNT(*) FROM teacher WHERE display_name=? AND employee_no IS NULL AND identity_status='NAME_ONLY'","教师甲"));
        assertEquals(1,number("SELECT COUNT(*) FROM semester WHERE id=? AND start_date IS NULL AND end_date IS NULL AND first_week_date IS NULL",semester));
    }
    @Test void dm08_zeroIsPreservedInSourceOnly() throws Exception {
        long row = source(batch(),1);
        assertEquals(0,number("SELECT raw_p FROM source_timetable_row WHERE id=?",row));
        assertEquals("教师甲,教师乙",string("SELECT raw_h FROM source_timetable_row WHERE id=?",row));
        assertEquals("9-12",string("SELECT raw_s FROM source_timetable_row WHERE id=?",row));
        assertEquals(1,number("SELECT COUNT(*) FROM course_offering WHERE id=? AND student_count IS NULL",offering));
        String json = string("SELECT raw_json FROM source_timetable_row WHERE id=?",row);
        assertTrue(json.contains("数据结构与算法-Array"));
        assertTrue(json.replace(" ","").contains("\"p\":0"));
    }
    @Test void dm09_duplicateSourcesAndNormalizedLinksRemainTraceable() throws Exception {
        long batch = batch();
        long row = source(batch,1);
        source(batch,2); // Same hash and raw values, different source index: both must survive.
        assertEquals(2,number("SELECT COUNT(*) FROM source_timetable_row WHERE import_batch_id=?",batch));
        assertThrows(SQLException.class, () -> source(batch,1));
        exec("UPDATE source_timetable_row SET offering_id=?,schedule_id=? WHERE id=?",offering,first,row);
        assertEquals(1,number("SELECT COUNT(*) FROM source_timetable_row r JOIN import_batch b ON r.import_batch_id=b.id JOIN class_schedule s ON r.schedule_id=s.id AND r.offering_id=s.offering_id WHERE r.id=?",row));
    }
    @Test void dm10_migrationAndRepeatAreSuccessful() throws Exception {
        assertEquals(24,migrationsApplied);
        assertEquals("24",flyway.info().current().getVersion().toString());
        flyway.validate();
        assertEquals(0,flyway.migrate().migrationsExecuted);
        assertEquals(2,number("SELECT COUNT(*) FROM class_schedule WHERE offering_id=?",offering));
        assertThrows(org.flywaydb.core.api.FlywayException.class,flyway::clean);
    }
    @Test void longModuleEnvelopeFitsTheDeclaredResponseLimit() throws Exception {
        long user=insert("INSERT INTO user_account(username,password_hash,enabled) VALUES (?,?,?)",
                "envelope-test-user","not-a-login-password",true);
        long session=insert("INSERT INTO class_session(offering_id,schedule_id,session_date,title,created_by) VALUES (?,?,?,?,?)",
                offering,first,java.sql.Date.valueOf("2026-09-24"),"Long envelope fixture",user);
        long media=insert("INSERT INTO classroom_media(class_session_id,storage_key,original_filename,content_type,byte_size,sha256,duration_ms,status) VALUES (?,?,?,?,?,?,?,?)",
                session,"long-envelope-fixture","fixture.mp4","video/mp4",500_000L,"a".repeat(64),20_000L,"READY");
        long job=insert("INSERT INTO classroom_module_job(media_id,module_type,status,provider_id) VALUES (?,?,?,?)",
                media,"TRANSCRIPTION","RUNNING","test-provider");
        String envelope="{\"payload\":\""+"x".repeat(500_000)+"\"}";
        long result=insert("INSERT INTO classroom_module_result(job_id,revision,status,payload_sha256,envelope_json) VALUES (?,?,?,?,?)",
                job,1,"SUCCEEDED","b".repeat(64),envelope);
        assertEquals(envelope,envelopeValue(result));
    }

    private String envelopeValue(long resultId) throws SQLException {
        try(var statement=db.prepareStatement("SELECT envelope_json FROM classroom_module_result WHERE id=?")) {
            statement.setLong(1,resultId);
            try(var rows=statement.executeQuery()) { assertTrue(rows.next()); return rows.getString(1); }
        }
    }
    @Test void dm11_teacherChangePreservesScheduleKey() throws Exception {
        exec("INSERT INTO schedule_teacher VALUES (?,?)",first,teacherA);
        String before = string("SELECT source_schedule_key FROM class_schedule WHERE id=?",first);
        exec("DELETE FROM schedule_teacher WHERE schedule_id=?",first);
        exec("INSERT INTO schedule_teacher VALUES (?,?)",first,teacherB);
        assertEquals(before,SourceKeys.schedule(offeringKey,MASK,1,9,12,"线上"));
        assertEquals(before,string("SELECT source_schedule_key FROM class_schedule WHERE id=?",first));
        assertThrows(SQLException.class, () -> schedule(1,9,12,MASK,place));
    }
    @Test void dm12_invalidDomainValuesFail() throws Exception {
        for (int day : new int[]{0,8}) assertThrows(SQLException.class, () -> exec("UPDATE class_schedule SET weekday=? WHERE id=?",day,first));
        assertThrows(SQLException.class, () -> exec("UPDATE class_schedule SET start_section=0 WHERE id=?",first));
        assertThrows(SQLException.class, () -> exec("UPDATE class_schedule SET end_section=1 WHERE id=?",first));
        for (int week : new int[]{0,19}) assertThrows(SQLException.class, () -> exec("INSERT INTO schedule_week VALUES (?,?)",first,week));
        for (String mask : List.of("101","00000000000000111x","0000000000000011111")) {
            assertThrows(SQLException.class, () -> exec("UPDATE class_schedule SET week_mask=? WHERE id=?",mask,first));
        }
        // Valid one-section arrangements above ten are accepted.
        exec("UPDATE class_schedule SET start_section=12,end_section=12 WHERE id=?",first);
    }
    @Test void foreignKeysAndScopeReferencesAreEnforced() throws Exception {
        assertThrows(SQLException.class, () -> exec("INSERT INTO schedule_teacher VALUES (?,?)",first,-1));
        assertThrows(SQLException.class, () -> exec("INSERT INTO schedule_week VALUES (?,?)",-1,1));
        assertThrows(SQLException.class, () -> exec("UPDATE class_schedule SET location_id=-1 WHERE id=?",first));
        assertThrows(SQLException.class, () -> exec("UPDATE course_offering SET course_id=-1 WHERE id=?",offering));
        assertThrows(SQLException.class, () -> exec("DELETE FROM course_offering WHERE id=?",offering));
        long user = insert("INSERT INTO user_account(username,password_hash,teacher_id,enabled) VALUES (?,?,?,?)","test-user","non-login-test-value",teacherA,true);
        long role = insert("INSERT INTO role(role_code,role_name) VALUES (?,?)","SUPERVISOR","测试角色");
        exec("INSERT INTO user_role_scope(user_id,role_id,scope_type,offering_id) VALUES (?,?,?,?)",user,role,"OFFERING",offering);
        assertThrows(SQLException.class, () -> exec("INSERT INTO user_role_scope(user_id,role_id,scope_type) VALUES (?,?,?)",user,role,"OFFERING"));
    }

    @Test void requiredTablesAndIndexesExistWithoutUnapprovedUniqueness() throws Exception {
        Set<String> tables = new HashSet<>();
        try (var rs=db.getMetaData().getTables(db.getCatalog(),mysql ? null : "PUBLIC","%",new String[]{"TABLE"})) {
            while(rs.next()) tables.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
        }
        assertEquals(Set.of("college","semester","course","course_offering","location","teacher",
                "class_schedule","schedule_week","schedule_teacher","user_account","role","user_role_scope",
                "import_batch","source_timetable_row","teaching_resource","supervision_evaluation",
                "class_session","classroom_media","classroom_module_job","classroom_transcript_segment",
                "classroom_head_up_window","classroom_analysis_report","classroom_module_result",
                "classroom_processed_range","classroom_analysis_run",
                "classroom_paid_test_budget","classroom_paid_test_reservation","classroom_generated_report",
                "classroom_report_dispatch","classroom_report_retry_request","classroom_module_retry_request",
                "username_only_login_audit",
                "teacher_action",
                "classroom_report_review","classroom_report_review_annotation",
                "classroom_report_notification",
                "classroom_segment_override",
                "flyway_schema_history"),tables);
        assertIndex("course_offering","uq_offering_source_key",false,"source_offering_key");
        assertIndex("class_schedule","uq_schedule_source_key",false,"source_schedule_key");
        assertIndex("schedule_week","idx_schedule_week_lookup",true,"week_no","schedule_id");
        assertIndex("schedule_teacher","idx_teacher_schedule",true,"teacher_id","schedule_id");
        assertIndex("source_timetable_row","idx_source_hash",true,"row_hash");
        assertIndex("source_timetable_row","uq_source_batch_index",false,"import_batch_id","source_index");
        assertIndex("course","idx_course_code",true,"course_code");
        assertIndex("teacher","idx_teacher_employee_no",true,"employee_no");
        assertIndex("class_session","uq_class_session_upload_request",false,
                "created_by","upload_idempotency_key_sha256");
        assertIndex("classroom_analysis_run","uq_analysis_run_request",false,
                "objective_report_id","request_key_sha256");
        assertIndex("classroom_paid_test_reservation","uq_classroom_paid_test_request",false,
                "request_sha256");
        assertIndex("classroom_paid_test_reservation","idx_classroom_paid_test_subject",true,
                "category","subject_id","id");
        assertIndex("classroom_generated_report","uq_generated_report_run",false,"run_id");
        assertIndex("classroom_generated_report","uq_generated_report_reservation",false,"reservation_id");
        assertIndex("classroom_report_dispatch","uq_report_dispatch_objective",false,"objective_report_id");
        assertIndex("classroom_report_dispatch","idx_report_dispatch_due",true,"state","lease_until","id");
        assertIndex("classroom_report_dispatch","idx_report_dispatch_retry",true,"state","next_attempt_at","id");
        assertIndex("classroom_report_retry_request","uq_report_retry_generation",false,"dispatch_id","generation");
        assertIndex("classroom_report_retry_request","uq_report_retry_key",false,"dispatch_id","request_key_sha256");
        assertIndex("classroom_module_job","uq_classroom_job_media_type_attempt",false,
                "media_id","module_type","attempt");
        assertIndex("classroom_module_retry_request","uq_module_retry_generation",false,"report_id","generation");
        assertIndex("classroom_module_retry_request","uq_module_retry_key",false,"report_id","request_key_sha256");
        // S2-10 覆盖层：同一报告版本内一个片段只能有一行覆盖，否则"当前边界"就有两个答案。
        assertIndex("classroom_segment_override","uq_segment_override_target",false,"report_id","segment_key");
        assertIndex("username_only_login_audit","ix_username_only_login_audit_created",true,
                "created_at","user_account_id");
        assertThrows(SQLException.class, () -> insert("INSERT INTO location(display_name,normalized_name,location_group,location_type) VALUES (?,?,?,?)","线上","线上","线上","ONLINE"));
        // Trim-only normalization must not silently fold case through MySQL's default ai_ci collation.
        insert("INSERT INTO location(display_name,normalized_name,location_group,location_type) VALUES (?,?,?,?)","LabA","LabA","Test","PHYSICAL");
        insert("INSERT INTO location(display_name,normalized_name,location_group,location_type) VALUES (?,?,?,?)","laba","laba","Test","PHYSICAL");
    }

    private void assertIndex(String table,String name,boolean nonUnique,String... expected) throws SQLException {
        SortedMap<Integer,String> columns=new TreeMap<>();
        try(var rs=db.getMetaData().getIndexInfo(db.getCatalog(),mysql ? null : "PUBLIC",mysql ? table : table.toUpperCase(Locale.ROOT),false,false)) {
            while(rs.next()) if(name.equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
                assertEquals(nonUnique,rs.getBoolean("NON_UNIQUE"));
                columns.put(rs.getInt("ORDINAL_POSITION"),rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        }
        assertEquals(List.of(expected),new ArrayList<>(columns.values()),name);
    }

    private long teacher(String name) throws Exception {
        return insert("INSERT INTO teacher(display_name,normalized_name,identity_status) VALUES (?,?,?)",name,name,"NAME_ONLY");
    }
    private long schedule(int day,int start,int end,String mask,long location) throws Exception {
        String key = SourceKeys.schedule(offeringKey,mask,day,start,end,location == place ? "线上" : "待定");
        return insert("INSERT INTO class_schedule(offering_id,weekday,start_section,end_section,location_id,week_mask,raw_week_text,source_schedule_key) VALUES (?,?,?,?,?,?,?,?)",
                offering,day,start,end,location,mask,"测试周次",key);
    }
    private void addWeeks(long schedule,String mask) throws Exception {
        for (int week : WeekMask.weeks(mask)) exec("INSERT INTO schedule_week VALUES (?,?)",schedule,week);
    }
    private long batch() throws Exception {
        return insert("INSERT INTO import_batch(source_type,source_namespace,source_title,source_scope_college_id,semester_id,source_file_name,source_sha256,total_rows,success_rows,failure_rows,status,started_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                "HTML_TIMETABLE","TEST","测试",college,semester,"fixture.html","a".repeat(64),2,0,0,"PENDING");
    }
    private long source(long batch,int index) throws Exception {
        return insert("INSERT INTO source_timetable_row(import_batch_id,source_index,raw_n,raw_t,raw_c,raw_p,raw_w,raw_rw,raw_d,raw_s,raw_h,raw_l,raw_g,raw_json,row_hash,normalize_status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?"
                + (mysql ? "" : " FORMAT JSON") + ",?,?)",batch,index,"数据结构与算法-Array","实","软件2401-09",0,MASK,"1-4周",1,"9-12","教师甲,教师乙","线上","线上",RAW,"b".repeat(64),"PENDING");
    }
    private PreparedStatement statement(String sql,Object... args) throws SQLException {
        var statement = db.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);
        for (int i=0;i<args.length;i++) statement.setObject(i+1,args[i]);
        return statement;
    }
    private long insert(String sql,Object... args) throws SQLException {
        try (var st = statement(sql,args)) { st.executeUpdate(); try (var keys=st.getGeneratedKeys()) { assertTrue(keys.next()); return keys.getLong(1); } }
    }
    private void exec(String sql,Object... args) throws SQLException { try (var st=statement(sql,args)) { st.executeUpdate(); } }
    private long number(String sql,Object... args) throws SQLException { try (var st=statement(sql,args); var rs=st.executeQuery()) { assertTrue(rs.next()); return rs.getLong(1); } }
    private String string(String sql,Object... args) throws SQLException { try (var st=statement(sql,args); var rs=st.executeQuery()) { assertTrue(rs.next()); return rs.getString(1); } }
    private List<Integer> integers(String sql,Object... args) throws SQLException {
        List<Integer> result = new ArrayList<>();
        try (var st=statement(sql,args); var rs=st.executeQuery()) { while(rs.next()) result.add(rs.getInt(1)); }
        return result;
    }
}
