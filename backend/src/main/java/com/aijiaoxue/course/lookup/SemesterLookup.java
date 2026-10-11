package com.aijiaoxue.course.lookup;

/** Minimal read-only semester selection option; no calendar dates are invented. */
public record SemesterLookup(long semesterId, String semesterCode, String semesterName) {}
