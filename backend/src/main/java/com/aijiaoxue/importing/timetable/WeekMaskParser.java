package com.aijiaoxue.importing.timetable;

import com.aijiaoxue.course.WeekMask;
import java.util.List;

/** Importing-module adapter that reuses the single approved week-mask algorithm. */
public final class WeekMaskParser {
    private WeekMaskParser() {}

    public static List<Integer> parse(String rawValue) {
        return WeekMask.weeks(rawValue);
    }
}
