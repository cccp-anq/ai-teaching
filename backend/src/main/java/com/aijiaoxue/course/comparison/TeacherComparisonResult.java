package com.aijiaoxue.course.comparison;

import java.util.List;

/** Read-only CMP-01 response in the caller's selected teacher order. */
public record TeacherComparisonResult(List<TeacherComparison> teachers) {
    public TeacherComparisonResult {
        teachers = List.copyOf(teachers);
    }
}
