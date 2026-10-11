package com.aijiaoxue.course.access;

public record ComparisonScheduleRow(
        long selectedTeacherId,
        long offeringId,
        long scheduleId,
        int weekday,
        int startSection,
        int endSection,
        String rawWeekText,
        long locationId,
        String locationDisplayName,
        String locationGroup,
        String locationType) {
    OfferingScheduleRow schedule() {
        return new OfferingScheduleRow(scheduleId, weekday, startSection, endSection, rawWeekText,
                locationId, locationDisplayName, locationGroup, locationType);
    }
}
