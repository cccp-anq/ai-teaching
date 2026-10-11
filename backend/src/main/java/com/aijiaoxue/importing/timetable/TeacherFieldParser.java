package com.aijiaoxue.importing.timetable;

import java.util.Arrays;
import java.util.List;

/** Splits only on ASCII commas; spaces inside an English name remain untouched. */
public final class TeacherFieldParser {
    private TeacherFieldParser() {}

    public static List<String> parse(String rawValue) {
        if (rawValue == null) {
            throw new IllegalArgumentException("teacher field is required");
        }
        List<String> teachers = Arrays.stream(rawValue.split(",", -1))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .toList();
        if (teachers.isEmpty()) {
            throw new IllegalArgumentException("at least one non-blank teacher is required");
        }
        return List.copyOf(teachers);
    }
}
