package com.aijiaoxue.classroom;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Strict adapter for the shared transcription/head-up JobEnvelope v1.0 contract. */
final class ClassroomModuleContract {
    private static final Set<String> STATUSES=Set.of("QUEUED","RUNNING","SUCCEEDED","FAILED");
    private static final Set<String> QUALITIES=Set.of("VALID","NO_SUBJECTS","LOW_QUALITY","REVIEW");

    private ClassroomModuleContract() {}

    static Envelope parse(ClassroomAnalysisRepository.ModuleJobRow expected, JsonNode root) {
        requiredObject(root,"envelope");
        equal("schemaVersion",text(root,"schemaVersion"),"1.0");
        equal("clientRequestId",text(root,"clientRequestId"),expected.clientRequestId());
        equal("moduleType",text(root,"moduleType"),expected.moduleType());
        equal("classSessionId",text(root,"classSessionId"),Long.toString(expected.classSessionId()));
        equal("mediaId",text(root,"mediaId"),Long.toString(expected.mediaId()));
        equal("mediaSha256",text(root,"mediaSha256"),expected.sha256());
        equal("durationMs",number(root,"durationMs"),expected.durationMs());
        equal("timeBase",text(root,"timeBase"),"MEDIA_START_MS");
        String jobId=text(root,"jobId");
        if(expected.providerJobId()!=null&&!expected.providerJobId().equals(jobId)) fail("jobId changed after submission");
        String status=text(root,"status");
        if(!STATUSES.contains(status)) fail("unsupported status");
        int revision=integer(root,"revision");
        if(revision!=(isTerminal(status)?1:0)) fail("revision does not match status");
        JsonNode range=requiredObject(root.path("analysisRange"),"analysisRange");
        long rangeStart=number(range,"startMs"),rangeEnd=number(range,"endMs");
        if(rangeStart!=0||rangeEnd!=expected.durationMs()) fail("analysisRange does not match assigned media");
        String providerId=text(root,"providerId"),moduleVersion=text(root,"moduleVersion");
        if(!"not-configured".equals(expected.providerId())) equal("providerId",providerId,expected.providerId());
        if("RUNNING".equals(expected.status())&&"QUEUED".equals(status)) fail("job status cannot move backwards");
        JsonNode payload=root.get("payload"),errorNode=root.get("error");
        if(!"SUCCEEDED".equals(status)&&payload!=null&&!payload.isNull()) fail("non-success envelope has payload");
        if(!"FAILED".equals(status)&&errorNode!=null&&!errorNode.isNull()) fail("non-failed envelope has error");
        Error error=null;
        if("FAILED".equals(status)) {
            requiredObject(errorNode,"error");
            error=new Error(text(errorNode,"code"),text(errorNode,"message"),bool(errorNode,"retryable"));
        }
        List<Range> ranges=List.of(); List<Segment> segments=List.of(); List<Window> windows=List.of();
        if("SUCCEEDED".equals(status)) {
            requiredObject(payload,"payload");
            ranges=parseRanges(payload.path("processedRanges"),expected.durationMs());
            if("TRANSCRIPTION".equals(expected.moduleType())) segments=parseSegments(payload.path("segments"),ranges);
            else windows=parseWindows(payload,ranges,expected.durationMs());
        }
        return new Envelope(jobId,status,revision,providerId,moduleVersion,error,ranges,segments,windows);
    }

    private static List<Range> parseRanges(JsonNode node,long duration) {
        if(!node.isArray()||node.isEmpty()) fail("processedRanges must be a non-empty array");
        List<Range> result=new ArrayList<>(); long previousEnd=-1;
        for(JsonNode item:node) {
            long start=number(item,"startMs"),end=number(item,"endMs");
            if(start<0||end<=start||end>duration||start<previousEnd) fail("processedRanges are invalid or overlapping");
            result.add(new Range(start,end)); previousEnd=end;
        }
        return List.copyOf(result);
    }

    private static List<Segment> parseSegments(JsonNode node,List<Range> ranges) {
        if(!node.isArray()) fail("segments must be an array");
        List<Segment> result=new ArrayList<>(); Set<String> ids=new HashSet<>(); long previousStart=-1;
        for(JsonNode item:node) {
            String id=text(item,"id"),value=text(item,"text").trim();
            long start=number(item,"startMs"),end=number(item,"endMs");
            if(value.isEmpty()||start<previousStart||end<=start||!covered(start,end,ranges)||!ids.add(id))
                fail("transcript segment is invalid, duplicate, unordered, or outside processedRanges");
            Double confidence=nullableDouble(item.get("confidence"));
            if(confidence!=null&&(confidence<0||confidence>1)) fail("segment confidence must be in [0,1]");
            String speaker=nullableText(item.get("speakerLabel"));
            result.add(new Segment(id,start,end,value,confidence,speaker)); previousStart=start;
        }
        return List.copyOf(result);
    }

    private static List<Window> parseWindows(JsonNode payload,List<Range> ranges,long duration) {
        JsonNode node=payload.path("windows");
        if(!node.isArray()||node.isEmpty()) fail("windows must be a non-empty array");
        String valueMode=text(payload,"valueMode");
        if(!Set.of("COUNTS","RATIO_ONLY").contains(valueMode)) fail("unsupported head-up valueMode");
        long windowMs=number(payload,"windowMs");
        if(windowMs<1) fail("windowMs must be positive");
        List<Window> result=new ArrayList<>(); Set<String> ids=new HashSet<>(); long previousEnd=-1;
        for(JsonNode item:node) {
            String id=text(item,"id"),quality=text(item,"quality");
            long start=number(item,"startMs"),end=number(item,"endMs");
            if(!QUALITIES.contains(quality)||start<previousEnd||end<=start||end-start>windowMs||end>duration
                    ||!covered(start,end,ranges)||!ids.add(id)) fail("head-up window is invalid, duplicate, or overlapping");
            Long sample=nullableLong(item.get("sampleAtMs"));
            if(sample!=null&&(sample<start||sample>=end)) fail("sampleAtMs is outside its window");
            String reason=nullableText(item.get("qualityReason"));
            Integer up=nullableInteger(item.get("headUpCount")),observed=nullableInteger(item.get("validObservedCount"));
            Integer visible=nullableInteger(item.get("visibleStudentCount"));
            if(visible!=null&&(visible<0||visible>1000||(observed!=null&&visible<observed)))
                fail("invalid anonymous visible student count");
            Double ratio=nullableDouble(item.get("headUpRatio"));
            if("COUNTS".equals(valueMode)) {
                if(ratio!=null) fail("COUNTS response must not include headUpRatio");
                if("VALID".equals(quality)||"REVIEW".equals(quality)) {
                    if(up==null||observed==null||observed<=0||up<0||up>observed) fail("invalid head-up counts");
                } else if("NO_SUBJECTS".equals(quality)&&(up==null||observed==null||up!=0||observed!=0)) {
                    fail("NO_SUBJECTS must use 0/0 counts");
                }
            } else if(("VALID".equals(quality)||"REVIEW".equals(quality))&&(ratio==null||ratio<0||ratio>1)) {
                fail("RATIO_ONLY valid window requires a ratio in [0,1]");
            }
            result.add(new Window(id,start,end,quality,reason,up,observed,sample,ratio)); previousEnd=end;
        }
        return List.copyOf(result);
    }

    private static boolean covered(long start,long end,List<Range> ranges) {
        return ranges.stream().anyMatch(range->start>=range.startMs()&&end<=range.endMs());
    }
    static boolean isTerminal(String status) { return "SUCCEEDED".equals(status)||"FAILED".equals(status); }
    private static JsonNode requiredObject(JsonNode node,String name) {
        if(node==null||!node.isObject()) fail(name+" must be an object"); return node;
    }
    private static String text(JsonNode node,String name) {
        JsonNode value=node.get(name); if(value==null||!value.isTextual()||value.asText().isBlank()) fail(name+" is required");
        return value.asText();
    }
    private static long number(JsonNode node,String name) {
        JsonNode value=node.get(name); if(value==null||!value.isIntegralNumber()) fail(name+" must be an integer"); return value.longValue();
    }
    private static int integer(JsonNode node,String name) {
        long value=number(node,name); if(value<Integer.MIN_VALUE||value>Integer.MAX_VALUE) fail(name+" is out of range"); return (int)value;
    }
    private static boolean bool(JsonNode node,String name) {
        JsonNode value=node.get(name); if(value==null||!value.isBoolean()) fail(name+" must be boolean"); return value.booleanValue();
    }
    private static Long nullableLong(JsonNode node) { return node==null||node.isNull()?null:(node.isIntegralNumber()?node.longValue():bad("expected integer or null")); }
    private static Integer nullableInteger(JsonNode node) { Long value=nullableLong(node); return value==null?null:(value>=Integer.MIN_VALUE&&value<=Integer.MAX_VALUE?value.intValue():bad("integer out of range")); }
    private static Double nullableDouble(JsonNode node) { return node==null||node.isNull()?null:(node.isNumber()?node.doubleValue():bad("expected number or null")); }
    private static String nullableText(JsonNode node) { return node==null||node.isNull()?null:(node.isTextual()?node.asText():bad("expected text or null")); }
    private static <T> T bad(String message) { fail(message); return null; }
    private static void equal(String name,Object actual,Object expected) { if(!expected.equals(actual)) fail(name+" does not match assignment"); }
    private static void fail(String message) { throw new ContractViolation(message); }

    record Envelope(String jobId,String status,int revision,String providerId,String moduleVersion,Error error,
                    List<Range> ranges,List<Segment> segments,List<Window> windows) {}
    record Error(String code,String message,boolean retryable) {}
    record Range(long startMs,long endMs) {}
    record Segment(String id,long startMs,long endMs,String text,Double confidence,String speakerLabel) {}
    record Window(String id,long startMs,long endMs,String quality,String qualityReason,Integer headUpCount,
                  Integer validObservedCount,Long sampleAtMs,Double headUpRatio) {}
    static final class ContractViolation extends IllegalArgumentException {
        ContractViolation(String message) { super(message); }
    }
}
