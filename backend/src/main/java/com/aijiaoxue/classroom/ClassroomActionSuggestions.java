package com.aijiaoxue.classroom;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Pure mapping from a validated generated report to the teacher's action suggestions
 * (S2-06 查看 AI 行动建议 / S2-07 查看建议的评价来源 / S2-13 建议不可用时给出原因).
 *
 * <p>No AI call and no re-generation happens here. The AI-produced interpretations of the
 * confirmed report are the suggestion source, and every suggestion keeps its evidence
 * references so the teacher can check which classroom evidence a suggestion came from.
 * An entry without usable suggestion text is not actionable and is dropped, so a report
 * that only carries hypotheses degrades to an explicit reason instead of an empty list.
 */
public final class ClassroomActionSuggestions {
    public static final String REASON_REPORT_NOT_READY = "REPORT_NOT_READY";
    public static final String REASON_NO_INTERPRETATIONS = "NO_INTERPRETATIONS";

    private ClassroomActionSuggestions() {}

    public record Evidence(String itemType, String itemId) {}

    public record Suggestion(int index, String hypothesis, List<String> alternatives, String suggestion,
                             List<Evidence> evidence) {}

    public record Result(boolean available, String reasonCode, List<Suggestion> suggestions,
                         Long classSessionId) {
        /**
         * 建议本身不携带媒体上下文（它只来自报告 JSON）。服务层在返回前补上 classSessionId，
         * 教师端才能从一条建议跳到证据回看页并按证据定位（S2-11）。
         */
        Result withClassSession(Long classSessionId) {
            return new Result(available, reasonCode, suggestions, classSessionId);
        }
    }

    public static Result fromReport(JsonNode report) {
        if (report == null || !report.isObject()) return new Result(false, REASON_REPORT_NOT_READY, List.of(), null);
        JsonNode interpretations = report.get("interpretations");
        if (interpretations == null || !interpretations.isArray() || interpretations.size() == 0)
            return new Result(false, REASON_NO_INTERPRETATIONS, List.of(), null);
        List<Suggestion> suggestions = new ArrayList<>();
        for (int index = 0; index < interpretations.size(); index++) {
            JsonNode entry = interpretations.get(index);
            if (entry == null || !entry.isObject()) continue;
            String suggestion = text(entry, "suggestion");
            if (suggestion == null || suggestion.isBlank()) continue;
            suggestions.add(new Suggestion(index, text(entry, "hypothesis"), alternatives(entry), suggestion,
                    evidence(entry)));
        }
        if (suggestions.isEmpty()) return new Result(false, REASON_NO_INTERPRETATIONS, List.of(), null);
        return new Result(true, null, List.copyOf(suggestions), null);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static List<String> alternatives(JsonNode entry) {
        JsonNode values = entry.get("alternatives");
        if (values == null || !values.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode value : values) if (value != null && value.isTextual()) out.add(value.asText());
        return List.copyOf(out);
    }

    private static List<Evidence> evidence(JsonNode entry) {
        JsonNode refs = entry.get("evidenceRefs");
        if (refs == null || !refs.isArray()) return List.of();
        List<Evidence> out = new ArrayList<>();
        for (JsonNode ref : refs) {
            if (ref == null || !ref.isObject()) continue;
            String itemType = text(ref, "itemType");
            String itemId = text(ref, "itemId");
            if (itemType != null && itemId != null) out.add(new Evidence(itemType, itemId));
        }
        return List.copyOf(out);
    }
}
