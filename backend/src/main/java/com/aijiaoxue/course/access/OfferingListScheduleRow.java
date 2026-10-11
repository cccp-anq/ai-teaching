package com.aijiaoxue.course.access;

/** One schedule in a paged list result, including its owning offering. */
public record OfferingListScheduleRow(
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
