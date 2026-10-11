package com.aijiaoxue.supervision.coverage;

/** One offering row of the covered/uncovered drilldown; one row = one CourseOffering. */
public record CoverageOfferingRow(
        long offeringId,
        String displayName,
        String semesterCode,
        String semesterName,
        Integer studentCount,
        Long ownerCollegeId) {}
