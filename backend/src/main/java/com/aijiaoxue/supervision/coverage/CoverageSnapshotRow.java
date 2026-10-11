package com.aijiaoxue.supervision.coverage;

/** One eligible offering and its coverage flag from a single database snapshot query. */
public record CoverageSnapshotRow(
        long offeringId,
        String displayName,
        String semesterCode,
        String semesterName,
        Integer studentCount,
        Long ownerCollegeId,
        boolean covered) {
    CoverageOfferingRow offering() {
        return new CoverageOfferingRow(offeringId, displayName, semesterCode, semesterName,
                studentCount, ownerCollegeId);
    }
}
