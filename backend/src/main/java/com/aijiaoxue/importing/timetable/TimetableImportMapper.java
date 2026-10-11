package com.aijiaoxue.importing.timetable;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface TimetableImportMapper {
    final class GeneratedId {
        private Long id;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
    }

    record SemesterRef(long id, String code) {}
    record CollegeRef(long id, String code) {}
    record BatchRecord(long id, int totalRows, int successRows, int failureRows) {}
    record OfferingRecord(long id, Long courseId, long semesterId, long sourceScopeCollegeId,
                          String officialOfferingCode, String sourceOfferingKey, String displayName,
                          String activityType, String classGroupText, Integer studentCount, String status) {}
    record LocationRecord(long id, String displayName, String normalizedName,
                          String locationGroup, String locationType) {}
    record TeacherRecord(long id, String employeeNo, String displayName, String normalizedName,
                         String sourceTeacherKey, String identityStatus) {}
    record ScheduleRecord(long id, long offeringId, int weekday, int startSection, int endSection,
                          long locationId, String weekMask, String rawWeekText, String sourceScheduleKey,
                          String locationDisplayName, String locationNormalizedName,
                          String locationGroup, String locationType) {}

    record NewBatch(String sourceType, String sourceNamespace, String sourceTitle,
                    long sourceScopeCollegeId, long semesterId, String sourceFileName,
                    String sourceSha256, String parserVersion, int totalRows,
                    long operatorUserId, LocalDateTime startedAt) {}
    record NewOffering(long semesterId, long sourceScopeCollegeId, String sourceOfferingKey,
                       String displayName, String activityType, String classGroupText,
                       int studentCount, String status) {}
    record NewLocation(String displayName, String normalizedName,
                       String locationGroup, String locationType) {}
    record NewTeacher(String displayName, String normalizedName,
                      String sourceTeacherKey, String identityStatus) {}
    record NewSchedule(long offeringId, int weekday, int startSection, int endSection,
                       long locationId, String weekMask, String rawWeekText,
                       String sourceScheduleKey) {}
    record NewSourceRow(long importBatchId, TimetableSourceRow source, String rowHash,
                        long offeringId, long scheduleId) {}

    SemesterRef semester(long id);
    CollegeRef collegeForUpdate(long id);
    BatchRecord successfulBatch(@Param("sourceSha256") String sourceSha256,
                                @Param("semesterId") long semesterId,
                                @Param("sourceScopeCollegeId") long sourceScopeCollegeId);
    OfferingRecord offering(String sourceOfferingKey);
    LocationRecord location(@Param("normalizedName") String normalizedName,
                            @Param("locationGroup") String locationGroup);
    LocationRecord locationForUpdate(@Param("normalizedName") String normalizedName,
                                     @Param("locationGroup") String locationGroup);
    List<TeacherRecord> teachers(String sourceTeacherKey);
    ScheduleRecord schedule(String sourceScheduleKey);
    List<Integer> scheduleWeeks(long scheduleId);
    List<Long> scheduleTeacherIds(long scheduleId);
    int scheduleCount(long offeringId);
    int weekCount(long scheduleId);
    int teacherCount(long scheduleId);

    int insertBatch(@Param("holder") GeneratedId holder, @Param("row") NewBatch row);
    int finishBatch(@Param("id") long id, @Param("finishedAt") LocalDateTime finishedAt,
                    @Param("successRows") int successRows);
    int insertOffering(@Param("holder") GeneratedId holder, @Param("row") NewOffering row);
    int insertLocation(@Param("holder") GeneratedId holder, @Param("row") NewLocation row);
    int insertTeacher(@Param("holder") GeneratedId holder, @Param("row") NewTeacher row);
    int insertSchedule(@Param("holder") GeneratedId holder, @Param("row") NewSchedule row);
    int insertScheduleWeek(@Param("scheduleId") long scheduleId, @Param("weekNo") int weekNo);
    int insertScheduleTeacher(@Param("scheduleId") long scheduleId, @Param("teacherId") long teacherId);
    int insertSourceRow(NewSourceRow row);
}
