package com.aijiaoxue.course.detail;

import java.time.LocalDate;

/** Approved minimum semester projection; nullable calendar dates are deliberately not invented. */
public record SemesterDetail(long semesterId, String semesterCode, String semesterName,
                             LocalDate firstWeekDate) {
    public SemesterDetail(long semesterId, String semesterCode, String semesterName) {
        this(semesterId,semesterCode,semesterName,null);
    }
}
