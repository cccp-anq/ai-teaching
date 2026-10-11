package com.aijiaoxue.classroom;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Builds a local, raw evidence snapshot. It is not approved for model egress. */
@Service
class ClassroomAnalysisInputSnapshotService {
    private static final String VERSION="classroom-analysis-input-v4";
    private final ClassroomAnalysisRepository repository;
    private final ObjectMapper mapper;

    ClassroomAnalysisInputSnapshotService(ClassroomAnalysisRepository repository,ObjectMapper mapper) {
        this.repository=repository; this.mapper=mapper;
    }

    @Transactional(readOnly=true)
    Snapshot build(long mediaId) {
        var media=repository.mediaInput(mediaId);
        var report=repository.report(mediaId);
        if(media==null||report==null||!"READY".equals(media.status())
                ||!"READY".equals(report.status())||!"EXTERNAL".equals(report.mode()))
            throw notReady();
        var transcript=source(report.transcriptJobId(),media,"TRANSCRIPTION");
        var headUp=source(report.headUpJobId(),media,"HEAD_UP");
        var transcriptRanges=ranges(transcript.resultId(),media.durationMs());
        var headUpRanges=ranges(headUp.resultId(),media.durationMs());
        var segments=repository.segments(transcript.jobId());
        var windows=repository.windowEvidence(headUp.jobId());
        if(segments.isEmpty()||windows.isEmpty()) throw notReady();
        verifyOriginal(transcript,"TRANSCRIPTION",transcriptRanges,segments,List.of());
        verifyOriginal(headUp,"HEAD_UP",headUpRanges,List.of(),windows);
        List<EvidenceItem> evidenceIndex=new ArrayList<>();
        long previousEnd=-1,transcriptSegmentMs=0;
        for(var segment:segments) {
            if(segment.text()==null||segment.text().isBlank()||segment.startMs()<previousEnd
                    ||segment.endMs()<=segment.startMs()||segment.endMs()>media.durationMs()
                    ||!covered(segment.startMs(),segment.endMs(),transcriptRanges)) throw notReady();
            previousEnd=segment.endMs();
            transcriptSegmentMs+=segment.endMs()-segment.startMs();
            evidenceIndex.add(new EvidenceItem(transcript.resultId(),"SEGMENT",segment.id(),
                    segment.startMs(),segment.endMs()));
        }
        long validDuration=0,previousWindowEnd=-1;
        double weightedRate=0;
        List<WindowFact> windowFacts=new ArrayList<>();
        for(var window:windows) {
            if(window.startMs()<previousWindowEnd||window.endMs()<=window.startMs()
                    ||window.endMs()>media.durationMs()||window.sampleAtMs()!=null
                    &&(window.sampleAtMs()<window.startMs()||window.sampleAtMs()>=window.endMs())
                    ||!covered(window.startMs(),window.endMs(),headUpRanges)) throw notReady();
            previousWindowEnd=window.endMs();
            Double rate=window.headUpRatio();
            if(rate==null&&window.headUpCount()!=null&&window.validObservedCount()!=null
                    &&window.validObservedCount()>0)
                rate=(double)window.headUpCount()/window.validObservedCount();
            if("VALID".equals(window.quality())&&(rate==null||!Double.isFinite(rate)||rate<0||rate>1))
                throw notReady();
            windowFacts.add(new WindowFact(window.id(),window.startMs(),window.endMs(),window.sampleAtMs(),
                    window.quality(),window.qualityReason(),window.headUpCount(),
                    window.validObservedCount(),"VALID".equals(window.quality())?rate:null));
            evidenceIndex.add(new EvidenceItem(headUp.resultId(),"WINDOW",window.id(),
                    window.startMs(),window.endMs()));
            if(!"VALID".equals(window.quality())) continue;
            long span=window.endMs()-window.startMs();
            validDuration+=span;
            weightedRate+=rate*span;
        }
        if(validDuration==0||report.mean()==null||report.coverage()==null
                ||!Double.isFinite(report.mean())||!Double.isFinite(report.coverage())
                ||Math.abs(report.mean()-weightedRate/validDuration)>1e-9
                ||Math.abs(report.coverage()-(double)validDuration/media.durationMs())>1e-9) throw notReady();
        var completeness=new Completeness(
                coverage(sumRanges(transcriptRanges),media.durationMs()),
                coverage(transcriptSegmentMs,media.durationMs()),
                coverage(sumRanges(headUpRanges),media.durationMs()),
                coverage(validDuration,media.durationMs()));
        var input=new RawInput(VERSION,report.id(),media.id(),media.sha256(),media.durationMs(),transcript,headUp,
                new Statistics(report.mean(),report.coverage()),completeness,transcriptRanges,headUpRanges,
                List.copyOf(segments),List.copyOf(windowFacts),List.copyOf(evidenceIndex));
        try {
            String json=mapper.writeValueAsString(input);
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
            return new Snapshot(HexFormat.of().formatHex(digest),json,input);
        } catch(Exception failure) {
            throw new IllegalStateException("Cannot serialize local analysis input",failure);
        }
    }

    private SourceResult source(long jobId,ClassroomAnalysisRepository.MediaInput media,String moduleType) {
        var job=repository.moduleJob(jobId);
        if(job==null||job.mediaId()!=media.id()||!Objects.equals(job.sha256(),media.sha256())
                ||job.durationMs()!=media.durationMs()||!moduleType.equals(job.moduleType())
                ||!"SUCCEEDED".equals(job.status())||job.resultRevision()==null) throw notReady();
        var result=repository.resultRef(jobId,job.resultRevision());
        if(result==null||!"SUCCEEDED".equals(result.status())) throw notReady();
        return new SourceResult(result.id(),result.jobId(),result.revision(),result.sha256());
    }

    private void verifyOriginal(SourceResult source,String moduleType,
                                List<ClassroomAnalysisRepository.ProcessedRange> ranges,
                                List<ClassroomAnalysisView.SegmentView> segments,
                                List<ClassroomAnalysisRepository.WindowEvidence> windows) {
        var job=repository.moduleJob(source.jobId());
        String json=repository.resultEnvelope(source.resultId());
        if(job==null||!moduleType.equals(job.moduleType())||!"SUCCEEDED".equals(job.status())
                ||job.resultRevision()==null||job.resultRevision()!=source.revision()
                ||json==null||!source.envelopeSha256().equals(sha256(json))) throw notReady();
        ClassroomModuleContract.Envelope original;
        try { original=ClassroomModuleContract.parse(job,mapper.readTree(json)); }
        catch(Exception invalid) { throw notReady(); }
        if(!"SUCCEEDED".equals(original.status())||original.revision()!=source.revision()
                ||original.ranges().size()!=ranges.size()) throw notReady();
        for(int i=0;i<ranges.size();i++) {
            var expected=original.ranges().get(i); var actual=ranges.get(i);
            if(expected.startMs()!=actual.startMs()||expected.endMs()!=actual.endMs()) throw notReady();
        }
        if("TRANSCRIPTION".equals(moduleType)) {
            if(original.segments().size()!=segments.size()) throw notReady();
            for(int i=0;i<segments.size();i++) {
                var expected=original.segments().get(i); var actual=segments.get(i);
                if(!expected.id().equals(actual.id())||expected.startMs()!=actual.startMs()
                        ||expected.endMs()!=actual.endMs()||!expected.text().equals(actual.text())) throw notReady();
            }
        } else {
            if(original.windows().size()!=windows.size()) throw notReady();
            for(int i=0;i<windows.size();i++) {
                var expected=original.windows().get(i); var actual=windows.get(i);
                if(!expected.id().equals(actual.id())||expected.startMs()!=actual.startMs()
                        ||expected.endMs()!=actual.endMs()||!expected.quality().equals(actual.quality())
                        ||!Objects.equals(expected.qualityReason(),actual.qualityReason())
                        ||!Objects.equals(expected.headUpCount(),actual.headUpCount())
                        ||!Objects.equals(expected.validObservedCount(),actual.validObservedCount())
                        ||!Objects.equals(expected.sampleAtMs(),actual.sampleAtMs())
                        ||!Objects.equals(expected.headUpRatio(),actual.headUpRatio())) throw notReady();
            }
        }
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private List<ClassroomAnalysisRepository.ProcessedRange> ranges(long resultId,long durationMs) {
        var ranges=repository.processedRanges(resultId);
        if(ranges.isEmpty()) throw notReady();
        long previousEnd=-1;
        for(var range:ranges) {
            if(range.startMs()<0||range.startMs()<previousEnd
                    ||range.endMs()<=range.startMs()||range.endMs()>durationMs) throw notReady();
            previousEnd=range.endMs();
        }
        return List.copyOf(ranges);
    }

    private static boolean covered(long startMs,long endMs,
                                   List<ClassroomAnalysisRepository.ProcessedRange> ranges) {
        return ranges.stream().anyMatch(range->startMs>=range.startMs()&&endMs<=range.endMs());
    }

    private static long sumRanges(List<ClassroomAnalysisRepository.ProcessedRange> ranges) {
        long total=0;
        for(var range:ranges) total+=range.endMs()-range.startMs();
        return total;
    }

    private static Coverage coverage(long coveredMs,long durationMs) {
        return new Coverage(coveredMs,(double)coveredMs/durationMs);
    }

    private static ClassroomAnalysisException notReady() {
        return new ClassroomAnalysisException("INPUT_NOT_READY","课堂分析输入尚不完整或已不一致",422);
    }

    record SourceResult(long resultId,long jobId,int revision,String envelopeSha256) {}
    record Statistics(double meanHeadUpRate,double validCoverageRate) {}
    record Coverage(long coveredMs,double rate) {}
    record Completeness(Coverage transcriptProcessed,Coverage transcriptSegments,
                        Coverage headUpProcessed,Coverage headUpValidWindows) {}
    record WindowFact(String id,long startMs,long endMs,Long sampleAtMs,String quality,
                      String qualityReason,Integer headUpCount,Integer validObservedCount,Double headUpRate) {}
    record EvidenceItem(long resultId,String itemType,String itemId,long startMs,long endMs) {}
    record RawInput(String version,long objectiveReportId,long mediaId,String mediaSha256,long durationMs,
                    SourceResult transcriptSource,SourceResult headUpSource,Statistics statistics,
                    Completeness completeness,
                    List<ClassroomAnalysisRepository.ProcessedRange> transcriptProcessedRanges,
                    List<ClassroomAnalysisRepository.ProcessedRange> headUpProcessedRanges,
                    List<ClassroomAnalysisView.SegmentView> transcript,
                    List<WindowFact> headUpWindows,List<EvidenceItem> evidenceIndex) {}
    record Snapshot(String sha256,String canonicalJson,RawInput input) {}
}
