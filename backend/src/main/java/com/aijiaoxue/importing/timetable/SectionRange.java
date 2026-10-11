package com.aijiaoxue.importing.timetable;

public record SectionRange(int startSection, int endSection) {
    public SectionRange {
        if (startSection < 1 || endSection < startSection) {
            throw new IllegalArgumentException("section range must satisfy start >= 1 and end >= start");
        }
    }
}
