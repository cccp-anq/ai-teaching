package com.aijiaoxue.importing.timetable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses a general positive start-end section range without imposing an upper limit. */
public final class SectionRangeParser {
    private static final Pattern RANGE = Pattern.compile("^\\s*(\\d+)\\s*-\\s*(\\d+)\\s*$");

    private SectionRangeParser() {}

    public static SectionRange parse(String rawValue) {
        if (rawValue == null) {
            throw new IllegalArgumentException("section range is required");
        }
        Matcher matcher = RANGE.matcher(rawValue);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("section range must use start-end with positive integers");
        }
        try {
            int start = Integer.parseInt(matcher.group(1));
            int end = Integer.parseInt(matcher.group(2));
            return new SectionRange(start, end);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("section number exceeds the supported integer range", exception);
        }
    }
}
