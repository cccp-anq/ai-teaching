package com.aijiaoxue.course.access;

import java.time.LocalDate;

/** Authorized database projection for the Offering-level part of DET-01. */
public record OfferingDetailRow(
        long offeringId,
        String displayName,
        Long courseId,
        String courseName,
        String courseCode,
        String syllabusRef,
        String description,
        String activityType,
        String classGroupText,
        Integer studentCount,
        long semesterId,
        String semesterCode,
        String semesterName,
        LocalDate firstWeekDate,
        long sourceScopeCollegeId,
        String sourceScopeCollegeCode,
        String sourceScopeCollegeName,
        Long ownerCollegeId,
        String ownerCollegeCode,
        String ownerCollegeName) {}
