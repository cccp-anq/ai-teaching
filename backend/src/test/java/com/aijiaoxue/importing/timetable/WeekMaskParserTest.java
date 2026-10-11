package com.aijiaoxue.importing.timetable;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WeekMaskParserTest {
    @Test
    void rightmostBitIsWeekOneAndGapsStayGaps() {
        assertEquals(List.of(1, 2, 3, 4), WeekMaskParser.parse("000000000000001111"));
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12, 13),
                WeekMaskParser.parse("000001111011111111"));
        assertEquals(List.of(10, 12, 14), WeekMaskParser.parse("000010101000000000"));
    }

    @Test
    void invalidMasksFailExplicitly() {
        for (String mask : List.of("000101", "000000000000001112", "00000000000000111A")) {
            assertThrows(IllegalArgumentException.class, () -> WeekMaskParser.parse(mask));
        }
        assertThrows(IllegalArgumentException.class, () -> WeekMaskParser.parse(null));
    }
}
