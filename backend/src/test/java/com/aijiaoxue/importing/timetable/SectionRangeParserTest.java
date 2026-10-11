package com.aijiaoxue.importing.timetable;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SectionRangeParserTest {
    @Test
    void acceptsGeneralAndSingleSectionRanges() {
        assertEquals(new SectionRange(1, 4), SectionRangeParser.parse("1-4"));
        assertEquals(new SectionRange(11, 11), SectionRangeParser.parse("11-11"));
        assertEquals(new SectionRange(9, 12), SectionRangeParser.parse(" 9 - 12 "));
    }

    @Test
    void rejectsReverseMalformedAndNonPositiveRanges() {
        for (String value : new String[]{"8-5", "abc", "0-2", "1", "1-"}) {
            assertThrows(IllegalArgumentException.class, () -> SectionRangeParser.parse(value));
        }
        assertThrows(IllegalArgumentException.class, () -> SectionRangeParser.parse(null));
    }
}
