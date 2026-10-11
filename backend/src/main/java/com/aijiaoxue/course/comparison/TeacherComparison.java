package com.aijiaoxue.course.comparison;

import java.util.List;

public record TeacherComparison(long teacherId, String displayName,
                                List<TeacherComparisonOffering> offerings) {
    public TeacherComparison {
        offerings = List.copyOf(offerings);
    }
}
