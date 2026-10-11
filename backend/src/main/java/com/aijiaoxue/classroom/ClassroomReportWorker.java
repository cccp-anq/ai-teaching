package com.aijiaoxue.classroom;

import java.time.LocalDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** At most one automatic submission per immutable run; uncertain calls need manual review. */
@Component
class ClassroomReportWorker {
    private static final Logger LOG=LoggerFactory.getLogger(ClassroomReportWorker.class);
    private static final int MAX_PRE_SUBMISSION_FAILURES=3;
    private final ClassroomReportProperties properties;
    private final ClassroomReportDispatch dispatch;
    private final ClassroomAnalysisRunService runs;
    private final ClassroomPaidTestBudgetService budget;
    private final ClassroomGeneratedReportStore reports;
    private final ObjectMapper mapper;
    private final String owner="report-worker-"+UUID.randomUUID();
    private long reconciliationCursor;

    ClassroomReportWorker(ClassroomReportProperties properties,ClassroomReportDispatch dispatch,
                          ClassroomAnalysisRunService runs,ClassroomPaidTestBudgetService budget,
                          ClassroomGeneratedReportStore reports,ObjectMapper mapper) {
        this.properties=properties; this.dispatch=dispatch; this.runs=runs;
        this.budget=budget; this.reports=reports; this.mapper=mapper;
    }

    @Scheduled(fixedDelayString="${app.classroom-analysis.report.worker-delay-ms:5000}",
            initialDelayString="${app.classroom-analysis.report.worker-initial-delay-ms:60000}")
    void scheduledRun() { runOnce(); }

    void runOnce() {
        if(!properties.isEnabled()) return;
        if(!properties.ready()) {
            LOG.warn("Classroom report worker is enabled but its configuration is incomplete");
            return;
        }
        reconcileCompleted();
        var client=new ClassroomReportModelClient(properties.settings(),mapper);
        var invocation=new ClassroomReportModelInvocation(runs,budget,client,reports);
        for(var row:dispatch.due(properties.getBatchSize(),LocalDateTime.now())) {
            LocalDateTime now=LocalDateTime.now();
            if(!dispatch.claim(row.id(),owner,now,now.plusSeconds(properties.getLeaseSeconds()))) continue;
            ClassroomReportDispatch.Claim claim=null;
            Long runId=null;
            boolean invocationStarted=false;
            try {
                claim=dispatch.currentClaim(row.id(),owner);
                var saved=reports.latestForMedia(row.mediaId());
                if(saved!=null) {
                    if(!dispatch.completeFromSavedReport(row.id(),owner,saved.runId()))
                        throw new IllegalStateException("Saved report could not complete dispatch");
                    continue;
                }
                runId=claim.runId()!=null?claim.runId():runs.prepare(row.mediaId(),
                        claim.manualGeneration()==0?"auto-report-v1:"+row.id()
                                :"auto-report-v1:"+row.id()+":manual-"+claim.manualGeneration()).id();
                dispatch.attachRun(row.id(),owner,runId);
                if(reports.report(runId)!=null) {
                    requireCompleted(row.id(),runId);
                    continue;
                }
                saved=reports.latestForMedia(row.mediaId());
                if(saved!=null) {
                    if(!dispatch.completeFromSavedReport(row.id(),owner,saved.runId()))
                        throw new IllegalStateException("Saved report could not complete dispatch");
                    continue;
                }
                invocationStarted=true;
                var result=invocation.invoke(runId,properties.getCallCeilingCents(),
                        properties.getTotalCapCents());
                if(reports.report(runId)!=null) requireCompleted(row.id(),runId);
                else if(result.replayed()) dispatch.markNeedsReview(row.id(),owner,"REPORT_SUBMISSION_ALREADY_RECORDED");
                else throw new IllegalStateException("Persisted report is missing after model invocation");
            } catch(RuntimeException failure) {
                RuntimeException cause=failure instanceof ClassroomReportModelInvocation.PreSubmissionFailure
                        && failure.getCause() instanceof RuntimeException wrapped?wrapped:failure;
                String code=code(cause);
                if(!invocationStarted||failure instanceof ClassroomReportModelInvocation.PreSubmissionFailure) {
                    if("PAID_TEST_BUDGET_EXHAUSTED".equals(code))
                        dispatch.waitForBudget(row.id(),owner,LocalDateTime.now().plusMinutes(5));
                    else if(claim!=null)
                        dispatch.deferPreSubmission(row.id(),owner,claim.preSubmitFailures(),code,
                                LocalDateTime.now().plusSeconds(30L<<Math.min(claim.preSubmitFailures(),2)),
                                MAX_PRE_SUBMISSION_FAILURES);
                    LOG.warn("Classroom report pre-submission deferred: dispatchId={}, code={}",row.id(),code);
                } else {
                    // A possibly chargeable submission is never retried. A peer may have finished
                    // after this lease expired, so check persisted evidence before marking review.
                    if(runId==null||!completeExisting(row.id(),runId))
                        dispatch.markNeedsReview(row.id(),owner,code);
                    LOG.warn("Classroom report submission needs review: dispatchId={}, code={}",row.id(),code);
                }
            }
        }
    }

    private boolean completeExisting(long dispatchId,long runId) {
        try { return reports.report(runId)!=null && dispatch.completeIfReportPresent(dispatchId,runId); }
        catch(RuntimeException failure) { return false; }
    }

    private void requireCompleted(long dispatchId,long runId) {
        if(!dispatch.completeIfReportPresent(dispatchId,runId))
            throw new IllegalStateException("Persisted report is not bound to dispatch");
    }

    private synchronized void reconcileCompleted() {
        var candidates=dispatch.completionCandidates(20,reconciliationCursor);
        if(candidates.isEmpty()&&reconciliationCursor!=0) {
            reconciliationCursor=0;
            candidates=dispatch.completionCandidates(20,0);
        }
        for(var candidate:candidates) {
            // Advance even if one saved report is unreadable, so it cannot starve later rows.
            reconciliationCursor=candidate.dispatchId();
            try {
                if(reports.report(candidate.runId())!=null)
                    dispatch.completeIfReportPresent(candidate.dispatchId(),candidate.runId());
            } catch(RuntimeException failure) {
                LOG.warn("Classroom report completion reconciliation failed: dispatchId={}",candidate.dispatchId());
            }
        }
    }

    static String code(RuntimeException failure) {
        if(failure instanceof ClassroomPaidTestBudgetService.BudgetViolation violation) return violation.code();
        if(failure instanceof ClassroomReportModelClient.ModelCallFailure model) return model.code();
        if(failure instanceof ClassroomAnalysisException analysis) return analysis.code();
        if(failure instanceof ClassroomAnalysisOutputValidator.OutputViolation violation)
            return violation.code();
        if(failure instanceof ClassroomAnalysisEvidenceValidator.EvidenceViolation)
            return "REPORT_DRAFT_EVIDENCE_INVALID";
        return "REPORT_PROCESSING_FAILED";
    }
}
