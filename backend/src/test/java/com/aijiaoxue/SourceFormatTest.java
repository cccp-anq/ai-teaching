package com.aijiaoxue;

import com.aijiaoxue.course.WeekMask;
import com.aijiaoxue.importing.SourceKeys;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceFormatTest {
    @Test void rightmostBitIsWeekOneAndNoncontiguousWeeksStaySeparate() {
        assertEquals(List.of(1,2,3,4), WeekMask.weeks("000000000000001111"));
        assertEquals(List.of(1,2,3,4,5,6,7,8,10,11,12,13), WeekMask.weeks("000001111011111111"));
        assertEquals(List.of(10,12,14), WeekMask.weeks("000010101000000000"));
        assertEquals(List.of(18), WeekMask.weeks("100000000000000000"));
        assertEquals(List.of(), WeekMask.weeks("000000000000000000"));
    }
    @Test void invalidMasksFailExplicitly() {
        for (String mask : List.of("", "101", "00000000000000111x", "0000000000000011111")) {
            assertThrows(IllegalArgumentException.class, () -> WeekMask.weeks(mask));
        }
        assertThrows(IllegalArgumentException.class, () -> WeekMask.weeks(null));
    }
    @Test void keysAreStableTrimOnlyAndUnambiguouslyFramed() {
        String a = SourceKeys.offering("source", "2026-FALL", "SOFTWARE", "课程", "本", "班级");
        assertEquals(a, SourceKeys.offering(" source ", "2026-FALL", "SOFTWARE", " 课程 ", "本", "班级 "));
        assertTrue(a.matches("[a-f0-9]{64}"));
        assertNotEquals(a, SourceKeys.offering("source", "2027-FALL", "SOFTWARE", "课程", "本", "班级"));
        assertNotEquals(SourceKeys.offering("ab", "c", "scope", "课程", "本", "班级"),
                SourceKeys.offering("a", "bc", "scope", "课程", "本", "班级"));
        assertNotEquals(SourceKeys.offering("s", "t", "c", "A B", "本", "软件2401-09"),
                SourceKeys.offering("s", "t", "c", "A  B", "本", "软件2401-09"));
    }
    @Test void scheduleKeyPreservesTimeAndPlaceChanges() {
        String offering = SourceKeys.offering("s", "t", "c", "n", "实", "class");
        String a = SourceKeys.schedule(offering, "000000000000001111", 1, 9, 12, "线上");
        assertEquals(a, SourceKeys.schedule(offering, "000000000000001111", 1, 9, 12, " 线上 "));
        assertNotEquals(a, SourceKeys.schedule(offering, "000000000000001111", 1, 9, 12, "待定"));
        assertNotEquals(a, SourceKeys.schedule(offering, "000000000000001111", 2, 9, 12, "线上"));
    }
    @Test void sourceTeacherIdentityIsScopeLocalAndNeverAnEmployeeNumber() {
        String a = SourceKeys.teacher("html", "scope-a", "Teacher Alpha");
        assertEquals(a, SourceKeys.teacher(" html ", "scope-a", " Teacher Alpha "));
        assertTrue(a.matches("[a-f0-9]{64}"));
        assertNotEquals(a, SourceKeys.teacher("html", "scope-b", "Teacher Alpha"));
    }
}
