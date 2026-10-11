package com.aijiaoxue.importing.timetable;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TeacherFieldParserTest {
    @Test
    void trimsCommaSeparatedNamesAndKeepsTheirOrder() {
        assertEquals(List.of("Teacher Alpha", "Teacher Beta"),
                TeacherFieldParser.parse(" Teacher Alpha, ,Teacher Beta "));
    }

    @Test
    void keepsSpacesInsideAnEnglishName() {
        assertEquals(List.of("Teacher Iota Example", "教师甲"),
                TeacherFieldParser.parse("Teacher Iota Example,教师甲"));
    }

    @Test
    void requiresAtLeastOneTeacher() {
        assertThrows(IllegalArgumentException.class, () -> TeacherFieldParser.parse(" , , "));
        assertThrows(IllegalArgumentException.class, () -> TeacherFieldParser.parse(null));
    }
}
