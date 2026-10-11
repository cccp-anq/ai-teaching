package com.aijiaoxue.classroom;

import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

@RestController
public class ClassroomAnalysisController {
    private final ClassroomAnalysisService service;
    public ClassroomAnalysisController(ClassroomAnalysisService service) { this.service=service; }

    @PostMapping(value="/course-offerings/{offeringId}/class-sessions/video",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ClassroomAnalysisView> upload(@PathVariable long offeringId,
            @RequestHeader(value="Idempotency-Key",required=false) String idempotencyKey,
            @RequestParam long scheduleId,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate sessionDate,
            @RequestParam(required=false) String title,
            @RequestParam long durationMs,
            @RequestParam MultipartFile video) {
        var result=service.upload(offeringId,scheduleId,sessionDate,title,durationMs,idempotencyKey,video);
        return ResponseEntity.status(result.replayed()?200:201)
                .header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(result.view());
    }
    @GetMapping("/course-offerings/{offeringId}/class-sessions")
    List<ClassroomAnalysisView> list(@PathVariable long offeringId) { return service.list(offeringId); }
    @GetMapping("/class-sessions/{sessionId}")
    ClassroomAnalysisView get(@PathVariable long sessionId) { return service.get(sessionId); }
    @GetMapping("/class-sessions/media/{mediaId}/generated-report")
    ResponseEntity<JsonNode> generatedReport(@PathVariable long mediaId) {
        var report=service.generatedReport(mediaId);
        return report==null ? ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL,"private, no-store").build()
                : ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(report);
    }
    @GetMapping("/class-sessions/media/{mediaId}/generated-report/versions")
    ResponseEntity<ClassroomAnalysisService.GeneratedReportVersions> generatedReportVersions(
            @PathVariable long mediaId,@RequestParam(defaultValue="0") int page) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store")
                .body(service.generatedReportVersions(mediaId,page));
    }
    @GetMapping("/class-sessions/media/{mediaId}/generated-report/progress")
    ResponseEntity<ClassroomAnalysisService.GeneratedReportProgress> generatedReportProgress(@PathVariable long mediaId) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store")
                .body(service.generatedReportProgress(mediaId));
    }
    @PostMapping(value="/class-sessions/media/{mediaId}/generated-report/retry",
            consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ClassroomAnalysisService.ManualRetryResult> retryGeneratedReport(@PathVariable long mediaId,
            @RequestHeader(value="Idempotency-Key",required=false) String idempotencyKey,
            @RequestBody RetryConfirmation confirmation) {
        var result=service.requestGeneratedReportRetry(mediaId,idempotencyKey,
                confirmation.confirmAdditionalCharge());
        return ResponseEntity.status(result.replayed()?200:202)
                .header(HttpHeaders.CACHE_CONTROL,"private, no-store")
                .header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(result);
    }
    record RetryConfirmation(boolean confirmAdditionalCharge) {}

    @PostMapping(value="/class-sessions/media/{mediaId}/transcription-result/recover",
            consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ClassroomAnalysisService.RecoveryResult> recoverTranscription(@PathVariable long mediaId,
            @RequestHeader(value="Idempotency-Key",required=false) String idempotencyKey,
            @RequestBody RecoveryConfirmation confirmation) {
        var result=service.recoverExistingTranscription(mediaId,idempotencyKey,
                confirmation.confirmExistingResult());
        return ResponseEntity.status(result.replayed()?200:202)
                .header(HttpHeaders.CACHE_CONTROL,"private, no-store")
                .header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(result);
    }
    record RecoveryConfirmation(boolean confirmExistingResult) {}
    @PostMapping(value="/class-sessions/media/{mediaId}/module-jobs/retry",
            consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ClassroomAnalysisService.ManualModuleRetryResult> retryFailedModuleJobs(@PathVariable long mediaId,
            @RequestHeader(value="Idempotency-Key",required=false) String idempotencyKey,
            @RequestBody RetryConfirmation confirmation) {
        var result=service.requestFailedModuleRetry(mediaId,idempotencyKey,
                confirmation.confirmAdditionalCharge());
        return ResponseEntity.status(result.replayed()?200:202)
                .header(HttpHeaders.CACHE_CONTROL,"private, no-store")
                .header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(result);
    }
    @GetMapping("/class-sessions/media/{mediaId}/content")
    ResponseEntity<FileSystemResource> media(@PathVariable long mediaId) {
        var item=service.media(mediaId);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store")
                .contentType(MediaType.parseMediaType(item.contentType())).contentLength(item.size()).body(item.resource());
    }
    @GetMapping("/classroom-module-media/{mediaId}")
    ResponseEntity<FileSystemResource> moduleMedia(@PathVariable long mediaId,
            @RequestParam long jobId,@RequestParam String moduleType,@RequestParam int attempt,
            @RequestParam long expires,@RequestParam String signature) {
        var item=service.moduleMedia(mediaId,jobId,moduleType,attempt,expires,signature);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store")
                .contentType(MediaType.parseMediaType(item.contentType())).contentLength(item.size()).body(item.resource());
    }
    @PostMapping(value="/class-sessions/module-jobs/{jobId}/result",consumes=MediaType.APPLICATION_JSON_VALUE)
    ClassroomAnalysisView importModuleResult(@PathVariable long jobId,@RequestBody JsonNode result) {
        return service.importModuleResult(jobId,result);
    }
}
