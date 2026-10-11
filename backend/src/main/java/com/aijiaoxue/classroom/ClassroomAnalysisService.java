package com.aijiaoxue.classroom;

import com.aijiaoxue.identity.CurrentUserProvider;
import com.aijiaoxue.identity.CurrentUser;
import com.aijiaoxue.identity.NotVisibleException;
import com.aijiaoxue.identity.PermissionService;
import com.aijiaoxue.identity.AuthorizedScopeProvider;
import com.aijiaoxue.course.access.AuthorizedOfferingRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ClassroomAnalysisService {
    private static final List<String> TYPES = List.of("video/mp4", "video/webm");
    private static final Pattern IDEMPOTENCY_KEY=Pattern.compile("[A-Za-z0-9._:-]{8,128}");
    private final ClassroomAnalysisRepository repository;
    private final AuthorizedOfferingRepository offerings;
    private final CurrentUserProvider currentUsers;
    private final PermissionService permissions;
    private final ClassroomAnalysisProperties properties;
    private final ClassroomMediaProbe mediaProbe;
    private final ClassroomModuleResultService moduleResults;
    private final ClassroomModuleHttpClient moduleClient;
    private final ClassroomMediaSigner mediaSigner;
    private final ClassroomGeneratedReportStore generatedReports;
    private final ClassroomReportDispatch reportDispatch;
    private final ClassroomReportProperties reportProperties;
    private final ObjectMapper json;
    private final Path storageRoot;
    private final TransactionTemplate transactions;

    public ClassroomAnalysisService(ClassroomAnalysisRepository repository, AuthorizedOfferingRepository offerings,
                                    CurrentUserProvider currentUsers, PermissionService permissions,
                                    ClassroomAnalysisProperties properties, ClassroomMediaProbe mediaProbe,
                                    ClassroomModuleResultService moduleResults,
                                    ClassroomModuleHttpClient moduleClient,
                                    ClassroomMediaSigner mediaSigner,
                                    ClassroomGeneratedReportStore generatedReports,
                                    ClassroomReportDispatch reportDispatch,
                                     ClassroomReportProperties reportProperties,
                                     ObjectMapper json,
                                     PlatformTransactionManager transactionManager,
                                     @Value("${app.storage.root:./.work/files}") String storageRoot) {
        this.repository=repository; this.offerings=offerings; this.currentUsers=currentUsers;
        this.permissions=permissions; this.properties=properties;
        this.mediaProbe=mediaProbe;
        this.moduleResults=moduleResults;
        this.moduleClient=moduleClient;
        this.mediaSigner=mediaSigner;
        this.generatedReports=generatedReports;
        this.reportDispatch=reportDispatch;
        this.reportProperties=reportProperties;
        this.json=json;
        this.transactions=new TransactionTemplate(transactionManager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.storageRoot=Path.of(storageRoot).toAbsolutePath().normalize().resolve("classroom-media");
    }

    // A competing insert may commit after the first lookup. READ_COMMITTED lets both
    // the collision lookup and the subsequent view see the winning complete upload.
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public UploadResult upload(long offeringId, long scheduleId, LocalDate sessionDate,
                               String requestedTitle, long durationMs, String idempotencyKey,
                               MultipartFile video) {
        var user=currentUsers.requireCurrentUser();
        permissions.requireClassroomWrite(user);
        if (isGlobalAdmin(user)) offerings.requireVisible(offeringId);
        else {
            if (!user.roles().contains("SUPERVISOR"))
                throw new AccessDeniedException("SUPERVISOR or ADMIN upload required");
            if (!offerings.visibleForPurpose(user,offeringId,AuthorizedScopeProvider.Purpose.SUPERVISION))
                throw new NotVisibleException();
        }
        String requestKey=validateIdempotencyKey(idempotencyKey);
        String requestKeySha256=sha256Utf8(requestKey);
        if (!repository.scheduleBelongsToOffering(scheduleId, offeringId))
            throw invalid("SCHEDULE_MISMATCH", "所选排课不属于当前开课");
        validate(video, durationMs);
        String title=requestedTitle==null||requestedTitle.isBlank()
                ? sessionDate+" 课堂" : requestedTitle.trim();
        if (title.length()>255) throw invalid("TITLE_TOO_LONG", "课堂标题不能超过255个字符");
        String original=safeFilename(video.getOriginalFilename());
        String contentType=normalizedType(video.getContentType(), original);
        String suffix=contentType.equals("video/webm") ? ".webm" : ".mp4";
        String storageKey=UUID.randomUUID()+suffix;
        Path target=resolveStorage(storageKey);
        try {
            String sha256=sha256(video.getInputStream());
            String requestSha256=uploadRequestSha256(offeringId,scheduleId,sessionDate,title,durationMs,
                    contentType,video.getSize(),sha256);
            var existing=repository.uploadRequest(user.userId(),requestKeySha256);
            if(existing!=null) return replay(existing,requestSha256);
            Files.createDirectories(storageRoot);
            try (InputStream source = video.getInputStream()) { Files.copy(source, target); }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) deleteQuietly(target);
                }
            });
            long actualDurationMs=validateProbe(mediaProbe.probe(target),durationMs);
            long sessionId;
            try {
                sessionId=repository.insertSession(offeringId,scheduleId,sessionDate,title,user.userId(),
                        requestKeySha256,requestSha256);
            } catch(DuplicateKeyException collision) {
                deleteQuietly(target);
                existing=repository.uploadRequest(user.userId(),requestKeySha256);
                if(existing==null) throw collision;
                return replay(existing,requestSha256);
            }
            long mediaId=repository.insertMedia(sessionId,storageKey,original,contentType,video.getSize(),sha256,actualDurationMs);
            boolean externalReady=!properties.isMockModulesEnabled()&&properties.integrationReady();
            String jobState=properties.isMockModulesEnabled()||externalReady?"QUEUED":"WAITING_EXTERNAL";
            LocalDateTime deadline=externalReady?LocalDateTime.now().plusSeconds(properties.getJobDeadlineSeconds()):null;
            String transcriptProvider=properties.isMockModulesEnabled()?"builtin-mock"
                    :(externalReady?properties.getTranscription().getProviderId():"not-configured");
            String headUpProvider=properties.isMockModulesEnabled()?"builtin-mock"
                    :(externalReady?properties.getHeadUp().getProviderId():"not-configured");
            long transcript=repository.insertJob(mediaId,"TRANSCRIPTION",transcriptProvider,jobState,
                    UUID.randomUUID().toString(),deadline);
            long headUp=repository.insertJob(mediaId,"HEAD_UP",headUpProvider,jobState,
                    UUID.randomUUID().toString(),deadline);
            long report=repository.insertReport(mediaId,transcript,headUp,
                    properties.isMockModulesEnabled()?"SIMULATED":"EXTERNAL");
            if (properties.isMockModulesEnabled())
                repository.completeMock(mediaId,transcript,headUp,report,actualDurationMs);
            return new UploadResult(viewUnchecked(sessionId),false);
        } catch (RuntimeException failure) {
            deleteQuietly(target); throw failure;
        } catch (IOException failure) {
            deleteQuietly(target);
            throw new ClassroomAnalysisException("VIDEO_STORAGE_FAILED", "视频保存失败", 500);
        }
    }

    private UploadResult replay(ClassroomAnalysisRepository.UploadRequestRow existing,String requestSha256) {
        if(!requestSha256.equals(existing.requestSha256()))
            throw new ClassroomAnalysisException("IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key已用于不同的课堂视频上传请求",409);
        return new UploadResult(viewUnchecked(existing.sessionId()),true);
    }

    @Transactional(readOnly=true)
    public List<ClassroomAnalysisView> list(long offeringId) {
        var user=currentUsers.requireCurrentUser(); permissions.requireClassroomRead(user); offerings.requireVisible(offeringId);
        return repository.sessionIds(offeringId).stream().map(this::viewSummaryUnchecked).toList();
    }

    @Transactional(readOnly=true)
    public ClassroomAnalysisView get(long sessionId) {
        var user=currentUsers.requireCurrentUser(); permissions.requireClassroomRead(user);
        var row=repository.session(sessionId);
        if (row==null) throw new NotVisibleException();
        offerings.requireVisible(row.offeringId());
        return view(row);
    }

    @Transactional(readOnly=true)
    public MediaDownload media(long mediaId) {
        var user=currentUsers.requireCurrentUser(); permissions.requireClassroomRead(user);
        var access=repository.mediaAccess(mediaId);
        if (access==null) throw new NotVisibleException();
        offerings.requireVisible(access.offeringId());
        Path file=resolveStorage(access.storageKey());
        if (!Files.isRegularFile(file)) throw new ClassroomAnalysisException("MEDIA_MISSING", "视频文件不可用", 404);
        return new MediaDownload(new FileSystemResource(file),access.contentType(),access.size());
    }

    @Transactional(readOnly=true)
    public JsonNode generatedReport(long mediaId) {
        requireGeneratedReportViewer(mediaId);
        var stored=generatedReports.visibleForMedia(mediaId);
        if(stored==null) return null;
        try {
            var publicReport=json.readTree(stored.normalizedJson()).deepCopy();
            if(!publicReport.isObject()) throw new IllegalStateException("Stored generated report is invalid");
            ((tools.jackson.databind.node.ObjectNode)publicReport).remove("inputSha256");
            ((tools.jackson.databind.node.ObjectNode)publicReport).remove("runId");
            return publicReport;
        }
        catch(Exception failure) { throw new IllegalStateException("Stored generated report is invalid",failure); }
    }

    @Transactional(readOnly=true)
    public GeneratedReportVersions generatedReportVersions(long mediaId,int page) {
        requireGeneratedReportViewer(mediaId);
        if(page<0||page>1000) throw new ClassroomAnalysisException("INVALID_REPORT_VERSION_PAGE",
                "报告版本页码不合法",400);
        var versions=generatedReports.versionsForMedia(mediaId,page);
        return new GeneratedReportVersions(versions.items().stream()
                .map(item->new GeneratedReportVersion(item.reportId(),item.generatedAt(),item.current()))
                .toList(),versions.nextPage());
    }

    public record GeneratedReportVersion(long reportId,java.time.LocalDateTime generatedAt,boolean current) {}
    public record GeneratedReportVersions(List<GeneratedReportVersion> items,Integer nextPage) {}

    @Transactional(readOnly=true)
    public GeneratedReportProgress generatedReportProgress(long mediaId) {
        requireGeneratedReportViewer(mediaId);
        if(generatedReports.visibleForMedia(mediaId)!=null) return new GeneratedReportProgress("COMPLETED");
        var objective=repository.report(mediaId);
        if(objective==null || !"EXTERNAL".equals(objective.mode()))
            return new GeneratedReportProgress("NOT_APPLICABLE");
        if("FAILED".equals(objective.status())) return new GeneratedReportProgress("FAILED_INPUT");
        if(!"READY".equals(objective.status())) return new GeneratedReportProgress("WAITING_INPUT");
        String state=reportDispatch.stateForMedia(mediaId,objective.id());
        if(state==null) return new GeneratedReportProgress("NOT_QUEUED");
        if("NEEDS_REVIEW".equals(state)||"COMPLETED".equals(state))
            return new GeneratedReportProgress("NEEDS_REVIEW");
        if("CLAIMED".equals(state)) return new GeneratedReportProgress("PROCESSING");
        if(!"QUEUED".equals(state)) return new GeneratedReportProgress("NEEDS_REVIEW");
        return new GeneratedReportProgress(reportProperties.ready()?"QUEUED":"PAUSED");
    }

    private void requireGeneratedReportViewer(long mediaId) {
        var user=currentUsers.requireCurrentUser();
        var access=repository.mediaAccess(mediaId);
        if(access==null) throw new NotVisibleException();
        boolean director=user.roles().contains("DIRECTOR") && user.authorizationScopes().stream()
                .anyMatch(grant->"DIRECTOR".equals(grant.roleCode())
                        && "GLOBAL".equals(grant.scopeType()) && grant.collegeId()==null
                        && grant.teacherId()==null && grant.offeringId()==null);
        boolean teacher=offerings.visibleForPurpose(user,access.offeringId(),
                AuthorizedScopeProvider.Purpose.TEACHING);
        boolean supervisor=offerings.visibleForPurpose(user,access.offeringId(),
                AuthorizedScopeProvider.Purpose.SUPERVISION);
        if(!isGlobalAdmin(user)&&!director&&!teacher&&!supervisor) throw new NotVisibleException();
        offerings.requireVisible(access.offeringId());
    }

    private static boolean isGlobalAdmin(CurrentUser user) {
        return user.roles().contains("ADMIN") && user.authorizationScopes().stream()
                .anyMatch(grant->"ADMIN".equals(grant.roleCode())
                        && "GLOBAL".equals(grant.scopeType()) && grant.collegeId()==null
                        && grant.teacherId()==null && grant.offeringId()==null);
    }

    public record GeneratedReportProgress(String state) {}

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public ManualRetryResult requestGeneratedReportRetry(long mediaId,String idempotencyKey,
                                                         boolean confirmAdditionalCharge) {
        var user=currentUsers.requireCurrentUser();
        permissions.requireClassroomWrite(user);
        if(!user.roles().contains("SUPERVISOR"))
            throw new AccessDeniedException("SUPERVISOR report retry required");
        var access=repository.mediaAccess(mediaId);
        if(access==null||!offerings.visibleForPurpose(user,access.offeringId(),
                AuthorizedScopeProvider.Purpose.SUPERVISION)) throw new NotVisibleException();
        offerings.requireVisible(access.offeringId());
        if(!confirmAdditionalCharge) throw new ClassroomAnalysisException("REPORT_RETRY_CONFIRMATION_REQUIRED",
                "再次生成可能产生新费用，请明确确认",422);
        String keyHash=sha256Utf8(validateIdempotencyKey(idempotencyKey));
        var objective=repository.report(mediaId);
        if(objective==null||!"EXTERNAL".equals(objective.mode())||!"READY".equals(objective.status()))
            throw new ClassroomAnalysisException("REPORT_RETRY_NOT_AVAILABLE","客观结果尚未满足重新生成条件",409);
        var retry=reportDispatch.manualRetry(mediaId,objective.id(),user.userId(),keyHash);
        return new ManualRetryResult(generatedReportProgress(mediaId).state(),retry.replayed());
    }

    public record ManualRetryResult(String state,boolean replayed) {}

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public ManualModuleRetryResult requestFailedModuleRetry(long mediaId,String idempotencyKey,
                                                           boolean confirmAdditionalCharge) {
        var user=currentUsers.requireCurrentUser();
        permissions.requireClassroomWrite(user);
        if(!user.roles().contains("SUPERVISOR"))
            throw new AccessDeniedException("SUPERVISOR module retry required");
        var access=repository.mediaAccess(mediaId);
        if(access==null||!offerings.visibleForPurpose(user,access.offeringId(),
                AuthorizedScopeProvider.Purpose.SUPERVISION)) throw new NotVisibleException();
        offerings.requireVisible(access.offeringId());
        if(!confirmAdditionalCharge) throw new ClassroomAnalysisException("MODULE_RETRY_CONFIRMATION_REQUIRED",
                "重新分析可能产生新费用，请明确确认",422);
        String keyHash=sha256Utf8(validateIdempotencyKey(idempotencyKey));
        var state=repository.lockModuleRetryState(mediaId);
        if(state==null||!"EXTERNAL".equals(state.mode()))
            throw new ClassroomAnalysisException("MODULE_RETRY_NOT_AVAILABLE","当前课堂没有可重建的模块任务",409);
        var repeat=repository.moduleRetryRequest(state.reportId(),keyHash);
        if(repeat!=null) {
            if(repeat.userId()!=user.userId()||!"REBUILD".equals(repeat.operationType())) throw new ClassroomAnalysisException("MODULE_RETRY_KEY_REUSED",
                    "该操作键已由其他督导使用",409);
            return new ManualModuleRetryResult(state.status(),true);
        }
        if(!"FAILED".equals(state.status()))
            throw new ClassroomAnalysisException("MODULE_RETRY_NOT_AVAILABLE","客观报告当前不是失败状态",409);
        var transcript=repository.moduleJob(state.transcriptJobId());
        var headUp=repository.moduleJob(state.headUpJobId());
        if(transcript==null||headUp==null||transcript.mediaId()!=mediaId||headUp.mediaId()!=mediaId
                ||!"TRANSCRIPTION".equals(transcript.moduleType())||!"HEAD_UP".equals(headUp.moduleType())
                ||!ClassroomModuleContract.isTerminal(transcript.status())
                ||!ClassroomModuleContract.isTerminal(headUp.status())
                ||("SUCCEEDED".equals(transcript.status())&&"SUCCEEDED".equals(headUp.status())))
            throw new ClassroomAnalysisException("MODULE_RETRY_NOT_AVAILABLE",
                    "请等待两项模块任务结束后刷新，再重新分析失败任务",409);
        if(!properties.integrationReady()||properties.isMockModulesEnabled())
            throw new ClassroomAnalysisException("MODULE_INTEGRATION_NOT_READY",
                    "识别模块尚未配置就绪，请稍后重试",503);
        if(("FAILED".equals(transcript.status())&&transcript.attempt()>=properties.getMaxModuleAttempts())
                ||("FAILED".equals(headUp.status())&&headUp.attempt()>=properties.getMaxModuleAttempts()))
            throw new ClassroomAnalysisException("MODULE_RETRY_LIMIT_REACHED",
                    "该识别模块已达到重试次数上限，请由管理员核查失败原因",409);
        LocalDateTime deadline=LocalDateTime.now().plusSeconds(properties.getJobDeadlineSeconds());
        Long replacementTranscript="FAILED".equals(transcript.status())
                ?repository.insertRetryJob(mediaId,"TRANSCRIPTION",properties.getTranscription().getProviderId(),
                        repository.nextModuleJobAttempt(mediaId,"TRANSCRIPTION"),deadline):null;
        Long replacementHeadUp="FAILED".equals(headUp.status())
                ?repository.insertRetryJob(mediaId,"HEAD_UP",properties.getHeadUp().getProviderId(),
                        repository.nextModuleJobAttempt(mediaId,"HEAD_UP"),deadline):null;
        int generation=repository.nextModuleRetryGeneration(state.reportId());
        repository.insertModuleRetryRequest(state.reportId(),generation,state.transcriptJobId(),
                state.headUpJobId(),replacementTranscript,replacementHeadUp,user.userId(),keyHash);
        if(!repository.restartFailedReport(state.reportId(),state.transcriptJobId(),state.headUpJobId(),
                replacementTranscript==null?state.transcriptJobId():replacementTranscript,
                replacementHeadUp==null?state.headUpJobId():replacementHeadUp))
            throw new ClassroomAnalysisException("MODULE_RETRY_NOT_AVAILABLE","报告状态已变化，请刷新后重试",409);
        return new ManualModuleRetryResult("WAITING_INPUT",false);
    }

    public record ManualModuleRetryResult(String state,boolean replayed) {}

    public RecoveryResult recoverExistingTranscription(long mediaId,String idempotencyKey,
                                                       boolean confirmExistingResult) {
        var user=currentUsers.requireCurrentUser();
        permissions.requireClassroomWrite(user);
        if(!user.roles().contains("SUPERVISOR"))
            throw new AccessDeniedException("SUPERVISOR transcription recovery required");
        var access=repository.mediaAccess(mediaId);
        if(access==null||!offerings.visibleForPurpose(user,access.offeringId(),
                AuthorizedScopeProvider.Purpose.SUPERVISION)) throw new NotVisibleException();
        offerings.requireVisible(access.offeringId());
        if(!confirmExistingResult) throw new ClassroomAnalysisException("RECOVERY_CONFIRMATION_REQUIRED",
                "请确认恢复已有转写结果",422);
        String keyHash=sha256Utf8(validateIdempotencyKey(idempotencyKey));
        var preparation=transactions.execute(status->prepareRecovery(mediaId,user.userId(),keyHash));
        if(preparation==null) throw new IllegalStateException("Recovery transaction returned no result");
        if(preparation.replayed()) return new RecoveryResult(preparation.state().status(),true);
        var recovered=pollRecoverableSource(preparation.sources());
        var result=transactions.execute(status->finishRecovery(mediaId,user.userId(),keyHash,preparation,recovered));
        if(result==null) throw new IllegalStateException("Recovery transaction returned no result");
        return result;
    }

    private RecoveryPreparation prepareRecovery(long mediaId,long userId,String keyHash) {
        var state=repository.lockModuleRetryState(mediaId);
        if(state==null||!"EXTERNAL".equals(state.mode()))
            throw new ClassroomAnalysisException("RECOVERY_NOT_AVAILABLE","当前课堂没有可恢复的客观结果",409);
        var repeat=repository.moduleRetryRequest(state.reportId(),keyHash);
        if(repeat!=null) {
            if(repeat.userId()!=userId||!"RECOVERY".equals(repeat.operationType())) throw new ClassroomAnalysisException("RECOVERY_KEY_REUSED",
                    "该操作键已由其他督导使用",409);
            return new RecoveryPreparation(state,List.of(),true);
        }
        var transcript=repository.moduleJob(state.transcriptJobId());
        var headUp=repository.moduleJob(state.headUpJobId());
        if(!"FAILED".equals(state.status())||transcript==null||headUp==null
                ||transcript.mediaId()!=mediaId||headUp.mediaId()!=mediaId
                ||!"TRANSCRIPTION".equals(transcript.moduleType())||!"FAILED".equals(transcript.status())
                ||!"HEAD_UP".equals(headUp.moduleType())||!"SUCCEEDED".equals(headUp.status()))
            throw new ClassroomAnalysisException("RECOVERY_NOT_AVAILABLE",
                    "请等待抬头结果完成，并确认当前转写任务失败",409);
        var sources=repository.recoverableTranscriptionSources(mediaId);
        if(sources.isEmpty()) throw new ClassroomAnalysisException("RECOVERY_NOT_AVAILABLE",
                "没有可恢复的历史转写结果",409);
        return new RecoveryPreparation(state,sources,false);
    }

    private RecoveredSource pollRecoverableSource(List<ClassroomAnalysisRepository.ModuleJobRow> sources) {
        boolean unavailable=false;
        for(var source:sources) {
            try {
                JsonNode raw=moduleClient.poll(source); // GET only: never submits a new supplier request.
                var envelope=ClassroomModuleContract.parse(source,raw);
                if("SUCCEEDED".equals(envelope.status())&&envelope.revision()==1)
                    return new RecoveredSource(source,raw);
            } catch(ClassroomModuleHttpClient.ModuleCallException failure) {
                unavailable=true;
            } catch(ClassroomModuleContract.ContractViolation ignored) {
                // Continue to older attempts: a later local failure can coexist with an older provider success.
            }
        }
        if(unavailable) throw new ClassroomAnalysisException("RECOVERY_SOURCE_UNAVAILABLE",
                "无法读取全部历史转写结果，请稍后再试",503);
        throw new ClassroomAnalysisException("RECOVERY_SOURCE_NOT_READY",
                "历史转写结果尚未成功，不能恢复",409);
    }

    private RecoveryResult finishRecovery(long mediaId,long userId,String keyHash,
                                          RecoveryPreparation preparation,RecoveredSource recovered) {
        var state=repository.lockModuleRetryState(mediaId);
        if(state==null||state.reportId()!=preparation.state().reportId())
            throw new ClassroomAnalysisException("RECOVERY_NOT_AVAILABLE","报告状态已变化，请刷新后重试",409);
        var repeat=repository.moduleRetryRequest(state.reportId(),keyHash);
        if(repeat!=null) {
            if(repeat.userId()!=userId||!"RECOVERY".equals(repeat.operationType()))
                throw new ClassroomAnalysisException("RECOVERY_KEY_REUSED","该操作键已由其他督导使用",409);
            return new RecoveryResult(state.status(),true);
        }
        if(!"FAILED".equals(state.status())
                ||state.transcriptJobId()!=preparation.state().transcriptJobId()
                ||state.headUpJobId()!=preparation.state().headUpJobId()
                ||repository.recoverableTranscriptionSources(mediaId).stream()
                    .noneMatch(candidate->candidate.id()==recovered.source().id()))
            throw new ClassroomAnalysisException("RECOVERY_NOT_AVAILABLE","报告状态已变化，请刷新后重试",409);
        var transcript=repository.moduleJob(state.transcriptJobId());
        if(transcript==null) throw new ClassroomAnalysisException("RECOVERY_NOT_AVAILABLE","当前转写任务不存在",409);
        long replacement=repository.insertRecoveredJob(recovered.source(),
                repository.nextModuleJobAttempt(mediaId,"TRANSCRIPTION"));
        int generation=repository.nextModuleRetryGeneration(state.reportId());
        repository.insertModuleRecoveryRequest(state.reportId(),generation,state.transcriptJobId(),
                state.headUpJobId(),replacement,userId,keyHash);
        if(!repository.restartFailedReport(state.reportId(),state.transcriptJobId(),state.headUpJobId(),
                replacement,state.headUpJobId()))
            throw new ClassroomAnalysisException("RECOVERY_NOT_AVAILABLE","报告状态已变化，请刷新后重试",409);
        moduleResults.accept(repository.moduleJob(replacement),recovered.raw());
        return new RecoveryResult("READY",false);
    }

    private record RecoveryPreparation(ClassroomAnalysisRepository.ModuleRetryState state,
                                       List<ClassroomAnalysisRepository.ModuleJobRow> sources,
                                       boolean replayed) {}
    private record RecoveredSource(ClassroomAnalysisRepository.ModuleJobRow source,JsonNode raw) {}

    public record RecoveryResult(String state,boolean replayed) {}

    @Transactional(readOnly=true)
    public MediaDownload moduleMedia(long mediaId,long jobId,String moduleType,int attempt,long expires,String signature) {
        if(!mediaSigner.verify(mediaId,jobId,moduleType,attempt,expires,signature,Instant.now()))
            throw new ClassroomAnalysisException("MEDIA_LINK_NOT_FOUND","媒体链接无效或已过期",404);
        var access=repository.moduleMediaAccess(mediaId,jobId,moduleType,attempt);
        if(access==null) throw new ClassroomAnalysisException("MEDIA_LINK_NOT_FOUND","媒体链接无效或已过期",404);
        Path file=resolveStorage(access.storageKey());
        if(!Files.isRegularFile(file)) throw new ClassroomAnalysisException("MEDIA_MISSING","视频文件不可用",404);
        return new MediaDownload(new FileSystemResource(file),access.contentType(),access.size());
    }

    @Transactional
    public ClassroomAnalysisView importModuleResult(long jobId,JsonNode result) {
        var user=currentUsers.requireCurrentUser(); permissions.requireClassroomWrite(user);
        Long offeringId=repository.offeringIdForJob(jobId);
        if(offeringId==null) throw new NotVisibleException();
        offerings.requireVisible(offeringId);
        var job=repository.moduleJob(jobId);
        if(job==null) throw new NotVisibleException();
        moduleResults.accept(job,result);
        return get(job.classSessionId());
    }

    private ClassroomAnalysisView viewUnchecked(long id) {
        var row=repository.session(id);
        if(row==null) throw new NotVisibleException();
        return view(row);
    }

    private ClassroomAnalysisView viewSummaryUnchecked(long id) {
        var row=repository.session(id);
        if(row==null) throw new NotVisibleException();
        return view(row,false);
    }

    private ClassroomAnalysisView view(ClassroomAnalysisRepository.SessionRow row) {
        return view(row,true);
    }

    private ClassroomAnalysisView view(ClassroomAnalysisRepository.SessionRow row,boolean includeEvidence) {
        var media=repository.media(row.id()).stream().map(item -> {
            var report=repository.report(item.id());
            var attendance=report==null||!includeEvidence?new ClassroomAttendanceEstimate.Result(null,0,null)
                    :ClassroomAttendanceEstimate.fromEnvelope(
                            repository.succeededHeadUpEnvelope(report.headUpJobId()),json);
            ClassroomAnalysisView.ReportView reportView=report==null?null:new ClassroomAnalysisView.ReportView(
                    report.id(),report.status(),report.mode(),report.mean(),report.coverage(),
                    attendance.estimatedCount(),attendance.observedWindows(),attendance.confidence(),report.summary(),
                    report.analysis(),report.evidenceStart(),report.evidenceEnd(),report.error(),
                    includeEvidence?repository.segments(report.transcriptJobId()):List.of(),
                    includeEvidence?repository.windows(report.headUpJobId()):List.of());
            return new ClassroomAnalysisView.MediaView(item.id(),item.filename(),item.contentType(),item.size(),
                    item.sha256(),item.durationMs(),item.status(),"/api/v1/class-sessions/media/"+item.id()+"/content",
                    repository.jobs(item.id()),reportView);
        }).toList();
        return new ClassroomAnalysisView(row.id(),row.offeringId(),row.scheduleId(),row.date(),row.title(),row.createdAt(),media);
    }

    private void validate(MultipartFile video,long durationMs) {
        if(video==null||video.isEmpty()) throw invalid("VIDEO_REQUIRED","请选择非空视频文件");
        if(video.getSize()>properties.getMaxVideoBytes())
            throw new ClassroomAnalysisException("VIDEO_TOO_LARGE","视频超过允许大小",413);
        if(durationMs<1_000||durationMs>86_400_000L) throw invalid("INVALID_DURATION","视频时长必须在1秒到24小时之间");
        String name=safeFilename(video.getOriginalFilename());
        String type=normalizedType(video.getContentType(),name);
        if(!TYPES.contains(type)) throw new ClassroomAnalysisException("UNSUPPORTED_VIDEO","仅支持MP4或WebM视频",415);
        try(InputStream in=video.getInputStream()) {
            byte[] header=in.readNBytes(12);
            boolean mp4=header.length>=8&&header[4]=='f'&&header[5]=='t'&&header[6]=='y'&&header[7]=='p';
            boolean webm=header.length>=4&&(header[0]&255)==0x1a&&(header[1]&255)==0x45&&(header[2]&255)==0xdf&&(header[3]&255)==0xa3;
            if((type.equals("video/mp4")&&!mp4)||(type.equals("video/webm")&&!webm))
                throw new ClassroomAnalysisException("VIDEO_SIGNATURE_MISMATCH","文件内容与视频格式不符",415);
        } catch(IOException e) { throw invalid("VIDEO_READ_FAILED","无法读取视频文件"); }
    }

    private long validateProbe(ClassroomMediaProbe.ProbeResult probe,long reportedDurationMs) {
        if(!probe.hasVideo()) throw invalid("VIDEO_STREAM_REQUIRED","视频文件不包含可用的视频流");
        if(!probe.hasAudio()) throw invalid("AUDIO_STREAM_REQUIRED","视频文件不包含可用的音轨");
        long actualDurationMs=probe.durationMs();
        if(actualDurationMs<1_000||actualDurationMs>86_400_000L)
            throw invalid("INVALID_DURATION","视频实际时长必须在1秒到24小时之间");
        long tolerance=properties.getDurationToleranceMs();
        if(tolerance<0)
            throw new ClassroomAnalysisException("MEDIA_PROBE_UNAVAILABLE","媒体探测容差配置无效",503);
        if(Math.abs(actualDurationMs-reportedDurationMs)>tolerance)
            throw invalid("VIDEO_DURATION_MISMATCH","视频实际时长与浏览器申报值不一致");
        return actualDurationMs;
    }

    private static String validateIdempotencyKey(String raw) {
        String value=raw==null?"":raw.trim();
        if(!IDEMPOTENCY_KEY.matcher(value).matches())
            throw new ClassroomAnalysisException("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key必须为8到128位字母、数字或._:-",400);
        return value;
    }

    private String normalizedType(String raw,String filename) {
        String value=raw==null?"":raw.split(";",2)[0].trim().toLowerCase(Locale.ROOT);
        if(value.equals("video/mp4")||value.equals("video/webm")) return value;
        String lower=filename.toLowerCase(Locale.ROOT);
        if((value.isBlank()||value.equals("application/octet-stream"))&&lower.endsWith(".mp4")) return "video/mp4";
        if((value.isBlank()||value.equals("application/octet-stream"))&&lower.endsWith(".webm")) return "video/webm";
        return value;
    }

    private static String safeFilename(String raw) {
        String value=raw==null?"video":raw.replace('\\','/');
        value=value.substring(value.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]","_").trim();
        if(value.isBlank()) value="video";
        return value.length()>255?value.substring(value.length()-255):value;
    }
    private Path resolveStorage(String key) {
        Path result=storageRoot.resolve(key).normalize();
        if(!result.startsWith(storageRoot)) throw new IllegalStateException("Invalid storage key");
        return result;
    }
    private static String sha256(InputStream input) throws IOException {
        try(input) {
            var digest=MessageDigest.getInstance("SHA-256"); input.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(),digest));
            return HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String uploadRequestSha256(long offeringId,long scheduleId,LocalDate sessionDate,String title,
                                              long durationMs,String contentType,long byteSize,String mediaSha256) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            for(String field:List.of(Long.toString(offeringId),Long.toString(scheduleId),sessionDate.toString(),title,
                    Long.toString(durationMs),contentType,Long.toString(byteSize),mediaSha256)) {
                byte[] bytes=field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String sha256Utf8(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void deleteQuietly(Path file) { try { Files.deleteIfExists(file); } catch(IOException ignored) {} }
    private static ClassroomAnalysisException invalid(String code,String message) { return new ClassroomAnalysisException(code,message,422); }
    public record MediaDownload(FileSystemResource resource,String contentType,long size) {}
    public record UploadResult(ClassroomAnalysisView view,boolean replayed) {}
}
