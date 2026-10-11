package com.aijiaoxue.classroom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ClassroomAnalysisEgressPacketTest {
    private final ObjectMapper mapper=new ObjectMapper();

    @Test void allowlistsOnlyReportEvidenceAndRedactsCommonIdentifiers() throws Exception {
        String raw="张三老师来自北京大学，电话13800138000，邮箱 zhang@example.com，编号123456789012，详见https://example.com/private";
        var snapshot=fixture("seg-1",raw);
        var packet=ClassroomAnalysisEgressPacket.build(mapper,snapshot,7);
        String json=mapper.writeValueAsString(packet);
        assertEquals("classroom-report-egress-v1",packet.version());
        assertEquals(7,packet.runId());
        assertEquals("input-hash",packet.inputSha256());
        assertEquals(raw,snapshot.input().transcript().get(0).text());
        for(String privateValue:List.of("张三老师","北京大学","13800138000","zhang@example.com",
                "123456789012","https://example.com/private","media-secret","envelope-secret","quality-secret"))
            assertFalse(json.contains(privateValue),privateValue);
        for(String marker:List.of("[人员]","[学校]","[电话]","[邮箱]","[编号]","[链接]"))
            assertTrue(json.contains(marker),marker);
        assertEquals("seg-1",packet.evidenceIndex().get(0).itemId());
        assertEquals(11,packet.transcriptResultId());
    }

    @Test void failsClosedOnUnsafeEvidenceIdAndOversizedText() {
        assertThrows(IllegalArgumentException.class,
                ()->ClassroomAnalysisEgressPacket.build(mapper,fixture("张三", "讲解递归"),7));
        assertThrows(IllegalArgumentException.class,
                ()->ClassroomAnalysisEgressPacket.build(mapper,fixture("seg-1", "讲解".repeat(20_000)),7));
    }

    @Test void replacesContextualNamesWithoutRemovingOrdinaryTeachingTerms() throws Exception {
        String raw="请李四，刚才张三回答问题，王小明提问，欧阳娜娜发言。李老师、王教授和Dr. Smith讨论，Alice said hello。"
                +"赵六说话，张量表示学习与递归算法。";
        var snapshot=fixture("seg-1",raw);
        var packet=ClassroomAnalysisEgressPacket.build(mapper,snapshot,7);
        String sent=packet.transcript().get(0).text();
        for(String name:List.of("李四","张三","王小明","欧阳娜娜","李老师","王教授","Smith","Alice","赵六"))
            assertFalse(sent.contains(name),name+": "+sent);
        assertTrue(sent.contains("请[人员]"));
        assertTrue(sent.contains("[人员]回答问题"));
        assertTrue(sent.contains("[人员]提问"));
        assertTrue(sent.contains("[人员]说话"));
        assertTrue(sent.contains("张量表示学习与递归算法"));
        assertEquals(raw,snapshot.input().transcript().get(0).text());
    }

    @Test void samplesLongClassAcrossTimeWithoutChangingHistoricalEvidence() throws Exception {
        var snapshot=longFixture(8);
        var packet=ClassroomAnalysisEgressPacket.build(mapper,snapshot,7);
        assertTrue(mapper.writeValueAsBytes(packet).length<=60_000);
        assertEquals("seg-0",packet.transcript().get(0).id());
        assertEquals("seg-239",packet.transcript().get(packet.transcript().size()-1).id());
        assertTrue(packet.transcript().stream().anyMatch(item->item.startMs()>=1_000_000
                &&item.startMs()<=1_400_000));
        assertTrue(packet.transcript().size()<snapshot.input().transcript().size());
        assertTrue(packet.headUpWindows().size()<snapshot.input().headUpWindows().size());
        assertEquals(packet.transcript().size()+packet.headUpWindows().size(),packet.evidenceIndex().size());
        for(var segment:packet.transcript())
            assertTrue(packet.evidenceIndex().stream().anyMatch(item->"SEGMENT".equals(item.itemType())
                    &&item.itemId().equals(segment.id())));
        for(var window:packet.headUpWindows())
            assertTrue(packet.evidenceIndex().stream().anyMatch(item->"WINDOW".equals(item.itemType())
                    &&item.itemId().equals(window.id())));
        assertTrue(packet.headUpWindows().stream().anyMatch(window->packet.headUpWindows().stream()
                .anyMatch(next->next.startMs()==window.endMs())));
        assertTrue(packet.limitations().stream().anyMatch(value->value.contains("抽样片段")));
        assertTrue(packet.limitations().stream().anyMatch(value->value.contains("抽样证据")));
        assertEquals(mapper.writeValueAsString(packet),mapper.writeValueAsString(
                ClassroomAnalysisEgressPacket.build(mapper,snapshot,7)));
        assertEquals(240,snapshot.input().transcript().size());
        assertEquals(240,snapshot.input().headUpWindows().size());

        var last=packet.transcript().get(packet.transcript().size()-1);
        String draft="""
                {"reportVersion":"1.0","runId":7,"inputSha256":"input-hash",
                 "topics":[{"startMs":%d,"endMs":%d,"summary":"讲解递归与复杂度分析",
                   "evidenceRefs":[{"resultId":11,"itemType":"SEGMENT","itemId":"%s"}]}],
                 "changes":[],"interpretations":[],"limitations":["仅分析抽样片段"]}
                """.formatted(last.startMs(),last.endMs(),last.id());
        var validated=ClassroomAnalysisOutputValidator.parse(mapper,snapshot,7,draft);
        assertEquals(1,validated.topics().size());
        var normalized=ClassroomAnalysisOutputValidator.withSamplingLimitations(validated,packet);
        assertTrue(normalized.limitations().stream().anyMatch(value->value.contains("转写仅提供跨课堂抽样")));
        assertTrue(normalized.limitations().stream().anyMatch(value->value.contains("观测窗口仅提供跨课堂抽样")));
        var many=new ClassroomAnalysisOutputValidator.Draft(validated.runId(),validated.inputSha256(),
                validated.topics(),validated.changes(),validated.interpretations(),
                java.util.stream.IntStream.range(0,12).mapToObj(i->"局限"+i).toList());
        var bounded=ClassroomAnalysisOutputValidator.withSamplingLimitations(many,packet);
        assertEquals(12,bounded.limitations().size());
        assertTrue(bounded.limitations().get(0).contains("抽样"));
    }

    @Test void reducesSampleFurtherWhenExcerptsAreLong() throws Exception {
        var packet=ClassroomAnalysisEgressPacket.build(mapper,longFixture(120),7);
        assertTrue(mapper.writeValueAsBytes(packet).length<=60_000);
        assertTrue(packet.transcript().size()<24);
        assertEquals("seg-0",packet.transcript().get(0).id());
        assertEquals("seg-239",packet.transcript().get(packet.transcript().size()-1).id());
    }

    private static ClassroomAnalysisInputSnapshotService.Snapshot longFixture(int repetitions) {
        var transcript=new ClassroomAnalysisInputSnapshotService.SourceResult(11,101,1,"envelope-secret");
        var headUp=new ClassroomAnalysisInputSnapshotService.SourceResult(22,202,1,"envelope-secret");
        var full=new ClassroomAnalysisInputSnapshotService.Coverage(2_400_000,1);
        var completeness=new ClassroomAnalysisInputSnapshotService.Completeness(full,full,full,full);
        var segments=new java.util.ArrayList<ClassroomAnalysisView.SegmentView>();
        var windows=new java.util.ArrayList<ClassroomAnalysisInputSnapshotService.WindowFact>();
        var index=new java.util.ArrayList<ClassroomAnalysisInputSnapshotService.EvidenceItem>();
        for(int i=0;i<240;i++) {
            long start=i*10_000L,end=start+10_000;
            String segmentId="seg-"+i,windowId="win-"+i;
            segments.add(new ClassroomAnalysisView.SegmentView(segmentId,start,end,
                    "讲解递归与复杂度分析".repeat(repetitions)));
            windows.add(new ClassroomAnalysisInputSnapshotService.WindowFact(windowId,start,end,start+5_000,
                    "VALID",null,3,4,0.75));
            index.add(new ClassroomAnalysisInputSnapshotService.EvidenceItem(11,"SEGMENT",segmentId,start,end));
            index.add(new ClassroomAnalysisInputSnapshotService.EvidenceItem(22,"WINDOW",windowId,start,end));
        }
        var input=new ClassroomAnalysisInputSnapshotService.RawInput("classroom-analysis-input-v4",1,2,
                "media-secret",2_400_000,transcript,headUp,
                new ClassroomAnalysisInputSnapshotService.Statistics(0.75,1),completeness,
                List.of(new ClassroomAnalysisRepository.ProcessedRange(0,2_400_000)),
                List.of(new ClassroomAnalysisRepository.ProcessedRange(0,2_400_000)),
                List.copyOf(segments),List.copyOf(windows),List.copyOf(index));
        return new ClassroomAnalysisInputSnapshotService.Snapshot("input-hash","fixture-json",input);
    }

    private static ClassroomAnalysisInputSnapshotService.Snapshot fixture(String id,String text) {
        var transcript=new ClassroomAnalysisInputSnapshotService.SourceResult(11,101,1,"envelope-secret");
        var headUp=new ClassroomAnalysisInputSnapshotService.SourceResult(22,202,1,"envelope-secret");
        var full=new ClassroomAnalysisInputSnapshotService.Coverage(20_000,1);
        var completeness=new ClassroomAnalysisInputSnapshotService.Completeness(full,full,full,full);
        var input=new ClassroomAnalysisInputSnapshotService.RawInput("classroom-analysis-input-v4",1,2,
                "media-secret",20_000,transcript,headUp,
                new ClassroomAnalysisInputSnapshotService.Statistics(0.75,1),completeness,
                List.of(new ClassroomAnalysisRepository.ProcessedRange(0,20_000)),
                List.of(new ClassroomAnalysisRepository.ProcessedRange(0,20_000)),
                List.of(new ClassroomAnalysisView.SegmentView(id,0,20_000,text)),
                List.of(new ClassroomAnalysisInputSnapshotService.WindowFact("win-1",0,20_000,10_000L,
                        "VALID","quality-secret",3,4,0.75)),
                List.of(new ClassroomAnalysisInputSnapshotService.EvidenceItem(11,"SEGMENT",id,0,20_000),
                        new ClassroomAnalysisInputSnapshotService.EvidenceItem(22,"WINDOW","win-1",0,20_000)));
        return new ClassroomAnalysisInputSnapshotService.Snapshot("input-hash","fixture-json",input);
    }
}
