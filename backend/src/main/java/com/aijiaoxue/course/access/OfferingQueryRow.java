package com.aijiaoxue.course.access;

/** Database row for one distinct CourseOffering in the paged query. */
public record OfferingQueryRow(
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
        Long ownerCollegeId,
        String ownerCollegeCode,
        String ownerCollegeName,
        String syllabusRef) {}
