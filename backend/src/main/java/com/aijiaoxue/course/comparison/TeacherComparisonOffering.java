package com.aijiaoxue.course.comparison;

import com.aijiaoxue.course.detail.ScheduleDetail;
import com.aijiaoxue.course.detail.SemesterDetail;
import com.aijiaoxue.course.detail.SyllabusStatus;
import java.util.List;

/** Minimal Offering facts needed for teacher comparison; full detail remains a separate API. */
public record TeacherComparisonOffering(
        long offeringId,
        String displayName,
        String activityType,
        String classGroupText,
        String description,
        SemesterDetail semester,
        Integer studentCount,
        SyllabusStatus syllabusStatus,
        List<ScheduleDetail> teachingSchedules) {
    public TeacherComparisonOffering {
        teachingSchedules = List.copyOf(teachingSchedules);
    }
}
