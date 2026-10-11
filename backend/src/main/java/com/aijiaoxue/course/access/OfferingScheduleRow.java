package com.aijiaoxue.course.access;

/** One schedule and its directly-owned location, before child collections are attached. */
public record OfferingScheduleRow(
        long scheduleId,
        int weekday,
        int startSection,
        int endSection,
        String rawWeekText,
        long locationId,
        String locationDisplayName,
        String locationGroup,
        String locationType) {}
