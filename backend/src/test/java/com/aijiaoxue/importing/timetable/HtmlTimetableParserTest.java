package com.aijiaoxue.importing.timetable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlTimetableParserTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HtmlTimetableParser parser = new HtmlTimetableParser();

    @Test
    void parsesAnonymousFixtureAndAllRequiredShapes() throws IOException {
        byte[] html;
        try (var input = getClass().getResourceAsStream("/fixtures/timetable-parser-anonymous.html")) {
            assertNotNull(input);
            html = input.readAllBytes();
        }

        TimetableParseResult result = parser.parse(html);

        assertFalse(result.hasFatalError());
        assertEquals(9, result.totalSourceRows());
        assertEquals(9, result.validRowCount());
        assertEquals(0, result.invalidRowCount());
        assertTrue(result.validationErrors().isEmpty());
        TimetableSourceRow first = result.parsedRows().get(0);
        assertEquals(1, first.sourceIndex());
        assertEquals("测试课程A", first.rawN());
        assertEquals("本", first.rawT());
        assertEquals("测试班级01", first.rawC());
        assertEquals(30, first.rawP());
        assertEquals("000000000000001111", first.rawW());
        assertEquals("1-4周", first.rawRw());
        assertEquals(2, first.rawD());
        assertEquals("1-2", first.rawS());
        assertEquals("Teacher Alpha", first.rawH());
        assertEquals("测试楼A101", first.rawL());
        assertEquals("其它", first.rawG());
        assertEquals(TimetableActivityType.UNDERGRAD_COURSE, first.activityType());
        assertEquals(TimetableActivityType.PRACTICE, result.parsedRows().get(2).activityType());
        assertTrue(result.parsedRows().stream().anyMatch(row -> row.studentCount() == 0));
        assertTrue(result.parsedRows().stream().anyMatch(row -> row.locationName().equals("线上")
                && row.locationGroup().equals("线上")));
        assertTrue(result.parsedRows().stream().anyMatch(row -> row.locationName().equals("待定")
                && row.locationGroup().equals("其它")));
        assertTrue(result.parsedRows().stream().anyMatch(row -> row.teacherNames().size() == 2));
        assertTrue(result.parsedRows().stream().anyMatch(row -> row.startSection() == 11
                && row.endSection() == 11));
        assertTrue(result.parsedRows().stream().anyMatch(row -> row.startSection() == 9
                && row.endSection() == 12));

        TimetableSourceRow gap = result.parsedRows().get(5);
        assertTrue(gap.weekNumbers().contains(8));
        assertFalse(gap.weekNumbers().contains(9));
        assertTrue(gap.weekNumbers().contains(10));
        assertEquals(List.of(10, 12, 14), result.parsedRows().get(6).weekNumbers());
    }

    @Test
    void preservesRawValuesWhileApplyingOnlySafeNormalization() throws Exception {
        Map<String, Object> row = validRow();
        row.put("n", "  数据结构与算法-Array  ");
        row.put("c", "  测试班级01  ");
        row.put("h", " Teacher Iota Example, 教师甲 ");
        row.put("l", "  线上  ");
        row.put("g", "  线上  ");

        TimetableSourceRow parsed = parser.parse(document(List.of(row))).parsedRows().get(0);

        assertEquals("  数据结构与算法-Array  ", parsed.rawN());
        assertEquals("  测试班级01  ", parsed.rawC());
        assertEquals(" Teacher Iota Example, 教师甲 ", parsed.rawH());
        assertEquals("  线上  ", parsed.rawL());
        assertEquals("数据结构与算法-Array", parsed.displayName());
        assertEquals("测试班级01", parsed.classGroupText());
        assertEquals(List.of("Teacher Iota Example", "教师甲"), parsed.teacherNames());
        assertEquals("线上", parsed.locationName());
        assertTrue(parsed.rawJson().contains("数据结构与算法-Array"));
    }

    @Test
    void bracketMatchingIgnoresStringsEscapesCommentsAndFalseDeclarations() throws Exception {
        Map<String, Object> row = validRow();
        row.put("n", "测试]课程\"A");
        String html = "<html><script>"
                + "const bait = \"const COURSES = [not real]\";"
                + "/* const COURSES = [also not real]; */"
                + "const COURSES = " + JSON.writeValueAsString(List.of(row)) + ";"
                + "</script></html>";

        TimetableParseResult result = parser.parse(html);

        assertEquals(1, result.validRowCount());
        assertEquals("测试]课程\"A", result.parsedRows().get(0).rawN());
    }

    @Test
    void documentFailuresHaveStableFatalCodes() {
        assertFatal(parser.parse("<html><script>const OTHER = [];</script></html>"),
                TimetableErrorCode.COURSES_NOT_FOUND);
        assertFatal(parser.parse("<html><script>const COURSES = [{\"n\":1};</script></html>"),
                TimetableErrorCode.COURSES_ARRAY_MALFORMED);
        assertFatal(parser.parse("<html><script>const COURSES = [{n:\"x\"}];</script></html>"),
                TimetableErrorCode.COURSES_ARRAY_MALFORMED);
        assertFatal(parser.parse("<html><script>const COURSES = {};</script></html>"),
                TimetableErrorCode.COURSES_ARRAY_MALFORMED);
        assertFatal(parser.parse("<html><script>const COURSES = []"),
                TimetableErrorCode.INVALID_SOURCE_DOCUMENT);
        assertFatal(parser.parse("  "), TimetableErrorCode.INVALID_SOURCE_DOCUMENT);
        assertFatal(parser.parse(new byte[]{(byte) 0xC3, 0x28}),
                TimetableErrorCode.UNSUPPORTED_ENCODING);
        assertFatal(parser.parse(new byte[]{(byte) 0xFF, (byte) 0xFE, 0x00, 0x00}),
                TimetableErrorCode.UNSUPPORTED_ENCODING);
    }

    @Test
    void missingAndMeaninglessObjectsReturnLocatedErrorsWithoutNpe() throws Exception {
        Map<String, Object> missingName = validRow();
        missingName.remove("n");
        String html = "<script>const COURSES = ["
                + JSON.writeValueAsString(missingName) + ",null,42,{}];</script>";

        TimetableParseResult result = parser.parse(html);

        assertEquals(4, result.totalSourceRows());
        assertEquals(0, result.validRowCount());
        assertEquals(4, result.invalidRowCount());
        assertError(result, 1, "n", TimetableErrorCode.MISSING_REQUIRED_FIELD);
        assertError(result, 2, null, TimetableErrorCode.INVALID_ROW_OBJECT);
        assertError(result, 3, null, TimetableErrorCode.INVALID_ROW_OBJECT);
        assertError(result, 4, "n", TimetableErrorCode.MISSING_REQUIRED_FIELD);
        assertTrue(result.validationErrors().stream().allMatch(error -> !error.fatal()));
    }

    @Test
    void fieldValidationCoversEveryRequiredErrorCase() throws Exception {
        assertInvalid(changed("n", "   "), "n", TimetableErrorCode.BLANK_NAME);
        assertInvalid(changed("t", "X"), "t", TimetableErrorCode.INVALID_ACTIVITY_TYPE);
        assertInvalid(changed("p", -1), "p", TimetableErrorCode.INVALID_STUDENT_COUNT);
        assertInvalid(changed("p", "30"), "p", TimetableErrorCode.INVALID_FIELD_TYPE);
        assertInvalid(changed("w", "000101"), "w", TimetableErrorCode.INVALID_WEEK_MASK);
        assertInvalid(changed("w", "000000000000001112"), "w", TimetableErrorCode.INVALID_WEEK_MASK);
        assertInvalid(changed("w", "00000000000000111A"), "w", TimetableErrorCode.INVALID_WEEK_MASK);
        assertInvalid(changed("d", 0), "d", TimetableErrorCode.INVALID_WEEKDAY);
        assertInvalid(changed("d", 8), "d", TimetableErrorCode.INVALID_WEEKDAY);
        assertInvalid(changed("s", "5-3"), "s", TimetableErrorCode.INVALID_SECTION_RANGE);
        assertInvalid(changed("s", "abc"), "s", TimetableErrorCode.INVALID_SECTION_RANGE);
        assertInvalid(changed("h", " , "), "h", TimetableErrorCode.EMPTY_TEACHER);
        assertInvalid(changed("l", "  "), "l", TimetableErrorCode.EMPTY_LOCATION);
        assertInvalid(changed("g", "未知楼群"), "g", TimetableErrorCode.UNKNOWN_LOCATION_GROUP);
    }

    @Test
    void oneInvalidRowDoesNotDiscardOthersAndSourceIndexIsOneBased() throws Exception {
        Map<String, Object> invalid = changed("d", 9);
        TimetableParseResult result = parser.parse(document(List.of(validRow(), invalid, validRow())));

        assertEquals(3, result.totalSourceRows());
        assertEquals(2, result.validRowCount());
        assertEquals(1, result.invalidRowCount());
        assertEquals(List.of(1, 3), result.parsedRows().stream()
                .map(TimetableSourceRow::sourceIndex).toList());
        TimetableValidationError error = result.validationErrors().get(0);
        assertEquals(2, error.sourceIndex());
        assertEquals("COURSES[2].d", error.location());
        assertEquals("9", error.rawValue());
        assertTrue(error.toString().contains("INVALID_WEEKDAY"));
    }

    @Test
    void duplicateSourceObjectsAreBothPreserved() throws Exception {
        Map<String, Object> row = validRow();
        TimetableParseResult result = parser.parse(document(List.of(row, row)));

        assertEquals(2, result.totalSourceRows());
        assertEquals(2, result.validRowCount());
        assertEquals(List.of(1, 2), result.parsedRows().stream()
                .map(TimetableSourceRow::sourceIndex).toList());
    }

    private void assertInvalid(Map<String, Object> row, String field,
                               TimetableErrorCode expectedCode) throws Exception {
        TimetableParseResult result = parser.parse(document(List.of(row)));
        assertEquals(0, result.validRowCount());
        assertEquals(1, result.invalidRowCount());
        assertFalse(result.hasFatalError());
        assertError(result, 1, field, expectedCode);
    }

    private void assertError(TimetableParseResult result, int index, String field,
                             TimetableErrorCode expectedCode) {
        assertTrue(result.validationErrors().stream().anyMatch(error ->
                Integer.valueOf(index).equals(error.sourceIndex())
                        && java.util.Objects.equals(field, error.field())
                        && error.errorCode() == expectedCode));
    }

    private void assertFatal(TimetableParseResult result, TimetableErrorCode expectedCode) {
        assertTrue(result.hasFatalError());
        assertEquals(1, result.fatalErrors().size());
        assertEquals(expectedCode, result.fatalErrors().get(0).errorCode());
        assertEquals(0, result.totalSourceRows());
    }

    private Map<String, Object> changed(String field, Object value) {
        Map<String, Object> row = validRow();
        row.put(field, value);
        return row;
    }

    private Map<String, Object> validRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("n", "测试课程A");
        row.put("t", "本");
        row.put("c", "测试班级01");
        row.put("p", 30);
        row.put("w", "000000000000001111");
        row.put("rw", "1-4周");
        row.put("d", 2);
        row.put("s", "1-2");
        row.put("h", "Teacher Alpha");
        row.put("l", "测试楼A101");
        row.put("g", "其它");
        return row;
    }

    private String document(List<Map<String, Object>> rows) throws Exception {
        return "<!doctype html><html><script data-source=\"fixture\">const /* marker */ COURSES = "
                + JSON.writeValueAsString(rows) + ";</script></html>";
    }
}
