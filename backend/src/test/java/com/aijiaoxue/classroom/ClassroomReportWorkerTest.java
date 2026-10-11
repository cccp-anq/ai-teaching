package com.aijiaoxue.classroom;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClassroomReportWorkerTest {
    private final ClassroomReportProperties properties=new ClassroomReportProperties();
    private final ClassroomReportDispatch dispatch=mock(ClassroomReportDispatch.class);
    private final ClassroomAnalysisRunService runs=mock(ClassroomAnalysisRunService.class);
    private final ClassroomPaidTestBudgetService budget=mock(ClassroomPaidTestBudgetService.class);
    private final ClassroomGeneratedReportStore reports=mock(ClassroomGeneratedReportStore.class);
    private final ClassroomReportWorker worker=new ClassroomReportWorker(properties,dispatch,runs,budget,
            reports,new ObjectMapper());

    @Test void disabledWorkerNeverClaimsOrSubmits() {
        worker.runOnce();
        verifyNoInteractions(dispatch,runs,budget,reports);
    }

    @Test void invalidPaidDraftHasAReviewableInternalCodeWithoutExposingItsText() {
        assertEquals("REPORT_DRAFT_INVALID",ClassroomReportWorker.code(
                new ClassroomAnalysisOutputValidator.OutputViolation()));
        assertEquals("REPORT_DRAFT_EVIDENCE_INVALID",ClassroomReportWorker.code(
                new ClassroomAnalysisEvidenceValidator.EvidenceViolation()));
    }

    @Test void invalidEnabledConfigurationNeverClaims() {
        properties.setEnabled(true);
        worker.runOnce();
        verifyNoInteractions(dispatch,runs,budget,reports);
    }

    @Test void budgetExhaustionWaitsWithoutSubmittingOrBecomingTerminal() {
        configureLoopback();
        when(dispatch.due(eq(2),any(LocalDateTime.class)))
                .thenReturn(List.of(new ClassroomReportDispatch.Row(17,27)));
        when(dispatch.claim(eq(17L),anyString(),any(LocalDateTime.class),any(LocalDateTime.class)))
                .thenReturn(true);
        when(dispatch.currentClaim(eq(17L),anyString()))
                .thenReturn(new ClassroomReportDispatch.Claim(null,0));
        when(runs.prepare(27,"auto-report-v1:17"))
                .thenReturn(new ClassroomAnalysisRunService.PreparedRun(37,"PREPARED","a".repeat(64),false));
        var coverage=new ClassroomAnalysisInputSnapshotService.Coverage(20_000,1);
        when(runs.prepareEgress(37)).thenReturn(new ClassroomAnalysisEgressPacket.Packet(
                ClassroomAnalysisEgressPacket.VERSION,37,"a".repeat(64),20_000,11,22,
                new ClassroomAnalysisInputSnapshotService.Statistics(0.75,1),
                new ClassroomAnalysisInputSnapshotService.Completeness(coverage,coverage,coverage,coverage),
                List.of(),List.of(),List.of(),List.of("测试限制")));
        when(budget.reserve(eq("REPORT"),eq(37L),anyString(),eq("qwen3.7-plus"),eq(100),eq(2000)))
                .thenThrow(new ClassroomPaidTestBudgetService.BudgetViolation("PAID_TEST_BUDGET_EXHAUSTED"));

        worker.runOnce();

        verify(dispatch).attachRun(eq(17L),anyString(),eq(37L));
        verify(dispatch).waitForBudget(eq(17L),anyString(),any(LocalDateTime.class));
        verify(dispatch,never()).markNeedsReview(anyLong(),anyString(),anyString());
        verify(budget).reserve(eq("REPORT"),eq(37L),anyString(),eq("qwen3.7-plus"),eq(100),eq(2000));
        verifyNoMoreInteractions(budget);
    }

    @Test void completedReportIsRecognizedAfterLeaseRecoveryWithoutRebuildingSource() {
        configureLoopback();
        when(dispatch.due(eq(2),any(LocalDateTime.class)))
                .thenReturn(List.of(new ClassroomReportDispatch.Row(17,27)));
        when(dispatch.claim(eq(17L),anyString(),any(LocalDateTime.class),any(LocalDateTime.class)))
                .thenReturn(true);
        when(dispatch.currentClaim(eq(17L),anyString()))
                .thenReturn(new ClassroomReportDispatch.Claim(37L,0));
        when(dispatch.completeIfReportPresent(17,37)).thenReturn(true);
        when(reports.report(37)).thenReturn(new ClassroomGeneratedReportStore.StoredReport(47,37,57,
                "a".repeat(64),"qwen3.7-plus","b".repeat(64),"c".repeat(64),"{}"));

        worker.runOnce();

        verifyNoInteractions(runs,budget);
        verify(dispatch).completeIfReportPresent(17,37);
    }

    @Test void latePriorReportCompletesManualRetryWithoutPreparingOrSubmittingAgain() {
        configureLoopback();
        when(dispatch.due(eq(2),any(LocalDateTime.class)))
                .thenReturn(List.of(new ClassroomReportDispatch.Row(17,27)));
        when(dispatch.claim(eq(17L),anyString(),any(LocalDateTime.class),any(LocalDateTime.class)))
                .thenReturn(true);
        when(dispatch.currentClaim(eq(17L),anyString()))
                .thenReturn(new ClassroomReportDispatch.Claim(null,0,1));
        when(reports.latestForMedia(27)).thenReturn(new ClassroomGeneratedReportStore.StoredReport(47,37,57,
                "a".repeat(64),"qwen3.7-plus","b".repeat(64),"c".repeat(64),"{}"));
        when(dispatch.completeFromSavedReport(eq(17L),anyString(),eq(37L))).thenReturn(true);

        worker.runOnce();

        verify(dispatch).completeFromSavedReport(eq(17L),anyString(),eq(37L));
        verifyNoInteractions(runs,budget);
        verify(reports,never()).report(anyLong());
    }

    @Test void replayFindsConcurrentCompletionWithoutSubmittingAgain() {
        configureLoopback();
        when(dispatch.due(eq(2),any(LocalDateTime.class)))
                .thenReturn(List.of(new ClassroomReportDispatch.Row(17,27)));
        when(dispatch.claim(eq(17L),anyString(),any(LocalDateTime.class),any(LocalDateTime.class)))
                .thenReturn(true);
        when(dispatch.currentClaim(eq(17L),anyString()))
                .thenReturn(new ClassroomReportDispatch.Claim(37L,0));
        when(dispatch.completeIfReportPresent(17,37)).thenReturn(true);
        var saved=new ClassroomGeneratedReportStore.StoredReport(47,37,57,
                "a".repeat(64),"qwen3.7-plus","b".repeat(64),"c".repeat(64),"{}");
        when(reports.report(37)).thenReturn(null,saved);
        var coverage=new ClassroomAnalysisInputSnapshotService.Coverage(20_000,1);
        when(runs.prepareEgress(37)).thenReturn(new ClassroomAnalysisEgressPacket.Packet(
                ClassroomAnalysisEgressPacket.VERSION,37,"a".repeat(64),20_000,11,22,
                new ClassroomAnalysisInputSnapshotService.Statistics(0.75,1),
                new ClassroomAnalysisInputSnapshotService.Completeness(coverage,coverage,coverage,coverage),
                List.of(),List.of(),List.of(),List.of("测试限制")));
        var row=new ClassroomPaidTestBudgetService.Row(57,"b".repeat(64),"REPORT",37,
                "qwen3.7-plus",100,"SUCCEEDED","provider-local",20L,30L,50L,"c".repeat(64));
        when(budget.reserve(eq("REPORT"),eq(37L),anyString(),eq("qwen3.7-plus"),eq(100),eq(2000)))
                .thenReturn(new ClassroomPaidTestBudgetService.Reservation(row,true));

        worker.runOnce();

        verify(dispatch).completeIfReportPresent(17,37);
        verify(budget,never()).tryStartSubmission(anyLong());
    }

    @Test void localFailureBeforeSubmissionUsesBoundedRetry() {
        configureLoopback();
        when(dispatch.due(eq(2),any(LocalDateTime.class)))
                .thenReturn(List.of(new ClassroomReportDispatch.Row(17,27)));
        when(dispatch.claim(eq(17L),anyString(),any(LocalDateTime.class),any(LocalDateTime.class)))
                .thenReturn(true);
        when(dispatch.currentClaim(eq(17L),anyString()))
                .thenReturn(new ClassroomReportDispatch.Claim(null,2));
        when(runs.prepare(27,"auto-report-v1:17"))
                .thenThrow(new ClassroomAnalysisException("INPUT_NOT_READY","local fixture",422));

        worker.runOnce();

        verify(dispatch).deferPreSubmission(eq(17L),anyString(),eq(2),eq("INPUT_NOT_READY"),
                any(LocalDateTime.class),eq(3));
        verifyNoInteractions(budget);
        verify(reports).latestForMedia(27);
        verifyNoMoreInteractions(reports);
    }

    @Test void unreadableCompletionCandidateDoesNotStarveLaterReports() {
        configureLoopback();
        var firstPage=IntStream.rangeClosed(1,20)
                .mapToObj(id->new ClassroomReportDispatch.CompletionCandidate(id,100+id)).toList();
        when(dispatch.completionCandidates(20,0)).thenReturn(firstPage);
        when(dispatch.completionCandidates(20,20)).thenReturn(List.of(
                new ClassroomReportDispatch.CompletionCandidate(21,121)));
        when(dispatch.completionCandidates(20,21)).thenReturn(List.of());
        when(reports.report(101)).thenThrow(new IllegalStateException("unreadable saved report"));
        when(reports.report(121)).thenReturn(new ClassroomGeneratedReportStore.StoredReport(221,121,321,
                "a".repeat(64),"qwen3.7-plus","b".repeat(64),"c".repeat(64),"{}"));

        worker.runOnce();
        worker.runOnce();
        worker.runOnce();

        verify(dispatch).completeIfReportPresent(21,121);
        verify(dispatch).completionCandidates(20,20);
        verify(dispatch).completionCandidates(20,21);
        verify(dispatch,times(2)).completionCandidates(20,0);
        verifyNoInteractions(runs,budget);
    }

    private void configureLoopback() {
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:1/compatible-mode/v1");
        properties.setAllowLoopbackHttp(true);
        properties.setApiKey("unit-test-key");
        assertTrue(properties.ready());
    }
}
