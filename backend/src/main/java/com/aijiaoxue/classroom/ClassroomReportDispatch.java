package com.aijiaoxue.classroom;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable handoff from objective READY; later generations require a supervisor decision. */
@Service
class ClassroomReportDispatch {
    private final JdbcTemplate jdbc;
    private final ClassroomAnalysisRepository repository;

    ClassroomReportDispatch(JdbcTemplate jdbc,ClassroomAnalysisRepository repository) {
        this.jdbc=jdbc; this.repository=repository;
    }

    @Transactional
    void enqueue(long mediaId) {
        var report=repository.report(mediaId);
        if(report==null||!"READY".equals(report.status())||!"EXTERNAL".equals(report.mode()))
            throw new IllegalStateException("Objective report is not ready for dispatch");
        try {
            jdbc.update("INSERT INTO classroom_report_dispatch(objective_report_id,media_id) VALUES (?,?)",
                    report.id(),mediaId);
        } catch(DuplicateKeyException duplicate) {
            // A repeated terminal module result cannot produce a second dispatch.
            if(jdbc.queryForObject("SELECT COUNT(*) FROM classroom_report_dispatch WHERE objective_report_id=? AND media_id=?",
                    Integer.class,report.id(),mediaId)!=1) throw duplicate;
        }
    }

    String stateForMedia(long mediaId,long objectiveReportId) {
        List<String> states=jdbc.query("""
                SELECT state FROM classroom_report_dispatch
                WHERE media_id=? AND objective_report_id=?""",
                (rs,n)->rs.getString(1),mediaId,objectiveReportId);
        return states.isEmpty()?null:states.get(0);
    }

    List<Row> due(int limit,LocalDateTime now) {
        return jdbc.query("""
                SELECT id,media_id FROM classroom_report_dispatch
                WHERE (state='QUEUED' AND (next_attempt_at IS NULL OR next_attempt_at<=?))
                   OR (state='CLAIMED' AND lease_until<?)
                ORDER BY id LIMIT ?""",(rs,n)->new Row(rs.getLong(1),rs.getLong(2)),now,now,limit);
    }

    boolean claim(long id,String owner,LocalDateTime now,LocalDateTime leaseUntil) {
        return jdbc.update("""
                UPDATE classroom_report_dispatch SET state='CLAIMED',lease_owner=?,lease_until=?,
                    updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND ((state='QUEUED' AND (next_attempt_at IS NULL OR next_attempt_at<=?))
                    OR (state='CLAIMED' AND lease_until<?))""",
                owner,leaseUntil,id,now,now)==1;
    }

    Claim currentClaim(long id,String owner) {
        List<Claim> rows=jdbc.query("""
                SELECT run_id,pre_submit_failures,manual_generation FROM classroom_report_dispatch
                WHERE id=? AND state='CLAIMED' AND lease_owner=?""",
                (rs,n)->new Claim((Long)rs.getObject(1),rs.getInt(2),rs.getInt(3)),id,owner);
        if(rows.size()!=1) throw new IllegalStateException("Report dispatch claim was lost");
        return rows.get(0);
    }

    /** Caller holds a transaction; row locking serializes supervisor decisions and idempotent replays. */
    ManualRetry manualRetry(long mediaId,long objectiveReportId,long userId,String requestKeySha256) {
        List<ManualState> rows=jdbc.query("""
                SELECT id,state,run_id,error_code,manual_generation FROM classroom_report_dispatch
                WHERE media_id=? AND objective_report_id=? FOR UPDATE""",
                (rs,n)->new ManualState(rs.getLong(1),rs.getString(2),(Long)rs.getObject(3),
                        rs.getString(4),rs.getInt(5)),mediaId,objectiveReportId);
        if(rows.size()!=1) throw new ClassroomAnalysisException("REPORT_RETRY_NOT_AVAILABLE",
                "当前课堂没有可重试的报告任务",409);
        var row=rows.get(0);
        List<RetryRequest> repeats=jdbc.query("""
                SELECT generation,requested_by_user_id FROM classroom_report_retry_request
                WHERE dispatch_id=? AND request_key_sha256=?""",
                (rs,n)->new RetryRequest(rs.getInt(1),rs.getLong(2)),row.id(),requestKeySha256);
        if(!repeats.isEmpty()) {
            if(repeats.get(0).userId()!=userId) throw new ClassroomAnalysisException("REPORT_RETRY_KEY_REUSED",
                    "该操作键已由其他督导使用",409);
            return new ManualRetry(true);
        }
        if(!"NEEDS_REVIEW".equals(row.state())) throw new ClassroomAnalysisException("REPORT_RETRY_NOT_AVAILABLE",
                "报告任务当前不需要人工重试",409);
        if(jdbc.queryForObject("""
                SELECT COUNT(*) FROM classroom_generated_report g
                JOIN classroom_analysis_run r ON r.id=g.run_id WHERE r.media_id=?""",
                Integer.class,mediaId)>0)
            throw new ClassroomAnalysisException("REPORT_ALREADY_AVAILABLE","报告已生成，不能再次计费",409);
        int next=Math.addExact(row.generation(),1);
        jdbc.update("""
                INSERT INTO classroom_report_retry_request
                (dispatch_id,generation,previous_run_id,requested_by_user_id,request_key_sha256,previous_error_code)
                VALUES (?,?,?,?,?,?)""",row.id(),next,row.runId(),userId,requestKeySha256,row.errorCode());
        if(jdbc.update("""
                UPDATE classroom_report_dispatch SET state='QUEUED',run_id=NULL,manual_generation=?,
                    pre_submit_failures=0,next_attempt_at=NULL,error_code=NULL,
                    lease_owner=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND state='NEEDS_REVIEW' AND manual_generation=?
                    AND NOT EXISTS (SELECT 1 FROM classroom_generated_report g
                        JOIN classroom_analysis_run r ON r.id=g.run_id
                        WHERE r.media_id=classroom_report_dispatch.media_id)""",
                next,row.id(),row.generation())!=1)
            throw new ClassroomAnalysisException("REPORT_RETRY_NOT_AVAILABLE","报告状态已变化，请刷新后重试",409);
        return new ManualRetry(false);
    }

    void attachRun(long id,String owner,long runId) {
        if(jdbc.update("""
                UPDATE classroom_report_dispatch SET run_id=?,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND state='CLAIMED' AND lease_owner=? AND (run_id IS NULL OR run_id=?)
                  AND EXISTS (SELECT 1 FROM classroom_analysis_run r WHERE r.id=?
                    AND r.media_id=classroom_report_dispatch.media_id
                    AND r.objective_report_id=classroom_report_dispatch.objective_report_id)""",
                runId,id,owner,runId,runId)!=1)
            throw new IllegalStateException("Report dispatch claim or run binding was lost");
    }

    /** A local failure before HTTP may be retried a bounded number of times. */
    String deferPreSubmission(long id,String owner,int previousFailures,String errorCode,
                              LocalDateTime nextAttemptAt,int maxFailures) {
        int failures=previousFailures+1;
        String state=failures>=maxFailures?"NEEDS_REVIEW":"QUEUED";
        if(jdbc.update("""
                UPDATE classroom_report_dispatch SET state=?,pre_submit_failures=?,next_attempt_at=?,
                    error_code=?,lease_owner=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND state='CLAIMED' AND lease_owner=? AND pre_submit_failures=?
                    AND NOT EXISTS (SELECT 1 FROM classroom_generated_report g WHERE g.run_id=classroom_report_dispatch.run_id)""",
                state,failures,"QUEUED".equals(state)?nextAttemptAt:null,errorCode,id,owner,
                previousFailures)==1) return state;
        return "CLAIM_LOST";
    }

    /** Budget exhaustion does not make a never-submitted job permanently terminal. */
    void waitForBudget(long id,String owner,LocalDateTime nextAttemptAt) {
        jdbc.update("""
                UPDATE classroom_report_dispatch SET state='QUEUED',next_attempt_at=?,
                    error_code='PAID_TEST_BUDGET_EXHAUSTED',lease_owner=NULL,lease_until=NULL,
                    updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND state='CLAIMED' AND lease_owner=?
                    AND NOT EXISTS (SELECT 1 FROM classroom_generated_report g WHERE g.run_id=classroom_report_dispatch.run_id)""",
                nextAttemptAt,id,owner);
    }

    /** Never downgrade a committed generated report, even after lease ownership changes. */
    void markNeedsReview(long id,String owner,String errorCode) {
        jdbc.update("""
                UPDATE classroom_report_dispatch SET state='NEEDS_REVIEW',error_code=?,
                    next_attempt_at=NULL,lease_owner=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND state='CLAIMED' AND lease_owner=?
                    AND NOT EXISTS (SELECT 1 FROM classroom_generated_report g WHERE g.run_id=classroom_report_dispatch.run_id)""",
                errorCode,id,owner);
    }

    /** Completion is based on persisted evidence, not on the now possibly expired lease. */
    boolean completeIfReportPresent(long id,long runId) {
        jdbc.update("""
                UPDATE classroom_report_dispatch SET state='COMPLETED',error_code=NULL,
                    next_attempt_at=NULL,lease_owner=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND run_id=? AND EXISTS (SELECT 1 FROM classroom_generated_report g
                    JOIN classroom_analysis_run r ON r.id=g.run_id AND r.state='COMPLETED'
                    WHERE g.run_id=classroom_report_dispatch.run_id)
                """,id,runId);
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM classroom_report_dispatch WHERE id=? AND run_id=? AND state='COMPLETED'""",
                Integer.class,id,runId)==1;
    }

    /** A late result from a prior attempt wins before a new, potentially chargeable submission. */
    boolean completeFromSavedReport(long id,String owner,long savedRunId) {
        return jdbc.update("""
                UPDATE classroom_report_dispatch SET run_id=?,state='COMPLETED',error_code=NULL,
                    next_attempt_at=NULL,lease_owner=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND state='CLAIMED' AND lease_owner=?
                    AND EXISTS (SELECT 1 FROM classroom_generated_report g
                        JOIN classroom_analysis_run r ON r.id=g.run_id AND r.state='COMPLETED'
                        WHERE g.run_id=? AND r.media_id=classroom_report_dispatch.media_id)
                """,savedRunId,id,owner,savedRunId)==1;
    }

    List<CompletionCandidate> completionCandidates(int limit,long afterId) {
        return jdbc.query("""
                SELECT d.id,d.run_id FROM classroom_report_dispatch d
                JOIN classroom_generated_report g ON g.run_id=d.run_id
                WHERE d.state<>'COMPLETED' AND d.id>? ORDER BY d.id LIMIT ?""",
                (rs,n)->new CompletionCandidate(rs.getLong(1),rs.getLong(2)),afterId,limit);
    }

    record Row(long id,long mediaId) {}
    record Claim(Long runId,int preSubmitFailures,int manualGeneration) {
        Claim(Long runId,int preSubmitFailures) { this(runId,preSubmitFailures,0); }
    }
    record CompletionCandidate(long dispatchId,long runId) {}
    record ManualRetry(boolean replayed) {}
    private record ManualState(long id,String state,Long runId,String errorCode,int generation) {}
    private record RetryRequest(int generation,long userId) {}
}
