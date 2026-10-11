package com.aijiaoxue.course.access;

public record ComparisonOfferingRow(
        long selectedTeacherId,
        long offeringId,
        String displayName,
        String activityType,
        String classGroupText,
        Integer studentCount,
        long semesterId,
        String semesterCode,
        String semesterName,
        String syllabusRef,
        String description) {}
