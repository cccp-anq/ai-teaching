package com.aijiaoxue.classroom;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Internal report path; a caller must reserve before submitting. */
final class ClassroomReportModelInvocation {
    private final ClassroomAnalysisRunService runs;
    private final ClassroomPaidTestBudgetService budget;
    private final ClassroomReportModelClient client;
    private final ClassroomGeneratedReportStore reports;

    ClassroomReportModelInvocation(ClassroomAnalysisRunService runs,ClassroomPaidTestBudgetService budget,
                                   ClassroomReportModelClient client,ClassroomGeneratedReportStore reports) {
        if(runs==null||budget==null||client==null||reports==null)
            throw new IllegalArgumentException("Report invocation dependency missing");
        this.runs=runs; this.budget=budget; this.client=client; this.reports=reports;
    }

    /** One run permits one submission; an unstarted RESERVED reservation may resume safely. */
    Result invoke(long runId,int callCeilingCents,int approvedTotalCapCents) {
        if(runId<=0) throw new IllegalArgumentException("Report run id is invalid");
        ClassroomAnalysisEgressPacket.Packet packet;
        long reservationId;
        try {
            packet=runs.prepareEgress(runId);
            if(packet==null||packet.runId()!=runId||packet.inputSha256()==null)
                throw new IllegalStateException("Report egress packet does not match run");
            String fingerprint=sha256("classroom-report-run-v1:"+runId+":"+packet.inputSha256());
            var reservation=budget.reserve("REPORT",runId,fingerprint,client.modelId(),
                    callCeilingCents,approvedTotalCapCents);
            reservationId=reservation.row().id();
            // RESERVED proves no caller has crossed the submission boundary yet. A conditional
            // transition lets an expired-lease peer resume it without permitting two HTTP calls.
            if(reservation.replayed()&&!"RESERVED".equals(reservation.row().state()))
                return new Result(reservationId,true,null);
            // A crash after this commit may have sent a chargeable request. Never auto-submit
            // a SUBMISSION_UNKNOWN or SUCCEEDED replay.
            if(!budget.tryStartSubmission(reservationId)) return new Result(reservationId,true,null);
        } catch(RuntimeException failure) {
            // No HTTP submission has started in this invocation. The reservation ledger still
            // prevents a second submission if its transaction committed before an error surfaced.
            throw new PreSubmissionFailure(failure);
        }
        var response=client.submit(packet);
        budget.markSucceeded(reservationId,response.providerRequestId(),response.inputTokens(),
                response.outputTokens(),response.totalTokens(),sha256(response.content()));
        // Even a malformed draft may be billed, so record the response metadata before validation.
        var report=reports.persist(runId,reservationId,client.modelId(),response.content());
        return new Result(reservationId,false,report);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception impossible) { throw new IllegalStateException(impossible); }
    }

    record Result(long reservationId,boolean replayed,ClassroomGeneratedReportStore.StoredReport report) {}

    static final class PreSubmissionFailure extends RuntimeException {
        PreSubmissionFailure(RuntimeException cause) { super("REPORT_PRE_SUBMISSION_FAILED",cause); }
    }
}
