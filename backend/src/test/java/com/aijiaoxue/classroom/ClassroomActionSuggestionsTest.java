package com.aijiaoxue.classroom;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S2-06/S2-07/S2-13: the suggestion mapping is pure, so the availability rules and the
 * evidence links are pinned here without a database or an AI call.
 */
class ClassroomActionSuggestionsTest {
    private static JsonNode json(String value) {
        return JsonMapper.builder().build().readTree(value);
    }

    @Test
    void suggestionKeepsItsReportIndexAndEvidence() {
        var result = ClassroomActionSuggestions.fromReport(json("""
                {"interpretations":[
                  {"hypothesis":"提问后等待时间偏短","alternatives":["学生未理解","问题过难"],
                   "suggestion":"提问后停顿三秒再点名",
                   "evidenceRefs":[{"resultId":7,"itemType":"SEGMENT","itemId":"seg-3"},
                                   {"resultId":7,"itemType":"WINDOW","itemId":"win-2"}]}
                ]}"""));

        assertTrue(result.available());
        assertNull(result.reasonCode());
        assertEquals(1, result.suggestions().size());
        var suggestion = result.suggestions().get(0);
        assertEquals(0, suggestion.index());
        assertEquals("提问后停顿三秒再点名", suggestion.suggestion());
        assertEquals("提问后等待时间偏短", suggestion.hypothesis());
        assertEquals(2, suggestion.alternatives().size());
        assertEquals(2, suggestion.evidence().size());
        assertEquals(new ClassroomActionSuggestions.Evidence("SEGMENT", "seg-3"), suggestion.evidence().get(0));
        assertEquals(new ClassroomActionSuggestions.Evidence("WINDOW", "win-2"), suggestion.evidence().get(1));
    }

    @Test
    void entryWithoutSuggestionTextIsDroppedButTheOriginalIndexIsKept() {
        var result = ClassroomActionSuggestions.fromReport(json("""
                {"interpretations":[
                  {"hypothesis":"只有假设没有行动","alternatives":[],"suggestion":"   ","evidenceRefs":[]},
                  {"hypothesis":"第二个","alternatives":[],"suggestion":"下一次先给示例","evidenceRefs":[]}
                ]}"""));

        assertTrue(result.available());
        assertEquals(1, result.suggestions().size());
        assertEquals(1, result.suggestions().get(0).index());
        assertEquals("下一次先给示例", result.suggestions().get(0).suggestion());
        assertTrue(result.suggestions().get(0).evidence().isEmpty());
    }

    @Test
    void reportWithoutInterpretationsDegradesToAnExplicitReason() {
        var missing = ClassroomActionSuggestions.fromReport(json("{\"topics\":[]}"));
        assertFalse(missing.available());
        assertEquals(ClassroomActionSuggestions.REASON_NO_INTERPRETATIONS, missing.reasonCode());
        assertTrue(missing.suggestions().isEmpty());

        var empty = ClassroomActionSuggestions.fromReport(json("{\"interpretations\":[]}"));
        assertFalse(empty.available());
        assertEquals(ClassroomActionSuggestions.REASON_NO_INTERPRETATIONS, empty.reasonCode());

        var notActionable = ClassroomActionSuggestions.fromReport(json(
                "{\"interpretations\":[{\"hypothesis\":\"只有假设\"}]}"));
        assertFalse(notActionable.available());
        assertEquals(ClassroomActionSuggestions.REASON_NO_INTERPRETATIONS, notActionable.reasonCode());
    }

    @Test
    void reportThatIsNotReadyIsDistinguishedFromAReportWithoutSuggestions() {
        var absent = ClassroomActionSuggestions.fromReport(null);
        assertFalse(absent.available());
        assertEquals(ClassroomActionSuggestions.REASON_REPORT_NOT_READY, absent.reasonCode());

        var scalar = ClassroomActionSuggestions.fromReport(json("\"not-a-report\""));
        assertFalse(scalar.available());
        assertEquals(ClassroomActionSuggestions.REASON_REPORT_NOT_READY, scalar.reasonCode());
    }
}
