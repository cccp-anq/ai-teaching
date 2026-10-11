package com.aijiaoxue.course;

import java.util.ArrayList;
import java.util.List;

/** Source format: rightmost character is week 1. No calendar or holiday policy here. */
public final class WeekMask {
    private WeekMask() {}

    public static List<Integer> weeks(String mask) {
        if (mask == null || !mask.matches("[01]{18}")) {
            throw new IllegalArgumentException("week_mask must contain exactly 18 binary digits");
        }
        List<Integer> weeks = new ArrayList<>();
        for (int week = 1; week <= 18; week++) {
            if (mask.charAt(18 - week) == '1') weeks.add(week);
        }
        return List.copyOf(weeks);
    }
}
