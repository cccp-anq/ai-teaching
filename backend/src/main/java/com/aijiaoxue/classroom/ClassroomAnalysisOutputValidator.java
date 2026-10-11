package com.aijiaoxue.classroom;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Validates a local draft against one immutable input; never sends or persists it. */
final class ClassroomAnalysisOutputValidator {
    private static final int MAX_BYTES=64*1024;
    private static final Pattern LINK=Pattern.compile("[a-z][a-z0-9+.-]{1,20}:|(?:[a-z0-9-]+\\.)+[a-z]{2,}|\\[[^\\]]+\\]\\([^)]*\\)");
    private static final Pattern WRITTEN_NUMBER=Pattern.compile("(?:百分之|千分之)[零〇一二两三四五六七八九十百千万亿]+|[零〇一二两三四五六七八九十百千万亿]+(?:成|个百分点|人|次|分钟|秒|倍)|(?:抬头率|覆盖率|比例|人数|上升|下降|增长|降低).{0,6}[零〇一二两三四五六七八九十百千万亿]+");
    private ClassroomAnalysisOutputValidator() {}

    static Draft parse(ObjectMapper mapper,ClassroomAnalysisInputSnapshotService.Snapshot snapshot,
                       long runId,String json) {
        if(mapper==null||snapshot==null||snapshot.input()==null||runId<=0||json==null
                ||json.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES) throw invalid();
        String stage="ENVELOPE";
        try {
        JsonNode root;
        try { root=mapper.readTree(json); } catch(Exception failure) { throw invalid(); }
        fields(root,"reportVersion","runId","inputSha256","topics","changes","interpretations","limitations");
        if(!"1.0".equals(string(root,"reportVersion"))||integer(root,"runId")!=runId
                ||!snapshot.sha256().equals(string(root,"inputSha256"))) throw invalid();
        var input=snapshot.input();
        List<Topic> topics=new ArrayList<>();
        stage="TOPIC";
        for(var node:array(root,"topics",1,30)) {
            fields(node,"startMs","endMs","summary","evidenceRefs");
            long start=integer(node,"startMs"),end=integer(node,"endMs");
            var refs=refs(node,input,1,12,true,false);
            ClassroomAnalysisEvidenceValidator.validateClaim(input,start,end,refs);
            topics.add(new Topic(start,end,prose(node,"summary"),refs));
        }
        List<Change> changes=new ArrayList<>();
        Set<String> changePairs=new HashSet<>();
        var windows=input.headUpWindows();
        stage="CHANGE";
        for(var node:array(root,"changes",0,30)) {
            fields(node,"fromWindowId","toWindowId","startMs","endMs","evidenceRefs");
            String fromId=string(node,"fromWindowId"),toId=string(node,"toWindowId");
            int fromIndex=-1;
            for(int i=0;i<windows.size()-1;i++)
                if(windows.get(i).id().equals(fromId)&&windows.get(i+1).id().equals(toId)) fromIndex=i;
            if(fromIndex<0||!changePairs.add(fromId+"\u0000"+toId)) throw invalid();
            var from=windows.get(fromIndex); var to=windows.get(fromIndex+1);
            if(!"VALID".equals(from.quality())||!"VALID".equals(to.quality())
                    ||from.headUpRate()==null||to.headUpRate()==null||from.endMs()!=to.startMs()) throw invalid();
            long start=integer(node,"startMs"),end=integer(node,"endMs");
            if(start!=from.startMs()||end!=to.endMs()) throw invalid();
            var refs=refs(node,input,2,12,false,false);
            if(!refs.contains(windowRef(input,fromId))||!refs.contains(windowRef(input,toId))) throw invalid();
            ClassroomAnalysisEvidenceValidator.validateClaim(input,start,end,refs);
            changes.add(new Change(fromId,toId,start,end,(to.headUpRate()-from.headUpRate())*100,refs));
        }
        List<Interpretation> interpretations=new ArrayList<>();
        stage="INTERPRETATION";
        for(var node:array(root,"interpretations",0,20)) {
            fields(node,"hypothesis","alternatives","suggestion","evidenceRefs");
            List<String> alternatives=new ArrayList<>();
            for(var alternative:array(node,"alternatives",1,5)) alternatives.add(proseValue(alternative));
            var refs=refs(node,input,2,12,true,true);
            ClassroomAnalysisEvidenceValidator.validateRefs(input,refs);
            if(!hasConcurrentSources(input,refs)) throw invalid();
            interpretations.add(new Interpretation(prose(node,"hypothesis"),List.copyOf(alternatives),
                    prose(node,"suggestion"),refs));
        }
        List<String> limitations=new ArrayList<>();
        stage="LIMITATION";
        for(var limitation:array(root,"limitations",1,12)) limitations.add(proseValue(limitation));
        return new Draft(runId,snapshot.sha256(),List.copyOf(topics),List.copyOf(changes),
                List.copyOf(interpretations),List.copyOf(limitations));
        } catch(OutputViolation failure) {
            throw new OutputViolation("REPORT_DRAFT_"+stage+"_INVALID");
        } catch(ClassroomAnalysisEvidenceValidator.EvidenceViolation failure) {
            throw new OutputViolation("REPORT_DRAFT_"+stage+"_EVIDENCE_INVALID");
        }
    }

    private static ClassroomAnalysisEvidenceValidator.EvidenceRef windowRef(
            ClassroomAnalysisInputSnapshotService.RawInput input,String id) {
        return new ClassroomAnalysisEvidenceValidator.EvidenceRef(input.headUpSource().resultId(),"WINDOW",id);
    }

    private static boolean hasConcurrentSources(ClassroomAnalysisInputSnapshotService.RawInput input,
                                                List<ClassroomAnalysisEvidenceValidator.EvidenceRef> refs) {
        var cited=input.evidenceIndex().stream().filter(item->refs.contains(
                new ClassroomAnalysisEvidenceValidator.EvidenceRef(item.resultId(),item.itemType(),item.itemId()))).toList();
        for(var segment:cited) for(var window:cited)
            if("SEGMENT".equals(segment.itemType())&&"WINDOW".equals(window.itemType())
                    &&segment.startMs()<window.endMs()&&window.startMs()<segment.endMs()) return true;
        return false;
    }

    private static List<ClassroomAnalysisEvidenceValidator.EvidenceRef> refs(
            JsonNode node,ClassroomAnalysisInputSnapshotService.RawInput input,int min,int max,
            boolean requireSegment,boolean requireWindow) {
        List<ClassroomAnalysisEvidenceValidator.EvidenceRef> refs=new ArrayList<>();
        boolean segment=false,window=false;
        for(var item:array(node,"evidenceRefs",min,max)) {
            fields(item,"resultId","itemType","itemId");
            var ref=new ClassroomAnalysisEvidenceValidator.EvidenceRef(integer(item,"resultId"),
                    string(item,"itemType"),string(item,"itemId"));
            refs.add(ref);
            segment|="SEGMENT".equals(ref.itemType()); window|="WINDOW".equals(ref.itemType());
        }
        if(requireSegment&&!segment||requireWindow&&!window) throw invalid();
        ClassroomAnalysisEvidenceValidator.validateRefs(input,refs);
        return List.copyOf(refs);
    }

    private static void fields(JsonNode node,String... names) {
        if(node==null||!node.isObject()||node.size()!=names.length) throw invalid();
        for(String name:names) if(node.get(name)==null) throw invalid();
    }
    private static JsonNode array(JsonNode node,String name,int min,int max) {
        JsonNode value=node.get(name);
        if(value==null||!value.isArray()||value.size()<min||value.size()>max) throw invalid();
        return value;
    }
    private static String string(JsonNode node,String name) {
        JsonNode value=node.get(name);
        if(value==null||!value.isTextual()||value.asText().isBlank()) throw invalid();
        return value.asText();
    }
    private static long integer(JsonNode node,String name) {
        JsonNode value=node.get(name);
        if(value==null||!value.isIntegralNumber()||!value.canConvertToLong()) throw invalid();
        return value.longValue();
    }
    private static String prose(JsonNode node,String name) { return proseValue(node.get(name)); }
    private static String proseValue(JsonNode value) {
        if(value==null||!value.isTextual()) throw invalid();
        String text=value.asText().trim();
        String normalized=Normalizer.normalize(text,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        if(text.isEmpty()||text.length()>500||LINK.matcher(normalized).find()
                ||WRITTEN_NUMBER.matcher(normalized).find()||normalized.indexOf('<')>=0
                ||normalized.indexOf('>')>=0||normalized.indexOf('%')>=0
                ||normalized.codePoints().anyMatch(c->Character.isISOControl(c)||Character.isDigit(c)
                        ||Character.getType(c)==Character.FORMAT)) throw invalid();
        return text;
    }
    private static OutputViolation invalid() { return new OutputViolation(); }

    /** The provider may omit a sampling caveat; the persisted report must not. */
    static Draft withSamplingLimitations(Draft draft,ClassroomAnalysisEgressPacket.Packet packet) {
        var notices=packet.limitations().stream().filter(value->value.contains("抽样")).toList();
        if(notices.isEmpty()) return draft;
        var merged=new LinkedHashSet<String>(notices);
        merged.addAll(draft.limitations());
        return new Draft(draft.runId(),draft.inputSha256(),draft.topics(),draft.changes(),
                draft.interpretations(),merged.stream().limit(12).toList());
    }

    record Topic(long startMs,long endMs,String summary,List<ClassroomAnalysisEvidenceValidator.EvidenceRef> evidenceRefs) {}
    record Change(String fromWindowId,String toWindowId,long startMs,long endMs,double deltaPercentagePoints,
                  List<ClassroomAnalysisEvidenceValidator.EvidenceRef> evidenceRefs) {}
    record Interpretation(String hypothesis,List<String> alternatives,String suggestion,
                          List<ClassroomAnalysisEvidenceValidator.EvidenceRef> evidenceRefs) {}
    record Draft(long runId,String inputSha256,List<Topic> topics,List<Change> changes,
                 List<Interpretation> interpretations,List<String> limitations) {}
    static final class OutputViolation extends IllegalArgumentException {
        private final String code;
        OutputViolation() { this("REPORT_DRAFT_INVALID"); }
        private OutputViolation(String code) {
            super("analysis draft does not match its prepared input");
            this.code=code;
        }
        String code() { return code; }
    }
}
