package com.aijiaoxue.classroom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ClassroomAnalysisOutputValidatorTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final ClassroomAnalysisInputSnapshotService.Snapshot snapshot=fixture();

    @Test void acceptsBoundDraftAndComputesChangeWithoutModelNumbers() {
        var draft=ClassroomAnalysisOutputValidator.parse(mapper,snapshot,7,valid());
        assertEquals(7,draft.runId());
        assertEquals("input-hash",draft.inputSha256());
        assertEquals(1,draft.topics().size());
        assertEquals(-25.0,draft.changes().get(0).deltaPercentagePoints(),1e-9);
        assertEquals(1,draft.interpretations().size());
    }

    @Test void rejectsWrongRunInputSchemaUnknownFieldsAndInventedNumbers() {
        rejects(valid().replace("\"runId\":7","\"runId\":8"));
        rejects(valid().replace("input-hash","another-hash"));
        rejects(valid().replace("\"reportVersion\":\"1.0\"","\"reportVersion\":\"2.0\""));
        rejects(valid().replace("\"topics\":[","\"meanHeadUpRate\":0.9,\"topics\":["));
        rejects(valid().replace("\"fromWindowId\":\"win-1\"",
                "\"deltaPercentagePoints\":99,\"fromWindowId\":\"win-1\""));
        rejects("not-json");
        rejects(" ".repeat(65_537));
    }

    @Test void rejectsForgedEvidenceTimeAndUnsafeProse() {
        rejects(valid().replace("\"itemId\":\"seg-1\"","\"itemId\":\"missing\""));
        rejects(valid().replace("\"resultId\":11,\"itemType\":\"SEGMENT\"",
                "\"resultId\":22,\"itemType\":\"SEGMENT\""));
        rejects(valid().replace("\"startMs\":0,\"endMs\":10000,\"summary\"",
                "\"startMs\":10000,\"endMs\":20000,\"summary\""));
        rejects(valid().replace("\"toWindowId\":\"win-2\"","\"toWindowId\":\"win-3\""));
        rejects(valid().replace("\"summary\":\"讲解递归\"",
                "\"summary\":\"https://example.test\""));
        rejects(valid().replace("\"summary\":\"讲解递归\"",
                "\"summary\":\"抬头率上升99%\""));
        rejects(valid().replace("\"summary\":\"讲解递归\"",
                "\"summary\":\"抬头率达到九成\""));
        rejects(valid().replace("\"summary\":\"讲解递归\"",
                "\"summary\":\"详见example.com\""));
        rejects(valid().replace("\"summary\":\"讲解递归\"",
                "\"summary\":\"[点击](/report)\""));
        rejects(valid().replace("\"summary\":\"讲解递归\"",
                "\"summary\":\"javascript:alert(1)\""));
        rejects(valid().replace("\"evidenceRefs\":[{\"resultId\":11,\"itemType\":\"SEGMENT\",\"itemId\":\"seg-1\"}]",
                "\"evidenceRefs\":[]"));
    }

    private void rejects(String json) {
        assertThrows(IllegalArgumentException.class,
                ()->ClassroomAnalysisOutputValidator.parse(mapper,snapshot,7,json));
    }

    private static ClassroomAnalysisInputSnapshotService.Snapshot fixture() {
        var transcript=new ClassroomAnalysisInputSnapshotService.SourceResult(11,101,1,"transcript-hash");
        var headUp=new ClassroomAnalysisInputSnapshotService.SourceResult(22,202,1,"head-up-hash");
        var full=new ClassroomAnalysisInputSnapshotService.Coverage(20_000,1);
        var completeness=new ClassroomAnalysisInputSnapshotService.Completeness(full,full,full,full);
        var input=new ClassroomAnalysisInputSnapshotService.RawInput("classroom-analysis-input-v4",1,2,
                "media-hash",20_000,transcript,headUp,
                new ClassroomAnalysisInputSnapshotService.Statistics(0.625,1),completeness,
                List.of(new ClassroomAnalysisRepository.ProcessedRange(0,20_000)),
                List.of(new ClassroomAnalysisRepository.ProcessedRange(0,20_000)),
                List.of(new ClassroomAnalysisView.SegmentView("seg-1",0,10_000,"讲解递归")),
                List.of(new ClassroomAnalysisInputSnapshotService.WindowFact("win-1",0,10_000,0L,
                                "VALID",null,3,4,0.75),
                        new ClassroomAnalysisInputSnapshotService.WindowFact("win-2",10_000,20_000,10_000L,
                                "VALID",null,2,4,0.5)),
                List.of(new ClassroomAnalysisInputSnapshotService.EvidenceItem(11,"SEGMENT","seg-1",0,10_000),
                        new ClassroomAnalysisInputSnapshotService.EvidenceItem(22,"WINDOW","win-1",0,10_000),
                        new ClassroomAnalysisInputSnapshotService.EvidenceItem(22,"WINDOW","win-2",10_000,20_000)));
        return new ClassroomAnalysisInputSnapshotService.Snapshot("input-hash","fixture-json",input);
    }

    private static String valid() {
        return """
                {"reportVersion":"1.0","runId":7,"inputSha256":"input-hash",
                 "topics":[{"startMs":0,"endMs":10000,"summary":"讲解递归",
                   "evidenceRefs":[{"resultId":11,"itemType":"SEGMENT","itemId":"seg-1"}]}],
                 "changes":[{"fromWindowId":"win-1","toWindowId":"win-2","startMs":0,"endMs":20000,
                   "evidenceRefs":[{"resultId":22,"itemType":"WINDOW","itemId":"win-1"},
                                   {"resultId":22,"itemType":"WINDOW","itemId":"win-2"}]}],
                 "interpretations":[{"hypothesis":"可能与课堂活动有关","alternatives":["也可能是视角变化"],
                   "suggestion":"结合视频核查",
                   "evidenceRefs":[{"resultId":11,"itemType":"SEGMENT","itemId":"seg-1"},
                                   {"resultId":22,"itemType":"WINDOW","itemId":"win-1"}]}],
                 "limitations":["不能推断理解程度"]}
                """;
    }
}
