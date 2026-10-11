package com.aijiaoxue.supervision.evaluation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * User-oriented DAT-04 text adapter. It accepts the shapes people commonly paste
 * from spreadsheets, chat messages and simple TXT files instead of requiring one
 * CSV dialect. Every non-blank line is still one evaluation so errors remain
 * locatable and the transactional import can stay all-or-nothing.
 */
public final class EvaluationTextParser {
    private static final Pattern LABELED_FIELD = Pattern.compile(
            "(?i)(offeringId|开课(?:编号|ID)|状态|status|completedAt|完成时间|评价时间)\\s*[:：=]\\s*(.*?)" +
                    "(?=(?:\\s*[,，;；|\\t]\\s*|\\s+)(?:offeringId|开课(?:编号|ID)|状态|status|completedAt|完成时间|评价时间)\\s*[:：=]|$)");
    private static final Pattern SPACE_ROW = Pattern.compile(
            "^(?:开课(?:编号|ID)?\\s*)?(\\d+)\\s+(?:状态\\s*)?(\\S+)(?:\\s+(?:完成时间\\s*)?(.+))?$");

    public sealed interface ParseEvent permits Parsed, Fatal {}

    public record Parsed(List<EvaluationTextRow> rows) implements ParseEvent {}

    public record Fatal(String errorCode, String reason) implements ParseEvent {}

    private EvaluationTextParser() {}

    public static String canonicalize(String content) {
        if (content == null) return "";
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        if (!normalized.isEmpty() && normalized.charAt(0) == '\ufeff') normalized = normalized.substring(1);
        String[] lines = normalized.split("\n", -1);
        int first = 0;
        int last = lines.length;
        while (first < last && lines[first].isBlank()) first++;
        while (last > first && lines[last - 1].isBlank()) last--;
        var result = new ArrayList<String>();
        for (int i = first; i < last; i++) result.add(lines[i].stripTrailing());
        return String.join("\n", result);
    }

    public static ParseEvent parse(String content) {
        String text = canonicalize(content);
        if (text.isBlank()) return new Fatal("EMPTY_TEXT", "没有可导入的评价文本");

        String[] lines = text.split("\n", -1);
        List<EvaluationTextRow> rows = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (isHeader(line)) continue;
            String[] fields = labeledFields(line);
            if (fields == null) fields = delimitedFields(line);
            if (fields == null) fields = spaceFields(line);
            if (fields == null) {
                return new Fatal("UNRECOGNIZED_ROW", "第" + (i + 1) + "行无法识别；请至少包含开课编号和评价状态");
            }
            rows.add(new EvaluationTextRow(i + 1, fields[0], fields[1], fields[2]));
        }
        if (rows.isEmpty()) return new Fatal("NO_DATA_ROWS", "文本中没有评价记录");
        return new Parsed(List.copyOf(rows));
    }

    private static boolean isHeader(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        boolean offering = lower.contains("offeringid") || lower.contains("开课编号") || lower.contains("开课id");
        boolean status = lower.contains("status") || lower.contains("状态");
        return offering && status && !lower.matches(".*\\d+.*");
    }

    private static String[] labeledFields(String line) {
        Matcher matcher = LABELED_FIELD.matcher(line);
        Map<String, String> values = new LinkedHashMap<>();
        while (matcher.find()) values.put(key(matcher.group(1)), matcher.group(2).trim());
        if (!values.containsKey("offeringId") || !values.containsKey("status")) return null;
        return new String[]{values.get("offeringId"), values.get("status"), values.getOrDefault("completedAt", "")};
    }

    private static String key(String label) {
        String lower = label.toLowerCase(Locale.ROOT);
        if (lower.equals("status") || label.equals("状态")) return "status";
        if (lower.equals("completedat") || label.equals("完成时间") || label.equals("评价时间")) return "completedAt";
        return "offeringId";
    }

    private static String[] delimitedFields(String line) {
        String delimiter = line.contains("\t") ? "\\t"
                : line.contains("|") ? "\\|"
                : line.matches(".*[,，;；].*") ? "[,，;；]" : null;
        if (delimiter == null) return null;
        String[] split = line.split("\\s*" + delimiter + "\\s*", -1);
        if (split.length < 2 || split.length > 3) return null;
        return new String[]{split[0], split[1], split.length == 3 ? split[2] : ""};
    }

    private static String[] spaceFields(String line) {
        Matcher matcher = SPACE_ROW.matcher(line);
        if (!matcher.matches()) return null;
        return new String[]{matcher.group(1), matcher.group(2), matcher.group(3) == null ? "" : matcher.group(3)};
    }
}
