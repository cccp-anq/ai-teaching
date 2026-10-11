package com.aijiaoxue.classroom;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Local preparation only: this service cannot submit a model request. */
@Service
class ClassroomAnalysisRunService {
    private static final Pattern REQUEST_KEY=Pattern.compile("[A-Za-z0-9._:-]{8,128}");
    private final ClassroomAnalysisRepository repository;
    private final ClassroomAnalysisInputSnapshotService snapshots;
    private final ObjectMapper mapper;

    ClassroomAnalysisRunService(ClassroomAnalysisRepository repository,
                               ClassroomAnalysisInputSnapshotService snapshots,ObjectMapper mapper) {
        this.repository=repository; this.snapshots=snapshots; this.mapper=mapper;
    }

    /** Local-only check against the original V9 row; never rebuilds from mutable source tables. */
    @Transactional(readOnly=true)
    ClassroomAnalysisOutputValidator.Draft validateDraft(long runId,String draftJson) {
        var snapshot=storedSnapshot(runId);
        var draft=ClassroomAnalysisOutputValidator.parse(mapper,snapshot,runId,draftJson);
        return ClassroomAnalysisOutputValidator.withSamplingLimitations(draft,
                ClassroomAnalysisEgressPacket.build(mapper,snapshot,runId));
    }

    /** Builds the allowlisted model input locally; no network request is made here. */
    @Transactional(readOnly=true)
    ClassroomAnalysisEgressPacket.Packet prepareEgress(long runId) {
        return ClassroomAnalysisEgressPacket.build(mapper,storedSnapshot(runId),runId);
    }

    private ClassroomAnalysisInputSnapshotService.Snapshot storedSnapshot(long runId) {
        var row=repository.analysisRunInput(runId);
        if(row==null||!("PREPARED".equals(row.state())||"COMPLETED".equals(row.state())))
            throw new ClassroomAnalysisException("ANALYSIS_RUN_NOT_PREPARED","分析运行不存在或尚不可校验",422);
        ClassroomAnalysisInputSnapshotService.RawInput input;
        try {
            if(row.inputJson()==null||!row.inputSha256().equals(sha256(row.inputJson())))
                throw new IllegalStateException();
            input=mapper.readValue(row.inputJson(),ClassroomAnalysisInputSnapshotService.RawInput.class);
            if(input==null||!"classroom-analysis-input-v4".equals(input.version())
                    ||!row.inputVersion().equals(input.version())
                    ||row.objectiveReportId()!=input.objectiveReportId()||row.mediaId()!=input.mediaId()
                    ||input.transcriptSource()==null||input.headUpSource()==null
                    ||row.transcriptResultId()!=input.transcriptSource().resultId()
                    ||row.headUpResultId()!=input.headUpSource().resultId()) throw new IllegalStateException();
        } catch(Exception invalidSnapshot) {
            throw new IllegalStateException("Stored analysis input failed integrity checks");
        }
        return new ClassroomAnalysisInputSnapshotService.Snapshot(row.inputSha256(),row.inputJson(),input);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    PreparedRun prepare(long mediaId,String requestKey) {
        if(requestKey==null||!REQUEST_KEY.matcher(requestKey).matches())
            throw new ClassroomAnalysisException("INVALID_IDEMPOTENCY_KEY","分析任务请求键无效",400);
        String requestHash=sha256(requestKey);
        var snapshot=snapshots.build(mediaId);
        var input=snapshot.input();
        var existing=repository.analysisRunByRequest(input.objectiveReportId(),requestHash);
        if(existing!=null) return reused(existing,snapshot);
        try {
            long id=repository.insertAnalysisRun(input.objectiveReportId(),mediaId,
                    input.transcriptSource().resultId(),input.headUpSource().resultId(),requestHash,
                    input.version(),snapshot.sha256(),snapshot.canonicalJson());
            return new PreparedRun(id,"PREPARED",snapshot.sha256(),false);
        } catch(DuplicateKeyException collision) {
            // READ_COMMITTED allows the losing insert to see the committed winner.
            existing=repository.analysisRunByRequest(input.objectiveReportId(),requestHash);
            if(existing==null) throw collision;
            return reused(existing,snapshot);
        }
    }

    private static PreparedRun reused(ClassroomAnalysisRepository.AnalysisRunRow existing,
                                      ClassroomAnalysisInputSnapshotService.Snapshot snapshot) {
        if(existing.mediaId()!=snapshot.input().mediaId()
                ||!existing.inputVersion().equals(snapshot.input().version())
                ||!existing.inputSha256().equals(snapshot.sha256()))
            throw new ClassroomAnalysisException("ANALYSIS_INPUT_CHANGED",
                    "相同请求键对应的分析输入已变化，原快照不会被覆盖",409);
        return new PreparedRun(existing.id(),existing.state(),existing.inputSha256(),true);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception impossible) { throw new IllegalStateException(impossible); }
    }

    record PreparedRun(long id,String state,String inputSha256,boolean replayed) {}
}
