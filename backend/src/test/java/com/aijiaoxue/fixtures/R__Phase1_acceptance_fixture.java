package com.aijiaoxue.fixtures;

import com.aijiaoxue.course.WeekMask;
import com.aijiaoxue.importing.SourceKeys;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.zip.CRC32;

/** Explicitly registered test migration; never scanned by the production migration location. */
public final class R__Phase1_acceptance_fixture extends BaseJavaMigration {
    public static final String RESOURCE = "/fixtures/phase1.json";
    public static final List<String> TABLES = List.of("college", "semester", "teacher", "location", "course",
            "course_offering", "class_schedule", "schedule_week", "schedule_teacher", "role",
            "user_account", "user_role_scope", "import_batch", "source_timetable_row", "teaching_resource",
            "supervision_evaluation");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    // Bump when the fixture projection changes without changing phase1.json.
    private static final int PROJECTION_VERSION = 4;
    private final boolean populated;
    private final boolean authenticationEnabled;
    private final byte[] bytes;
    private final JsonNode world;

    public R__Phase1_acceptance_fixture(boolean populated) throws Exception {
        this(populated, false);
    }

    public R__Phase1_acceptance_fixture(boolean populated, boolean authenticationEnabled) throws Exception {
        this.populated = populated;
        this.authenticationEnabled = authenticationEnabled;
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream(RESOURCE))) {
            bytes = input.readAllBytes();
        }
        world = JSON.readTree(bytes);
    }

    @Override public Integer getChecksum() {
        var crc = new CRC32();
        crc.update(bytes);
        crc.update(PROJECTION_VERSION);
        crc.update(populated ? 1 : 0);
        crc.update(authenticationEnabled ? 1 : 0);
        return (int) crc.getValue();
    }

    // Own one DML transaction on both engines (MySQL does not transact DDL).
    @Override public boolean canExecuteInTransaction() { return false; }

    @Override public void migrate(Context context) throws Exception {
        Connection db = context.getConnection();
        String url = db.getMetaData().getURL();
        if (!url.startsWith("jdbc:h2:mem:phase1-fixture-") && !url.matches(
                "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/ai_teaching_migration_[a-f0-9]{32}\\?sslMode=DISABLED&allowPublicKeyRetrieval=true")) {
            throw new SQLException("Fixture migration requires an isolated acceptance test database");
        }
        boolean autoCommit = db.getAutoCommit();
        if (autoCommit) db.setAutoCommit(false);
        Savepoint boundary = db.setSavepoint();
        try {
            clear(db);
            if (populated) populate(db);
            if (autoCommit) db.commit();
        } catch (Exception failure) {
            db.rollback(boundary);
            throw failure;
        } finally {
            if (autoCommit) db.setAutoCommit(true);
        }
    }

    private void clear(Connection db) throws SQLException {
        // Exact fixture IDs only; foreign keys prevent deleting fixtures referenced by other data.
        delete(db, "source_timetable_row", "id", world.get("schedules"));
        delete(db, "import_batch", "id", world.get("batches"));
        delete(db, "user_role_scope", "id", world.get("scopes"));
        delete(db, "user_account", "id", world.get("accounts"));
        delete(db, "role", "id", world.get("roles"));
        delete(db, "schedule_teacher", "schedule_id", world.get("schedules"));
        delete(db, "schedule_week", "schedule_id", world.get("schedules"));
        delete(db, "class_schedule", "id", world.get("schedules"));
        try (var st = db.prepareStatement("DELETE FROM teaching_resource")) { st.executeUpdate(); }
        try (var st = db.prepareStatement("DELETE FROM supervision_evaluation")) { st.executeUpdate(); }
        delete(db, "course_offering", "id", world.get("offerings"));
        delete(db, "course", "id", world.get("courses"));
        delete(db, "teacher", "id", world.get("teachers"));
        delete(db, "location", "id", world.get("locations"));
        delete(db, "semester", "id", world.get("semesters"));
        delete(db, "college", "id", world.get("colleges"));
    }

    private void populate(Connection db) throws Exception {
        String time = world.get("timestamp").asText();
        String namespace = world.get("namespace").asText();
        for (var n : world.get("colleges")) insert(db, "college", "id,college_code,college_name,created_at,updated_at",
                v(n,"id"),v(n,"code"),v(n,"name"),time,time);
        for (var n : world.get("semesters")) insert(db, "semester", "id,semester_code,semester_name,teaching_week_count,first_week_date,created_at,updated_at",
                v(n,"id"),v(n,"code"),v(n,"name"),18,v(n,"firstWeek"),time,time);
        for (var n : world.get("teachers")) insert(db, "teacher", "id,display_name,normalized_name,identity_status,created_at,updated_at",
                v(n,"id"),v(n,"name"),v(n,"name"),"NAME_ONLY",time,time);
        for (var n : world.get("locations")) insert(db, "location", "id,display_name,normalized_name,location_group,location_type,created_at,updated_at",
                v(n,"id"),v(n,"name"),v(n,"name"),v(n,"group"),v(n,"type"),time,time);
        for (var n : world.get("courses")) insert(db, "course", "id,course_name,owner_college_id,syllabus_ref,verification_status,description,created_at,updated_at",
                v(n,"id"),v(n,"name"),v(n,"college"),v(n,"syllabus"),"SOURCE_ONLY",v(n,"description"),time,time);
        Map<Long,String> offeringKeys = new HashMap<>();
        for (var n : world.get("offerings")) {
            String key = SourceKeys.offering(namespace, find("semesters",n.get("semester").asLong()).get("code").asText(),
                    find("colleges",n.get("college").asLong()).get("code").asText(), n.get("name").asText(), rawType(n), n.get("classes").asText());
            offeringKeys.put(n.get("id").asLong(),key);
            insert(db, "course_offering", "id,course_id,semester_id,source_scope_college_id,source_offering_key,display_name,activity_type,class_group_text,student_count,status,created_at,updated_at",
                    v(n,"id"),v(n,"course"),v(n,"semester"),v(n,"college"),key,v(n,"name"),v(n,"type"),v(n,"classes"),v(n,"students"),"SOURCE_ONLY",time,time);
        }
        for (var n : world.get("evaluations")) insert(db, "supervision_evaluation",
                "id,course_offering_id,status,completed_at,created_at,updated_at",
                v(n,"id"),v(n,"offering"),v(n,"status"),v(n,"completedAt"),time,time);
        for (var n : world.get("schedules")) {
            String mask = n.get("mask").asText();
            String key = SourceKeys.schedule(offeringKeys.get(n.get("offering").asLong()),mask,n.get("day").asInt(),
                    n.get("start").asInt(),n.get("end").asInt(),find("locations",n.get("location").asLong()).get("name").asText());
            insert(db, "class_schedule", "id,offering_id,weekday,start_section,end_section,location_id,week_mask,raw_week_text,source_schedule_key,created_at,updated_at",
                    v(n,"id"),v(n,"offering"),v(n,"day"),v(n,"start"),v(n,"end"),v(n,"location"),mask,v(n,"weeks"),key,time,time);
            for (int week : WeekMask.weeks(mask)) insert(db,"schedule_week","schedule_id,week_no",v(n,"id"),week);
            for (var teacher : n.get("teachers")) insert(db,"schedule_teacher","schedule_id,teacher_id",v(n,"id"),teacher.asLong());
        }
        for (var n : world.get("roles")) insert(db,"role","id,role_code,role_name",v(n,"id"),v(n,"code"),v(n,"name"));
        for (var n : world.get("accounts")) insert(db,"user_account","id,username,password_hash,teacher_id,enabled,created_at,updated_at",
                v(n,"id"),v(n,"username"),authenticationEnabled ? world.get("authentication").get("testPasswordHash").asText()
                        : "TEST_ONLY_DISABLED_NOT_A_LOGIN_HASH",v(n,"teacher"),authenticationEnabled,time,time);
        for (var n : world.get("scopes")) insert(db,"user_role_scope","id,user_id,role_id,scope_type,teacher_id,offering_id,created_at",
                v(n,"id"),v(n,"user"),v(n,"role"),v(n,"type"),v(n,"teacher"),v(n,"offering"),time);
        for (var batch : world.get("batches")) {
            List<JsonNode> schedules = new ArrayList<>();
            List<Map<String,Object>> rawRows = new ArrayList<>();
            for (var s : world.get("schedules")) if (s.get("batch").asLong() == batch.get("id").asLong()) {
                schedules.add(s);
                rawRows.add(raw(s));
            }
            // Hashes describe synthetic JSON bytes, never pretend to hash the remote HTML.
            insert(db,"import_batch","id,source_type,source_namespace,source_title,source_scope_college_id,semester_id,source_file_name,source_sha256,total_rows,success_rows,failure_rows,status,operator_user_id,started_at,finished_at,created_at",
                    v(batch,"id"),"TEST_FIXTURE",namespace,v(batch,"title"),v(batch,"college"),v(batch,"semester"),
                    "phase1.json#batch-"+v(batch,"id"),sha(JSON.writeValueAsString(rawRows)),schedules.size(),schedules.size(),0,"FIXTURE_READY",1,time,time,time);
            for (int i=0;i<schedules.size();i++) {
                var s=schedules.get(i);
                var r=rawRows.get(i);
                String json=JSON.writeValueAsString(r);
                insert(db,"source_timetable_row","id,import_batch_id,source_index,raw_n,raw_t,raw_c,raw_p,raw_w,raw_rw,raw_d,raw_s,raw_h,raw_l,raw_g,raw_json,row_hash,normalize_status,offering_id,schedule_id,created_at",
                        v(s,"id"),v(batch,"id"),i+1,r.get("n"),r.get("t"),r.get("c"),r.get("p"),r.get("w"),r.get("rw"),r.get("d"),r.get("s"),r.get("h"),r.get("l"),r.get("g"),json,sha(json),"FIXTURE_LINKED",v(s,"offering"),v(s,"id"),time);
            }
        }
    }

    private Map<String,Object> raw(JsonNode schedule) {
        var o=find("offerings",schedule.get("offering").asLong());
        var l=find("locations",schedule.get("location").asLong());
        var teachers=new StringJoiner(",");
        for(var id:schedule.get("teachers")) teachers.add(find("teachers",id.asLong()).get("name").asText());
        Map<String,Object> r=new LinkedHashMap<>();
        r.put("n",v(o,"name")); r.put("t",rawType(o)); r.put("c",v(o,"classes")); r.put("p",v(o,"students"));
        r.put("w",v(schedule,"mask")); r.put("rw",v(schedule,"weeks")); r.put("d",v(schedule,"day"));
        r.put("s",v(schedule,"start")+"-"+v(schedule,"end")); r.put("h",teachers.toString());
        r.put("l",v(l,"name")); r.put("g",v(l,"group"));
        return r;
    }
    private static String rawType(JsonNode o) { return "PRACTICE".equals(o.get("type").asText()) ? "实" : "本"; }
    private JsonNode find(String collection,long id) {
        for(var n:world.get(collection)) if(n.get("id").asLong()==id) return n;
        throw new IllegalArgumentException("Unknown fixture reference: "+collection+"/"+id);
    }
    private static Object v(JsonNode node,String field) {
        var value=node.get(field);
        return value==null || value.isNull() ? null
                : value.isIntegralNumber() ? value.asLong() : value.asText();
    }
    private static String sha(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
    private static void delete(Connection db,String table,String column,JsonNode rows) throws SQLException {
        for(var row:rows) try(var st=db.prepareStatement("DELETE FROM "+table+" WHERE "+column+"=?")) {
            st.setLong(1,row.get("id").asLong()); st.executeUpdate();
        }
    }
    private static void insert(Connection db,String table,String columns,Object... values) throws SQLException {
        String[] names=columns.split(",");
        List<String> placeholders=new ArrayList<>();
        boolean h2=db.getMetaData().getDatabaseProductName().equals("H2");
        for(String name:names) placeholders.add(h2 && name.equals("raw_json") ? "? FORMAT JSON" : "?");
        try(var st=db.prepareStatement("INSERT INTO "+table+"("+columns+") VALUES ("+String.join(",",placeholders)+")")) {
            for(int i=0;i<values.length;i++) st.setObject(i+1,values[i]);
            st.executeUpdate();
        }
    }
}
