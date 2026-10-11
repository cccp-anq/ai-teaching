package com.aijiaoxue.course.query;

/** Normalized application criteria. None of these fields grants an authorization scope. */
public record CourseOfferingQueryCriteria(
        Long semesterId,
        String courseName,
        String courseCode,
        String teacherName,
        Long sourceScopeCollegeId,
        Long ownerCollegeId,
        Integer week,
        Integer weekday,
        Integer section,
        String location,
        int page,
        int size) {

    public CourseOfferingQueryCriteria {
        requirePositive(semesterId, "semesterId");
        requirePositive(sourceScopeCollegeId, "sourceScopeCollegeId");
        requirePositive(ownerCollegeId, "ownerCollegeId");
        requireRange(week, 1, 18, "week");
        requireRange(weekday, 1, 7, "weekday");
        requireRange(section, 1, Short.MAX_VALUE, "section");
        if (page < 1) throw new IllegalArgumentException("page must be at least 1");
        if (size < 1 || size > 100) throw new IllegalArgumentException("size must be between 1 and 100");
        courseName = normalize(courseName);
        courseCode = normalize(courseCode);
        teacherName = normalize(teacherName);
        location = normalize(location);
    }

    public long offset() {
        return ((long) page - 1) * size;
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static void requirePositive(Long value, String field) {
        if (value != null && value < 1) throw new IllegalArgumentException(field + " must be positive");
    }

    private static void requireRange(Integer value, int minimum, int maximum, String field) {
        if (value != null && (value < minimum || value > maximum))
            throw new IllegalArgumentException(field + " is outside the supported range");
    }
}
