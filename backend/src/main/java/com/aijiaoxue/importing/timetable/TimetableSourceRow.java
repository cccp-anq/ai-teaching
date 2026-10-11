package com.aijiaoxue.importing.timetable;

import java.util.List;
import java.util.Objects;

/**
 * One valid COURSES object. All eleven raw values are retained alongside safely normalized fields.
 * No official course, offering, teacher, or location identity is inferred here.
 */
public record TimetableSourceRow(
        int sourceIndex,
        String rawN,
        String rawT,
        String rawC,
        int rawP,
        String rawW,
        String rawRw,
        int rawD,
        String rawS,
        String rawH,
        String rawL,
        String rawG,
        String rawJson,
        String displayName,
        TimetableActivityType activityType,
        String classGroupText,
        int studentCount,
        String weekMask,
        List<Integer> weekNumbers,
        String rawWeekText,
        int weekday,
        int startSection,
        int endSection,
        List<String> teacherNames,
        String locationName,
        String locationGroup
) {
    public TimetableSourceRow {
        if (sourceIndex < 1) {
            throw new IllegalArgumentException("sourceIndex must be 1-based");
        }
        rawN = Objects.requireNonNull(rawN, "rawN");
        rawT = Objects.requireNonNull(rawT, "rawT");
        rawC = Objects.requireNonNull(rawC, "rawC");
        rawW = Objects.requireNonNull(rawW, "rawW");
        rawRw = Objects.requireNonNull(rawRw, "rawRw");
        rawS = Objects.requireNonNull(rawS, "rawS");
        rawH = Objects.requireNonNull(rawH, "rawH");
        rawL = Objects.requireNonNull(rawL, "rawL");
        rawG = Objects.requireNonNull(rawG, "rawG");
        rawJson = Objects.requireNonNull(rawJson, "rawJson");
        displayName = Objects.requireNonNull(displayName, "displayName");
        activityType = Objects.requireNonNull(activityType, "activityType");
        classGroupText = Objects.requireNonNull(classGroupText, "classGroupText");
        weekMask = Objects.requireNonNull(weekMask, "weekMask");
        weekNumbers = List.copyOf(weekNumbers);
        rawWeekText = Objects.requireNonNull(rawWeekText, "rawWeekText");
        teacherNames = List.copyOf(teacherNames);
        locationName = Objects.requireNonNull(locationName, "locationName");
        locationGroup = Objects.requireNonNull(locationGroup, "locationGroup");
    }
}
