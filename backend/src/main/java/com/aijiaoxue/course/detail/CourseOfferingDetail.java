package com.aijiaoxue.course.detail;

import java.util.List;

/** Read-only DET-01 contract, intentionally independent from persistence/source records. */
public record CourseOfferingDetail(
        long offeringId,
        String displayName,
        String description,
        Long courseId,
        String courseName,
        String courseCode,
        String activityType,
        String classGroupText,
        Integer studentCount,
        SemesterDetail semester,
        CollegeDetail sourceScopeCollege,
        CollegeDetail ownerCollege,
        SyllabusStatus syllabusStatus,
        String syllabusUrl,
        List<TeacherDetail> teachers,
        List<ScheduleDetail> schedules) {
    public CourseOfferingDetail {
        teachers = List.copyOf(teachers);
        schedules = List.copyOf(schedules);
    }
}
