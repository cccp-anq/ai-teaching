package com.aijiaoxue.classroom;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.ToLongFunction;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;

/** An explicit allowlist for future report-model requests; raw snapshots never leave this boundary. */
final class ClassroomAnalysisEgressPacket {
    static final String VERSION="classroom-report-egress-v1";
    private static final int MAX_BYTES=60_000;
    private static final Pattern SAFE_ID=Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern EMAIL=Pattern.compile("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}");
    private static final Pattern URL=Pattern.compile("(?i)(?:https?://|www\\.)[^\\s，。；;]+|(?:[A-Z0-9-]+\\.)+[A-Z]{2,}(?:/[^\\s，。；;]*)?");
    private static final Pattern PHONE=Pattern.compile("(?<!\\d)(?:(?:\\+?86[- ]?)?1[3-9](?:[- ]?\\d){9})(?!\\d)");
    private static final Pattern NUMBER_ID=Pattern.compile("(?<!\\d)\\d{8,20}(?!\\d)");
    private static final Pattern SCHOOL=Pattern.compile("[\\p{IsHan}]{2,12}(?:大学|学院|学校|中学|小学)");
    private static final Pattern PERSON=Pattern.compile("[\\p{IsHan}]{2,4}(?:老师|同学)");
    // Contextual rules deliberately do not treat every surname-prefixed word as a name:
    // e.g. "张量表示" is teaching content, while "张三回答" is a person reference.
    private static final String SURNAMES="赵钱孙李周吴郑王冯陈蒋沈韩杨朱秦许何吕施张孔曹严华金魏陶姜谢苏潘葛范彭鲁韦马方任袁柳史唐薛雷贺倪汤罗郝安常傅齐康伍余顾孟黄萧尹姚邵汪戴宋熊纪舒屈项董梁杜阮贾江郭林钟徐高夏蔡田胡卢莫丁邓洪左石崔龚程陆牛温庄廖聂辛曾关";
    private static final String CHINESE_NAME="(?:欧阳|司马|上官|诸葛|["+SURNAMES+"])[\\p{IsHan}]{1,2}";
    private static final Pattern SINGLE_SURNAME_TITLE=Pattern.compile("["+SURNAMES+"](?:老师|同学|教授|先生|女士|校长|院长|主任|博士)");
    private static final Pattern CONTEXTUAL_CHINESE_PERSON=Pattern.compile(CHINESE_NAME
            +"(?=\\s*(?:回答|提问|发言|上台|举手|朗读|汇报|分享|说[了道话，,：:]|问[了道，,：:]))");
    private static final Pattern INVITED_CHINESE_PERSON=Pattern.compile("(请|让|由|问|叫|轮到|邀请)("+CHINESE_NAME+")(?=\\s*(?:[，,。！？:：]|$))");
    private static final Pattern ENGLISH_TITLE=Pattern.compile("(?i)(?<![A-Za-z])(?:Mr|Mrs|Ms|Dr|Prof)\\.?\\s*[A-Z][a-z]{1,30}(?![A-Za-z])");
    private static final Pattern CONTEXTUAL_ENGLISH_PERSON=Pattern.compile(
            "(?<![A-Za-z])[A-Z][a-z]{2,20}(?:\\s+[A-Z][a-z]{2,20}){0,2}(?=\\s+(?:said|asked|answered|explained)(?![A-Za-z])|\\s*(?:回答|提问|发言))");

    private ClassroomAnalysisEgressPacket() {}

    static Packet build(ObjectMapper mapper,ClassroomAnalysisInputSnapshotService.Snapshot snapshot,long runId) {
        if(mapper==null||snapshot==null||snapshot.input()==null||runId<=0
                ||!"classroom-analysis-input-v4".equals(snapshot.input().version())) throw invalid();
        var input=snapshot.input();
        List<Segment> segments=new ArrayList<>();
        for(var segment:input.transcript()) {
            if(segment==null) throw invalid();
            segments.add(new Segment(id(segment.id()),segment.startMs(),segment.endMs(),redact(segment.text())));
        }
        List<Window> windows=new ArrayList<>();
        for(var window:input.headUpWindows()) {
            if(window==null) throw invalid();
            windows.add(new Window(id(window.id()),window.startMs(),window.endMs(),window.sampleAtMs(),
                    window.quality(),window.headUpCount(),window.validObservedCount(),window.headUpRate()));
        }
        List<ClassroomAnalysisInputSnapshotService.EvidenceItem> index=new ArrayList<>();
        for(var item:input.evidenceIndex()) {
            if(item==null) throw invalid();
            index.add(new ClassroomAnalysisInputSnapshotService.EvidenceItem(item.resultId(),item.itemType(),
                    id(item.itemId()),item.startMs(),item.endMs()));
        }
        var packet=new Packet(VERSION,runId,snapshot.sha256(),input.durationMs(),
                input.transcriptSource().resultId(),input.headUpSource().resultId(),input.statistics(),
                input.completeness(),List.copyOf(segments),List.copyOf(windows),List.copyOf(index),
                List.of("抬头观测不是注意力、学习效果或因果证明", "未处理区间和无效窗口不能解释为无课堂活动",
                        "转写与窗口均可能有识别误差"));
        if(bytes(mapper,packet)<=MAX_BYTES) return packet;

        // The immutable V9 input remains complete. Only the model-facing view is sampled,
        // so large classes can be analyzed without pretending the excerpts are exhaustive.
        int segmentLimit=Math.min(24,segments.size());
        int windowAnchors=Math.min(12,windows.size());
        while(segmentLimit>0&&windowAnchors>0) {
            var chosenSegments=select(segments,segmentLimit,s->s.startMs()+(s.endMs()-s.startMs())/2);
            var chosenWindows=selectWindowPairs(windows,windowAnchors);
            Set<String> segmentIds=new HashSet<>(),windowIds=new HashSet<>();
            for(var segment:chosenSegments) segmentIds.add(segment.id());
            for(var window:chosenWindows) windowIds.add(window.id());
            var chosenIndex=index.stream().filter(item->"SEGMENT".equals(item.itemType())
                            ?segmentIds.contains(item.itemId()):"WINDOW".equals(item.itemType())
                            &&windowIds.contains(item.itemId())).toList();
            if(chosenIndex.size()!=chosenSegments.size()+chosenWindows.size()) throw invalid();
            var limitations=new ArrayList<>(packet.limitations());
            if(chosenSegments.size()<segments.size())
                limitations.add("转写仅提供跨课堂抽样片段，未展示部分不能据此推断");
            if(chosenWindows.size()<windows.size())
                limitations.add("观测窗口仅提供跨课堂抽样证据，未展示部分不能据此推断");
            var sampled=new Packet(VERSION,runId,snapshot.sha256(),input.durationMs(),
                    input.transcriptSource().resultId(),input.headUpSource().resultId(),input.statistics(),
                    input.completeness(),chosenSegments,chosenWindows,chosenIndex,List.copyOf(limitations));
            if(bytes(mapper,sampled)<=MAX_BYTES) return sampled;
            if(segmentLimit==1&&windowAnchors==1) break;
            segmentLimit=Math.max(1,segmentLimit/2);
            windowAnchors=Math.max(1,windowAnchors/2);
        }
        throw invalid();
    }

    private static int bytes(ObjectMapper mapper,Packet packet) {
        try { return mapper.writeValueAsBytes(packet).length; }
        catch(Exception failure) { throw new IllegalStateException("Cannot serialize report egress packet",failure); }
    }

    private static List<Window> selectWindowPairs(List<Window> windows,int anchors) {
        if(windows.size()<=anchors*2) return List.copyOf(windows);
        var selected=new TreeSet<Integer>();
        for(int index:indices(windows,anchors,w->w.startMs()+(w.endMs()-w.startMs())/2)) {
            selected.add(index);
            selected.add(index+1<windows.size()?index+1:index-1);
        }
        return selected.stream().map(windows::get).toList();
    }

    private static <T> List<T> select(List<T> source,int limit,ToLongFunction<T> midpoint) {
        if(source.size()<=limit) return List.copyOf(source);
        return indices(source,limit,midpoint).stream().map(source::get).toList();
    }

    private static <T> List<Integer> indices(List<T> source,int limit,ToLongFunction<T> midpoint) {
        var selected=new TreeSet<Integer>();
        double first=midpoint.applyAsLong(source.get(0));
        double last=midpoint.applyAsLong(source.get(source.size()-1));
        for(int slot=0;slot<limit;slot++) {
            double target=limit==1?(first+last)/2:first+(last-first)*slot/(limit-1);
            int nearest=0;
            double distance=Double.POSITIVE_INFINITY;
            for(int index=0;index<source.size();index++) {
                double candidate=Math.abs(midpoint.applyAsLong(source.get(index))-target);
                if(candidate<distance) { nearest=index; distance=candidate; }
            }
            selected.add(nearest);
        }
        return List.copyOf(selected);
    }

    private static String id(String value) {
        if(value==null||!SAFE_ID.matcher(value).matches()) throw invalid();
        return value;
    }

    private static String redact(String value) {
        if(value==null||value.isBlank()) throw invalid();
        String result=ENGLISH_TITLE.matcher(value).replaceAll("[人员]");
        result=EMAIL.matcher(result).replaceAll("[邮箱]");
        result=URL.matcher(result).replaceAll("[链接]");
        result=PHONE.matcher(result).replaceAll("[电话]");
        result=NUMBER_ID.matcher(result).replaceAll("[编号]");
        result=PERSON.matcher(result).replaceAll("[人员]");
        result=SINGLE_SURNAME_TITLE.matcher(result).replaceAll("[人员]");
        result=CONTEXTUAL_CHINESE_PERSON.matcher(result).replaceAll("[人员]");
        result=INVITED_CHINESE_PERSON.matcher(result).replaceAll("$1[人员]");
        result=CONTEXTUAL_ENGLISH_PERSON.matcher(result).replaceAll("[人员]");
        result=SCHOOL.matcher(result).replaceAll("[学校]");
        return result;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("analysis input cannot form a bounded de-identified egress packet");
    }

    record Segment(String id,long startMs,long endMs,String text) {}
    record Window(String id,long startMs,long endMs,Long sampleAtMs,String quality,
                  Integer headUpCount,Integer validObservedCount,Double headUpRate) {}
    record Packet(String version,long runId,String inputSha256,long durationMs,
                  long transcriptResultId,long headUpResultId,
                  ClassroomAnalysisInputSnapshotService.Statistics statistics,
                  ClassroomAnalysisInputSnapshotService.Completeness completeness,
                  List<Segment> transcript,List<Window> headUpWindows,
                  List<ClassroomAnalysisInputSnapshotService.EvidenceItem> evidenceIndex,
                  List<String> limitations) {}
}
