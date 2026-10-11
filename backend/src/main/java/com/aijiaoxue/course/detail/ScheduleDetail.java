package com.aijiaoxue.course.detail;

import java.util.List;

/** One complete time-location-teacher fact. */
public record ScheduleDetail(
        long scheduleId,
        List<Integer> weeks,
        String rawWeekText,
        int weekday,
        int startSection,
        int endSection,
        LocationDetail location,
        List<TeacherDetail> teachers) {
    public ScheduleDetail {
        weeks = List.copyOf(weeks);
        teachers = List.copyOf(teachers);
    }
}
