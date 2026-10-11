package com.aijiaoxue.course.query;

import com.aijiaoxue.course.detail.CollegeDetail;
import com.aijiaoxue.course.detail.ScheduleDetail;
import com.aijiaoxue.course.detail.SyllabusStatus;
import java.util.List;

/** Stable list projection with query-result metadata; the DET-01 detail contract remains separate. */
public record CourseOfferingListItem(
        long offeringId,
        String displayName,
        String courseCode,
        String activityType,
        String classGroupText,
        Integer studentCount,
        long semesterId,
        String semesterCode,
        String semesterName,
        long sourceScopeCollegeId,
        CollegeDetail ownerCollege,
        SyllabusStatus syllabusStatus,
        String syllabusUrl,
        List<String> teacherNames,
        List<ScheduleDetail> schedules) {
    public CourseOfferingListItem {
        teacherNames = List.copyOf(teacherNames);
        schedules = List.copyOf(schedules);
    }
}
