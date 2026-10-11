package com.aijiaoxue.identity.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Round 3.8 BOOT decision (DEC-024 P1/P2/P3), demo/test face: an explicit, repeatable,
 * idempotent demo bootstrap behind the {@code demo} profile. It never runs in production
 * startup, never invents formal identifiers beyond the demo codes, and never stores the
 * demo password outside its BCrypt hash. Lookup endpoints stay read-only and are
 * unrelated to this bootstrap.
 *
 * Passwords come from the DEMO_PASSWORD property (environment variable or -D) and are
 * BCrypt-hashed; without it the accounts are skipped so an accidental profile misfire
 * cannot create default-credential accounts. All demo data is anonymous.
 */
@Component
@Profile("demo")
public class DemoBootstrapRunner implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final Environment environment;
    private final PasswordEncoder passwords;

    public DemoBootstrapRunner(JdbcTemplate jdbc, Environment environment, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.environment = environment;
        this.passwords = passwords;
    }

    @Override
    public void run(ApplicationArguments args) {
        String password = environment.getProperty("DEMO_PASSWORD");
        Timestamp now = Timestamp.from(Instant.now());

        Long softCollege = college("DEMO-SOFT", "演示软件学院", now);
        Long otherCollege = college("DEMO-OTHER", "演示文理学院", now);
        Long semester = semester("DEMO-2026-FALL", "2026秋演示学期", now);
        semester("DEMO-2026-SPRING", "2026春演示学期", now); // empty term: zero-denominator demo
        Long teacherA = teacher("demo-t1", "演示教师甲", now);
        Long teacherB = teacher("demo-t2", "演示教师乙", now);
        Long teacherC = teacher("demo-t3", "演示教师丙", now);
        Long teacherD = teacher("demo-t4", "演示教师丁", now);
        Long teacherE = teacher("demo-t5", "演示教师戊", now);
        Long course1 = course("DEMO-COURSE-001", "演示课程数据结构", softCollege,
                "demo:syllabus-001", "演示课程简介：面向本科生的数据结构入门课程，覆盖线性表、树与图。", now);
        Long course2 = course("DEMO-COURSE-002", "演示课程算法设计", otherCollege, null, null, now);

        offering("DEMO-OFFERING-001", semester, softCollege, course1, "演示开课数据结构甲班",
                "UNDERGRAD_COURSE", "演示软工2401", 45, now,
                "111100000000000000", 1, 1, 2, "演示教室A", "PHYSICAL", List.of(teacherA, teacherB));
        offering("DEMO-OFFERING-002", semester, softCollege, course2, "演示开课算法设计",
                "UNDERGRAD_COURSE", "演示软工2402", 0, now,
                "000011110000000000", 2, 2, 4, "线上", "ONLINE", List.of(teacherC));
        offering("DEMO-OFFERING-003", semester, otherCollege, null, "演示实践环节未映射课程",
                "PRACTICE", "演示实训班", 30, now,
                "000000001111000000", 3, 3, 6, "演示教室B", "PHYSICAL", List.of(teacherD, teacherE));
        // AC-03 demo shape: schedule A = week1 + room C, schedule B = week2 + room D.
        offeringAc03("DEMO-OFFERING-004", semester, softCollege, course1, "演示开课时间地点匹配",
                "UNDERGRAD_COURSE", "演示软工2403", 28, now,
                "000000000000000001", 4, 1, 2, "演示教室C",
                "000000000000000010", 4, 1, 2, "演示教室D", List.of(teacherA));

        if (password == null || password.isBlank()) {
            System.out.println("[demo-bootstrap] DEMO_PASSWORD not set; demo accounts were NOT created.");
            return;
        }
        role("ADMIN", "演示管理员");
        role("DIRECTOR", "演示教研室主任");
        role("TEACHER", "演示任课教师");
        role("SUPERVISOR", "演示教学督导");
        account("demo-admin", password, "ADMIN", null, null, now);
        account("demo-director", password, "DIRECTOR", null, null, now);
        account("demo-teacher", password, "TEACHER", teacherA, null, now);
        Long offering2Id = jdbc.queryForObject(
                "SELECT id FROM course_offering WHERE source_offering_key=?",
                Long.class, offeringKey("DEMO-OFFERING-002", "DEMO-SOFT"));
        account("demo-supervisor", password, "SUPERVISOR", null, offering2Id, now);
    }

    private void role(String code, String name) {
        jdbc.update("INSERT IGNORE INTO role (role_code, role_name) VALUES (?,?)", code, name);
    }

    private Long college(String code, String name, Timestamp now) {
        jdbc.update("INSERT IGNORE INTO college (college_code, college_name, created_at, updated_at) VALUES (?,?,?,?)",
                code, name, now, now);
        return jdbc.queryForObject("SELECT id FROM college WHERE college_code=?", Long.class, code);
    }

    private Long semester(String code, String name, Timestamp now) {
        jdbc.update("INSERT IGNORE INTO semester (semester_code, semester_name, teaching_week_count, created_at, updated_at) VALUES (?,?,?,?,?)",
                code, name, (short) 18, now, now);
        return jdbc.queryForObject("SELECT id FROM semester WHERE semester_code=?", Long.class, code);
    }

    private Long teacher(String sourceKey, String displayName, Timestamp now) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM teacher WHERE source_teacher_key=?", Long.class, sourceKey);
        if (count != null && count > 0)
            return jdbc.queryForObject("SELECT id FROM teacher WHERE source_teacher_key=?", Long.class, sourceKey);
        jdbc.update("INSERT INTO teacher (display_name, normalized_name, identity_status, source_teacher_key, created_at, updated_at) VALUES (?,?,?,?,?,?)",
                displayName, displayName, "NAME_ONLY", sourceKey, now, now);
        return jdbc.queryForObject("SELECT id FROM teacher WHERE source_teacher_key=?", Long.class, sourceKey);
    }

    private Long course(String code, String name, Long ownerCollegeId, String syllabusRef,
                        String description, Timestamp now) {
        // course_code has no unique constraint in V2; idempotency is checked explicitly.
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM course WHERE course_code=?", Long.class, code);
        if (count == null || count == 0)
            jdbc.update("INSERT INTO course (course_code, course_name, owner_college_id, syllabus_ref, verification_status, description, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
                    code, name, ownerCollegeId, syllabusRef, "VERIFIED", description, now, now);
        return jdbc.queryForObject("SELECT id FROM course WHERE course_code=?", Long.class, code);
    }

    private String offeringKey(String offeringCode, String scopeCode) {
        return com.aijiaoxue.importing.SourceKeys.offering("DEMO", "DEMO-2026-FALL", scopeCode,
                demoName(offeringCode), "本", offeringCode);
    }

    /** Name participates in the offering source key; keep a stable demo lookup. */
    private static String demoName(String offeringCode) {
        return switch (offeringCode) {
            case "DEMO-OFFERING-001" -> "演示开课数据结构甲班";
            case "DEMO-OFFERING-002" -> "演示开课算法设计";
            case "DEMO-OFFERING-003" -> "演示实践环节未映射课程";
            default -> "演示开课时间地点匹配";
        };
    }

    private void offering(String offeringCode, Long semesterId, Long scopeCollegeId, Long courseId,
                          String displayName, String type, String classes, int students, Timestamp now,
                          String mask, int weekday, int startSection, int endSection, String room,
                          String roomType, List<Long> teacherIds) {
        String scopeCode = jdbc.queryForObject("SELECT college_code FROM college WHERE id=?", String.class, scopeCollegeId);
        String rawType = "PRACTICE".equals(type) ? "实" : "本";
        String sourceKey = com.aijiaoxue.importing.SourceKeys.offering("DEMO", "DEMO-2026-FALL", scopeCode,
                displayName, rawType, offeringCode);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM course_offering WHERE source_offering_key=?", Long.class, sourceKey);
        if (count != null && count > 0) return;

        Long locationId = location(room, roomType, now);
        jdbc.update("INSERT INTO course_offering (course_id,semester_id,source_scope_college_id,source_offering_key,display_name,activity_type,class_group_text,student_count,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                courseId, semesterId, scopeCollegeId, sourceKey, displayName,
                "PRACTICE".equals(type) ? "PRACTICE" : "UNDERGRAD_COURSE", classes, students, "SOURCE_ONLY", now, now);
        Long offeringId = jdbc.queryForObject("SELECT id FROM course_offering WHERE source_offering_key=?", Long.class, sourceKey);
        String scheduleKey = com.aijiaoxue.importing.SourceKeys.schedule(sourceKey, mask, weekday, startSection, endSection, room);
        jdbc.update("INSERT INTO class_schedule (offering_id,weekday,start_section,end_section,location_id,week_mask,raw_week_text,source_schedule_key,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                offeringId, weekday, startSection, endSection, locationId, mask, mask + "周", scheduleKey, now, now);
        Long scheduleId = jdbc.queryForObject(
                "SELECT id FROM class_schedule WHERE offering_id=? AND weekday=? AND start_section=?",
                Long.class, offeringId, weekday, startSection);
        insertWeeksAndTeachers(scheduleId, mask, teacherIds);
    }

    private void offeringAc03(String offeringCode, Long semesterId, Long scopeCollegeId, Long courseId,
                              String displayName, String type, String classes, int students, Timestamp now,
                              String maskA, int weekdayA, int startA, int endA, String roomA,
                              String maskB, int weekdayB, int startB, int endB, String roomB,
                              List<Long> teacherIds) {
        String scopeCode = jdbc.queryForObject("SELECT college_code FROM college WHERE id=?", String.class, scopeCollegeId);
        String sourceKey = com.aijiaoxue.importing.SourceKeys.offering("DEMO", "DEMO-2026-FALL", scopeCode,
                displayName, "本", offeringCode);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM course_offering WHERE source_offering_key=?", Long.class, sourceKey);
        if (count != null && count > 0) return;

        jdbc.update("INSERT INTO course_offering (course_id,semester_id,source_scope_college_id,source_offering_key,display_name,activity_type,class_group_text,student_count,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                courseId, semesterId, scopeCollegeId, sourceKey, displayName, type, classes, students, "SOURCE_ONLY", now, now);
        Long offeringId = jdbc.queryForObject("SELECT id FROM course_offering WHERE source_offering_key=?", Long.class, sourceKey);
        Long roomC = location(roomA, "PHYSICAL", now);
        Long roomD = location(roomB, "PHYSICAL", now);
        String scheduleKeyA = com.aijiaoxue.importing.SourceKeys.schedule(sourceKey, maskA, weekdayA, startA, endA, roomA);
        jdbc.update("INSERT INTO class_schedule (offering_id,weekday,start_section,end_section,location_id,week_mask,raw_week_text,source_schedule_key,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                offeringId, weekdayA, startA, endA, roomC, maskA, "第1周", scheduleKeyA, now, now);
        Long scheduleA = jdbc.queryForObject(
                "SELECT id FROM class_schedule WHERE offering_id=? AND weekday=? AND start_section=? AND location_id=?",
                Long.class, offeringId, weekdayA, startA, roomC);
        String scheduleKeyB = com.aijiaoxue.importing.SourceKeys.schedule(sourceKey, maskB, weekdayB, startB, endB, roomB);
        jdbc.update("INSERT INTO class_schedule (offering_id,weekday,start_section,end_section,location_id,week_mask,raw_week_text,source_schedule_key,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                offeringId, weekdayB, startB, endB, roomD, maskB, "第2周", scheduleKeyB, now, now);
        Long scheduleB = jdbc.queryForObject(
                "SELECT id FROM class_schedule WHERE offering_id=? AND weekday=? AND start_section=? AND location_id=?",
                Long.class, offeringId, weekdayB, startB, roomD);
        insertWeeksAndTeachers(scheduleA, maskA, teacherIds);
        insertWeeksAndTeachers(scheduleB, maskB, teacherIds);
    }

    private Long location(String displayName, String type, Timestamp now) {
        String group = "PHYSICAL".equals(type) ? "演示楼" : displayName;
        jdbc.update("INSERT IGNORE INTO location (display_name, normalized_name, location_group, location_type, created_at, updated_at) VALUES (?,?,?,?,?,?)",
                displayName, displayName, group, type, now, now);
        return jdbc.queryForObject("SELECT id FROM location WHERE normalized_name=? AND location_group=?",
                Long.class, displayName, group);
    }

    private void insertWeeksAndTeachers(Long scheduleId, String mask, List<Long> teacherIds) {
        // mask is 18 chars; char position i (0-based) is week 18-i.
        for (int i = 0; i < mask.length(); i++) {
            if (mask.charAt(i) == '1') {
                int week = mask.length() - i;
                jdbc.update("INSERT IGNORE INTO schedule_week (schedule_id, week_no) VALUES (?,?)", scheduleId, week);
            }
        }
        for (Long teacherId : Objects.requireNonNull(teacherIds, "teacherIds"))
            jdbc.update("INSERT IGNORE INTO schedule_teacher (schedule_id, teacher_id) VALUES (?,?)", scheduleId, teacherId);
    }

    private void account(String username, String password, String roleCode, Long teacherId,
                         Long offeringId, Timestamp now) {
        Long roleId = jdbc.queryForObject("SELECT id FROM role WHERE role_code=?", Long.class, roleCode);
        if (roleId == null) throw new IllegalStateException("role missing: " + roleCode);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM user_account WHERE username=?", Long.class, username);
        if (count != null && count > 0) return;
        jdbc.update("INSERT INTO user_account (username, password_hash, teacher_id, enabled, created_at, updated_at) VALUES (?,?,?,?,?,?)",
                username, passwords.encode(password), teacherId, true, now, now);
        Long userId = jdbc.queryForObject("SELECT id FROM user_account WHERE username=?", Long.class, username);
        jdbc.update("INSERT INTO user_role_scope (user_id, role_id, scope_type, offering_id, teacher_id) VALUES (?,?,?,?,?)",
                userId, roleId, scopeType(roleCode), offeringId,
                "TEACHER".equals(roleCode) ? teacherId : null);
    }

    private static String scopeType(String roleCode) {
        return switch (roleCode) {
            case "ADMIN" -> "GLOBAL";
            case "DIRECTOR" -> "GLOBAL";
            case "TEACHER" -> "SELF_TEACHER";
            case "SUPERVISOR" -> "OFFERING";
            default -> throw new IllegalStateException("unsupported demo role " + roleCode);
        };
    }
}
