package com.aijiaoxue.importing.timetable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Pure parser for the static const COURSES JSON array embedded in timetable HTML. */
public final class HtmlTimetableParser {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> LOCATION_GROUPS = Set.of(
            "1号", "信息", "建筑", "文管", "线上", "生命", "其它");
    private static final int RAW_VALUE_LIMIT = 256;

    public TimetableParseResult parse(Path source) {
        if (source == null) {
            return TimetableParseResult.fatal(
                    TimetableErrorCode.INVALID_SOURCE_DOCUMENT, "source path is required");
        }
        try {
            return parse(Files.readAllBytes(source));
        } catch (IOException exception) {
            return TimetableParseResult.fatal(
                    TimetableErrorCode.INVALID_SOURCE_DOCUMENT, "source document could not be read");
        }
    }

    public TimetableParseResult parse(byte[] sourceBytes) {
        if (sourceBytes == null || sourceBytes.length == 0) {
            return TimetableParseResult.fatal(
                    TimetableErrorCode.INVALID_SOURCE_DOCUMENT, "source document is empty");
        }
        if (hasUnsupportedBom(sourceBytes)) {
            return TimetableParseResult.fatal(
                    TimetableErrorCode.UNSUPPORTED_ENCODING, "source document must use UTF-8");
        }
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            String html = decoder.decode(ByteBuffer.wrap(sourceBytes)).toString();
            if (!html.isEmpty() && html.charAt(0) == '\uFEFF') {
                html = html.substring(1);
            }
            if (html.indexOf('\u0000') >= 0) {
                return TimetableParseResult.fatal(
                        TimetableErrorCode.UNSUPPORTED_ENCODING, "source document must use UTF-8");
            }
            return parse(html);
        } catch (CharacterCodingException exception) {
            return TimetableParseResult.fatal(
                    TimetableErrorCode.UNSUPPORTED_ENCODING, "source document must use UTF-8");
        }
    }

    public TimetableParseResult parse(String html) {
        if (html == null || html.isBlank()) {
            return TimetableParseResult.fatal(
                    TimetableErrorCode.INVALID_SOURCE_DOCUMENT, "source document is empty");
        }

        ScriptLookup lookup = findCoursesScript(html);
        if (lookup.malformedDocument()) {
            return TimetableParseResult.fatal(
                    TimetableErrorCode.INVALID_SOURCE_DOCUMENT, "HTML contains an unclosed script element");
        }
        if (lookup.assignmentStart() < 0) {
            return TimetableParseResult.fatal(
                    TimetableErrorCode.COURSES_NOT_FOUND,
                    "const COURSES declaration was not found in a script element");
        }

        String arrayJson = extractBalancedArray(lookup.scriptBody(), lookup.assignmentStart());
        if (arrayJson == null) {
            return TimetableParseResult.fatal(
                    TimetableErrorCode.COURSES_ARRAY_MALFORMED,
                    "COURSES must be a bracket-balanced JSON array");
        }

        JsonNode courses;
        try {
            courses = JSON.readTree(arrayJson);
        } catch (Exception exception) {
            return TimetableParseResult.fatal(
                    TimetableErrorCode.COURSES_ARRAY_MALFORMED,
                    "COURSES array is not valid JSON");
        }
        if (courses == null || !courses.isArray()) {
            return TimetableParseResult.fatal(
                    TimetableErrorCode.COURSES_ARRAY_MALFORMED,
                    "COURSES must be a JSON array");
        }

        List<TimetableSourceRow> parsedRows = new ArrayList<>();
        List<TimetableValidationError> validationErrors = new ArrayList<>();
        int sourceIndex = 0;
        for (JsonNode sourceRow : courses) {
            sourceIndex++;
            TimetableSourceRow parsed = validateRow(sourceRow, sourceIndex, validationErrors);
            if (parsed != null) {
                parsedRows.add(parsed);
            }
        }
        return new TimetableParseResult(parsedRows, validationErrors, sourceIndex);
    }

    private TimetableSourceRow validateRow(JsonNode node, int sourceIndex,
                                           List<TimetableValidationError> allErrors) {
        if (node == null || !node.isObject()) {
            allErrors.add(rowError(sourceIndex, null, TimetableErrorCode.INVALID_ROW_OBJECT,
                    "COURSES record must be a JSON object", node));
            return null;
        }

        List<TimetableValidationError> errors = new ArrayList<>();
        String rawN = stringField(node, "n", sourceIndex, errors);
        String rawT = stringField(node, "t", sourceIndex, errors);
        String rawC = stringField(node, "c", sourceIndex, errors);
        Integer rawP = integerField(node, "p", sourceIndex, errors);
        String rawW = stringField(node, "w", sourceIndex, errors);
        String rawRw = stringField(node, "rw", sourceIndex, errors);
        Integer rawD = integerField(node, "d", sourceIndex, errors);
        String rawS = stringField(node, "s", sourceIndex, errors);
        String rawH = stringField(node, "h", sourceIndex, errors);
        String rawL = stringField(node, "l", sourceIndex, errors);
        String rawG = stringField(node, "g", sourceIndex, errors);

        String displayName = rawN == null ? null : rawN.trim();
        if (displayName != null && displayName.isEmpty()) {
            errors.add(rowError(sourceIndex, "n", TimetableErrorCode.BLANK_NAME,
                    "course or activity display name must not be blank", rawN));
        }

        TimetableActivityType activityType = null;
        if (rawT != null) {
            activityType = switch (rawT.trim()) {
                case "本" -> TimetableActivityType.UNDERGRAD_COURSE;
                case "实" -> TimetableActivityType.PRACTICE;
                default -> {
                    errors.add(rowError(sourceIndex, "t", TimetableErrorCode.INVALID_ACTIVITY_TYPE,
                            "activity type must be 本 or 实", rawT));
                    yield null;
                }
            };
        }

        if (rawP != null && rawP < 0) {
            errors.add(rowError(sourceIndex, "p", TimetableErrorCode.INVALID_STUDENT_COUNT,
                    "student count must be an integer greater than or equal to zero", rawP));
        }

        List<Integer> weekNumbers = null;
        if (rawW != null) {
            try {
                weekNumbers = WeekMaskParser.parse(rawW);
            } catch (IllegalArgumentException exception) {
                String reason = rawW.length() == 18
                        ? "week mask must contain only binary digits"
                        : "week mask must contain exactly 18 binary digits; actual length = " + rawW.length();
                errors.add(rowError(sourceIndex, "w", TimetableErrorCode.INVALID_WEEK_MASK,
                        reason, rawW));
            }
        }

        if (rawD != null && (rawD < 1 || rawD > 7)) {
            errors.add(rowError(sourceIndex, "d", TimetableErrorCode.INVALID_WEEKDAY,
                    "weekday must be between 1 and 7", rawD));
        }

        SectionRange sections = null;
        if (rawS != null) {
            try {
                sections = SectionRangeParser.parse(rawS);
            } catch (IllegalArgumentException exception) {
                errors.add(rowError(sourceIndex, "s", TimetableErrorCode.INVALID_SECTION_RANGE,
                        exception.getMessage(), rawS));
            }
        }

        List<String> teacherNames = null;
        if (rawH != null) {
            try {
                teacherNames = TeacherFieldParser.parse(rawH);
            } catch (IllegalArgumentException exception) {
                errors.add(rowError(sourceIndex, "h", TimetableErrorCode.EMPTY_TEACHER,
                        exception.getMessage(), rawH));
            }
        }

        String locationName = rawL == null ? null : rawL.trim();
        if (locationName != null && locationName.isEmpty()) {
            errors.add(rowError(sourceIndex, "l", TimetableErrorCode.EMPTY_LOCATION,
                    "location must not be blank", rawL));
        }

        String locationGroup = rawG == null ? null : rawG.trim();
        if (locationGroup != null && !LOCATION_GROUPS.contains(locationGroup)) {
            errors.add(rowError(sourceIndex, "g", TimetableErrorCode.UNKNOWN_LOCATION_GROUP,
                    "unsupported location group", rawG));
        }

        if (!errors.isEmpty()) {
            allErrors.addAll(errors);
            return null;
        }

        return new TimetableSourceRow(
                sourceIndex,
                rawN, rawT, rawC, rawP, rawW, rawRw, rawD, rawS, rawH, rawL, rawG,
                node.toString(),
                displayName, activityType, rawC.trim(), rawP,
                rawW, weekNumbers, rawRw, rawD,
                sections.startSection(), sections.endSection(), teacherNames,
                locationName, locationGroup);
    }

    private String stringField(JsonNode object, String field, int sourceIndex,
                               List<TimetableValidationError> errors) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) {
            errors.add(rowError(sourceIndex, field, TimetableErrorCode.MISSING_REQUIRED_FIELD,
                    "required source field is missing", null));
            return null;
        }
        if (!value.isString()) {
            errors.add(rowError(sourceIndex, field, TimetableErrorCode.INVALID_FIELD_TYPE,
                    "source field must be a string", value));
            return null;
        }
        return value.stringValue();
    }

    private Integer integerField(JsonNode object, String field, int sourceIndex,
                                 List<TimetableValidationError> errors) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) {
            errors.add(rowError(sourceIndex, field, TimetableErrorCode.MISSING_REQUIRED_FIELD,
                    "required source field is missing", null));
            return null;
        }
        if (!value.isIntegralNumber() || value.longValue() < Integer.MIN_VALUE
                || value.longValue() > Integer.MAX_VALUE) {
            errors.add(rowError(sourceIndex, field, TimetableErrorCode.INVALID_FIELD_TYPE,
                    "source field must be a 32-bit integer", value));
            return null;
        }
        return value.intValue();
    }

    private TimetableValidationError rowError(int sourceIndex, String field,
                                               TimetableErrorCode code, String reason,
                                               Object rawValue) {
        return TimetableValidationError.row(
                sourceIndex, field, code, reason, safeRawValue(rawValue));
    }

    private String safeRawValue(Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        String text;
        if (rawValue instanceof JsonNode node && node.isString()) {
            text = node.stringValue();
        } else {
            text = String.valueOf(rawValue);
        }
        StringBuilder safe = new StringBuilder(Math.min(text.length(), RAW_VALUE_LIMIT));
        for (int i = 0; i < text.length() && safe.length() < RAW_VALUE_LIMIT; i++) {
            char character = text.charAt(i);
            safe.append(Character.isISOControl(character) ? '\uFFFD' : character);
        }
        if (text.length() > safe.length()) {
            safe.append('…');
        }
        return safe.toString();
    }

    private ScriptLookup findCoursesScript(String html) {
        int cursor = 0;
        while (true) {
            int scriptOpen = findScriptOpen(html, cursor);
            if (scriptOpen < 0) {
                return ScriptLookup.notFound();
            }
            int bodyStart = findTagEnd(html, scriptOpen + 7);
            if (bodyStart < 0) {
                return ScriptLookup.malformed();
            }
            int scriptClose = indexOfIgnoreCase(html, "</script", bodyStart);
            if (scriptClose < 0) {
                return ScriptLookup.malformed();
            }
            int closeEnd = findTagEnd(html, scriptClose + 8);
            if (closeEnd < 0) {
                return ScriptLookup.malformed();
            }
            String body = html.substring(bodyStart, scriptClose);
            int assignmentStart = findCoursesAssignment(body);
            if (assignmentStart >= 0) {
                return new ScriptLookup(body, assignmentStart, false);
            }
            cursor = closeEnd;
        }
    }

    private int findScriptOpen(String html, int fromIndex) {
        int candidate = fromIndex;
        while ((candidate = indexOfIgnoreCase(html, "<script", candidate)) >= 0) {
            int after = candidate + 7;
            if (after >= html.length() || Character.isWhitespace(html.charAt(after))
                    || html.charAt(after) == '>' || html.charAt(after) == '/') {
                return candidate;
            }
            candidate = after;
        }
        return -1;
    }

    private int findTagEnd(String html, int fromIndex) {
        char quote = 0;
        for (int i = fromIndex; i < html.length(); i++) {
            char current = html.charAt(i);
            if (quote != 0) {
                if (current == quote) {
                    quote = 0;
                }
            } else if (current == '\'' || current == '"') {
                quote = current;
            } else if (current == '>') {
                return i + 1;
            }
        }
        return -1;
    }

    private int findCoursesAssignment(String script) {
        int cursor = 0;
        while (cursor < script.length()) {
            char current = script.charAt(cursor);
            if (current == '"' || current == '\'' || current == '`') {
                cursor = skipQuoted(script, cursor, current);
            } else if (startsWith(script, cursor, "//")) {
                cursor = skipLineComment(script, cursor + 2);
            } else if (startsWith(script, cursor, "/*")) {
                cursor = skipBlockComment(script, cursor + 2);
            } else if (isIdentifierStart(current)) {
                int end = skipIdentifier(script, cursor);
                if (script.substring(cursor, end).equals("const")) {
                    int nameStart = skipTrivia(script, end);
                    int nameEnd = nameStart < script.length() && isIdentifierStart(script.charAt(nameStart))
                            ? skipIdentifier(script, nameStart) : nameStart;
                    if (script.substring(nameStart, nameEnd).equals("COURSES")) {
                        int equals = skipTrivia(script, nameEnd);
                        if (equals < script.length() && script.charAt(equals) == '=') {
                            return skipTrivia(script, equals + 1);
                        }
                    }
                }
                cursor = end;
            } else {
                cursor++;
            }
        }
        return -1;
    }

    private int skipTrivia(String source, int fromIndex) {
        int cursor = fromIndex;
        boolean changed;
        do {
            changed = false;
            while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) {
                cursor++;
                changed = true;
            }
            if (startsWith(source, cursor, "//")) {
                cursor = skipLineComment(source, cursor + 2);
                changed = true;
            } else if (startsWith(source, cursor, "/*")) {
                cursor = skipBlockComment(source, cursor + 2);
                changed = true;
            }
        } while (changed);
        return cursor;
    }

    private String extractBalancedArray(String script, int arrayStart) {
        if (arrayStart < 0 || arrayStart >= script.length() || script.charAt(arrayStart) != '[') {
            return null;
        }
        int depth = 0;
        int cursor = arrayStart;
        while (cursor < script.length()) {
            char current = script.charAt(cursor);
            if (current == '"' || current == '\'' || current == '`') {
                cursor = skipQuoted(script, cursor, current);
                continue;
            }
            if (startsWith(script, cursor, "//")) {
                cursor = skipLineComment(script, cursor + 2);
                continue;
            }
            if (startsWith(script, cursor, "/*")) {
                cursor = skipBlockComment(script, cursor + 2);
                continue;
            }
            if (current == '[') {
                depth++;
            } else if (current == ']') {
                depth--;
                if (depth == 0) {
                    return script.substring(arrayStart, cursor + 1);
                }
            }
            cursor++;
        }
        return null;
    }

    private int skipQuoted(String source, int quoteStart, char quote) {
        int cursor = quoteStart + 1;
        boolean escaped = false;
        while (cursor < source.length()) {
            char current = source.charAt(cursor++);
            if (escaped) {
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == quote) {
                break;
            }
        }
        return cursor;
    }

    private int skipLineComment(String source, int fromIndex) {
        int newline = source.indexOf('\n', fromIndex);
        return newline < 0 ? source.length() : newline + 1;
    }

    private int skipBlockComment(String source, int fromIndex) {
        int end = source.indexOf("*/", fromIndex);
        return end < 0 ? source.length() : end + 2;
    }

    private int skipIdentifier(String source, int fromIndex) {
        int cursor = fromIndex + 1;
        while (cursor < source.length() && isIdentifierPart(source.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private boolean isIdentifierStart(char character) {
        return Character.isJavaIdentifierStart(character);
    }

    private boolean isIdentifierPart(char character) {
        return Character.isJavaIdentifierPart(character);
    }

    private boolean startsWith(String source, int offset, String expected) {
        return offset >= 0 && offset + expected.length() <= source.length()
                && source.startsWith(expected, offset);
    }

    private int indexOfIgnoreCase(String source, String expected, int fromIndex) {
        String expectedLower = expected.toLowerCase(Locale.ROOT);
        int lastStart = source.length() - expected.length();
        for (int i = Math.max(0, fromIndex); i <= lastStart; i++) {
            if (source.regionMatches(true, i, expectedLower, 0, expected.length())) {
                return i;
            }
        }
        return -1;
    }

    private boolean hasUnsupportedBom(byte[] bytes) {
        return startsWith(bytes, 0xFF, 0xFE)
                || startsWith(bytes, 0xFE, 0xFF)
                || startsWith(bytes, 0x00, 0x00, 0xFE, 0xFF)
                || startsWith(bytes, 0xFF, 0xFE, 0x00, 0x00);
    }

    private boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private record ScriptLookup(String scriptBody, int assignmentStart, boolean malformedDocument) {
        private static ScriptLookup notFound() {
            return new ScriptLookup("", -1, false);
        }

        private static ScriptLookup malformed() {
            return new ScriptLookup("", -1, true);
        }
    }
}
