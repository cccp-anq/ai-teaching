package com.aijiaoxue.classroom;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class ClassroomModuleResultService {
    private final ClassroomAnalysisRepository repository;
    private final ObjectMapper mapper;
    private final ClassroomReportDispatch dispatch;

    ClassroomModuleResultService(ClassroomAnalysisRepository repository,ObjectMapper mapper,
                                 ClassroomReportDispatch dispatch) {
        this.repository=repository; this.mapper=mapper; this.dispatch=dispatch;
    }

    @Transactional
    void accept(ClassroomAnalysisRepository.ModuleJobRow job,JsonNode raw) {
        accept(job,raw,null);
    }

    @Transactional
    void accept(ClassroomAnalysisRepository.ModuleJobRow job,JsonNode raw,LocalDateTime nextPollAt) {
        var envelope=ClassroomModuleContract.parse(job,raw);
        if(ClassroomModuleContract.isTerminal(job.status())) {
            if(!ClassroomModuleContract.isTerminal(envelope.status())||job.resultRevision()==null
                    ||job.resultRevision()!=envelope.revision()||!job.status().equals(envelope.status()))
                throw new ClassroomModuleContract.ContractViolation("terminal result is immutable");
            String hash=sha256(writeJson(raw));
            if(hash.equals(repository.moduleResultHash(job.id(),envelope.revision()))) return;
            throw new ClassroomModuleContract.ContractViolation("same result revision has different content");
        }
        if(!ClassroomModuleContract.isTerminal(envelope.status())) {
            repository.saveProgress(job.id(),envelope.status(),envelope.providerId(),envelope.jobId(),
                    envelope.moduleVersion(),envelope.revision(),nextPollAt);
            return;
        }
        String json=writeJson(raw);
        long resultId=repository.insertModuleResult(job.id(),envelope.revision(),envelope.status(),sha256(json),json);
        for(int index=0;index<envelope.ranges().size();index++) {
            var range=envelope.ranges().get(index);
            repository.insertProcessedRange(resultId,index,range.startMs(),range.endMs());
        }
        if("SUCCEEDED".equals(envelope.status())) {
            if("TRANSCRIPTION".equals(job.moduleType())) repository.replaceTranscript(job.id(),envelope.segments());
            else repository.replaceHeadUpWindows(job.id(),envelope.windows());
        }
        repository.finishJob(job.id(),envelope);
        if("FAILED".equals(envelope.status())) {
            repository.markReportFailed(job.mediaId(),envelope.error().code()+": "+envelope.error().message());
        } else if(repository.succeededJobCount(job.mediaId())==2) {
            var aggregate=repository.headUpAggregate(job.mediaId());
            double coverage=aggregate.durationMs()==0?0:(double)aggregate.validDurationMs()/aggregate.durationMs();
            if(aggregate.mean()==null) repository.markReportReadyWithoutMean(job.mediaId(),coverage);
            else repository.markReportReady(job.mediaId(),aggregate.mean(),coverage);
            dispatch.enqueue(job.mediaId());
        }
    }

    @Transactional
    void failIntegration(ClassroomAnalysisRepository.ModuleJobRow job,String code,String message) {
        if(repository.failIntegrationJob(job.id(),code,message))
            repository.markReportFailed(job.mediaId(),code+": "+message);
    }

    private String writeJson(JsonNode raw) {
        try { return mapper.writeValueAsString(raw); }
        catch(Exception failure) { throw new IllegalArgumentException("cannot serialize module result",failure); }
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
