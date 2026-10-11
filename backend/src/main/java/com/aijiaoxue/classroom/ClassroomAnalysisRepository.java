package com.aijiaoxue.classroom;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ClassroomAnalysisRepository {
    private final JdbcTemplate jdbc;
    public ClassroomAnalysisRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    long insertSession(long offeringId, long scheduleId, LocalDate date, String title, long userId,
                       String idempotencyKeySha256, String requestSha256) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO class_session(offering_id,schedule_id,session_date,title,created_by,
                    upload_idempotency_key_sha256,upload_request_sha256) VALUES (?,?,?,?,?,?,?)""", new String[]{"id"});
            statement.setLong(1, offeringId); statement.setLong(2, scheduleId);
            statement.setDate(3, Date.valueOf(date)); statement.setString(4, title); statement.setLong(5, userId);
            statement.setString(6,idempotencyKeySha256); statement.setString(7,requestSha256);
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    UploadRequestRow uploadRequest(long userId,String idempotencyKeySha256) {
        return jdbc.query("""
                SELECT id,upload_request_sha256 FROM class_session
                WHERE created_by=? AND upload_idempotency_key_sha256=?""",
                (rs,n)->new UploadRequestRow(rs.getLong(1),rs.getString(2)),userId,idempotencyKeySha256)
                .stream().findFirst().orElse(null);
    }

    long insertMedia(long sessionId, String storageKey, String originalFilename, String contentType,
                     long byteSize, String sha256, long durationMs) {
        return insert("""
                INSERT INTO classroom_media(class_session_id,storage_key,original_filename,content_type,
                byte_size,sha256,duration_ms,status) VALUES (?,?,?,?,?,?,?,'READY')""",
                sessionId, storageKey, originalFilename, contentType, byteSize, sha256, durationMs);
    }

    long insertJob(long mediaId, String type, String provider, String status, String clientRequestId,
                   LocalDateTime deadlineAt) {
        return insert("""
                INSERT INTO classroom_module_job(media_id,module_type,status,provider_id,client_request_id,
                next_poll_at,deadline_at) VALUES (?,?,?,?,?,CURRENT_TIMESTAMP,?)""",
                mediaId, type, status, provider, clientRequestId, deadlineAt);
    }

    long insertRetryJob(long mediaId,String type,String provider,int attempt,LocalDateTime deadlineAt) {
        return insert("""
                INSERT INTO classroom_module_job(media_id,module_type,status,provider_id,client_request_id,
                attempt,next_poll_at,deadline_at) VALUES (?,?, 'QUEUED',?,?,?,CURRENT_TIMESTAMP,?)""",
                mediaId,type,provider,java.util.UUID.randomUUID().toString(),attempt,deadlineAt);
    }

    long insertRecoveredJob(ModuleJobRow source,int attempt) {
        return insert("""
                INSERT INTO classroom_module_job(media_id,module_type,status,provider_id,client_request_id,
                provider_job_id,module_version,attempt,recovery_source_job_id)
                VALUES (?,?,'RUNNING',?,?,?,?,?,?)""",
                source.mediaId(),source.moduleType(),source.providerId(),source.clientRequestId(),
                source.providerJobId(),source.moduleVersion(),attempt,source.id());
    }

    List<ModuleJobRow> recoverableTranscriptionSources(long mediaId) {
        List<Long> ids=jdbc.query("""
                SELECT id FROM classroom_module_job
                WHERE media_id=? AND module_type='TRANSCRIPTION' AND status='FAILED'
                  AND error_code='MODULE_INTEGRATION_FAILED'
                  AND provider_job_id IS NOT NULL AND client_request_id IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM classroom_module_result r WHERE r.job_id=classroom_module_job.id)
                  AND NOT EXISTS (SELECT 1 FROM classroom_module_job recovered
                                  WHERE recovered.recovery_source_job_id=classroom_module_job.id)
                ORDER BY attempt DESC LIMIT 10""",(rs,n)->rs.getLong(1),mediaId);
        return ids.stream().map(this::moduleJob).toList();
    }

    int nextModuleJobAttempt(long mediaId,String moduleType) {
        Integer value=jdbc.queryForObject("""
                SELECT COALESCE(MAX(attempt),0)+1 FROM classroom_module_job
                WHERE media_id=? AND module_type=?""",Integer.class,mediaId,moduleType);
        return value==null?1:value;
    }

    long insertReport(long mediaId, long transcriptJobId, long headUpJobId, String mode) {
        return insert("""
                INSERT INTO classroom_analysis_report(media_id,transcript_job_id,head_up_job_id,status,data_mode)
                VALUES (?,?,?,'WAITING_INPUT',?)""", mediaId, transcriptJobId, headUpJobId, mode);
    }

    private long insert(String sql, Object... values) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    boolean scheduleBelongsToOffering(long scheduleId, long offeringId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM class_schedule WHERE id=? AND offering_id=?",
                Integer.class, scheduleId, offeringId);
        return count != null && count == 1;
    }

    void completeMock(long mediaId, long transcriptJobId, long headUpJobId, long reportId, long durationMs) {
        long firstEnd = Math.max(1, durationMs / 3);
        long secondEnd = Math.max(firstEnd + 1, durationMs * 2 / 3);
        if (secondEnd > durationMs) secondEnd = durationMs;
        jdbc.update("""
                UPDATE classroom_module_job SET status='SUCCEEDED',provider_id='builtin-mock',
                module_version='synthetic-1',result_revision=1,updated_at=CURRENT_TIMESTAMP WHERE id IN (?,?)""",
                transcriptJobId, headUpJobId);
        jdbc.update("INSERT INTO classroom_transcript_segment(job_id,segment_key,start_ms,end_ms,text_content) VALUES (?,?,?,?,?)",
                transcriptJobId, "seg-001", 0, firstEnd, "模拟转写：本段内容仅用于验证主项目流程，尚未接入真实语音模块。");
        if (secondEnd > firstEnd)
            jdbc.update("INSERT INTO classroom_transcript_segment(job_id,segment_key,start_ms,end_ms,text_content) VALUES (?,?,?,?,?)",
                    transcriptJobId, "seg-002", firstEnd, secondEnd, "模拟转写：这里展示带时间戳的课程内容。");
        long window = Math.max(1, durationMs / 4);
        int[][] counts = {{30,40},{24,40},{0,0},{20,40}};
        long validDuration = 0;
        double weightedRate = 0;
        for (int i = 0; i < 4; i++) {
            long start = i * window;
            long end = i == 3 ? durationMs : Math.min(durationMs, (i + 1) * window);
            if (end <= start) continue;
            String quality = i == 2 ? "NO_SUBJECTS" : "VALID";
            jdbc.update("""
                    INSERT INTO classroom_head_up_window(job_id,window_key,start_ms,end_ms,quality,
                    quality_reason,head_up_count,valid_observed_count) VALUES (?,?,?,?,?,?,?,?)""",
                    headUpJobId, "win-00" + (i + 1), start, end, quality,
                    i == 2 ? "SIMULATED_NO_SUBJECTS" : null, counts[i][0], counts[i][1]);
            if (counts[i][1] > 0) {
                long span = end - start;
                validDuration += span;
                weightedRate += ((double) counts[i][0] / counts[i][1]) * span;
            }
        }
        jdbc.update("""
                UPDATE classroom_analysis_report SET status='SUCCEEDED',data_mode='SIMULATED',
                mean_head_up_rate=?,valid_coverage_rate=?,summary_text=?,analysis_text=?,
                evidence_start_ms=?,evidence_end_ms=? WHERE id=?""",
                validDuration == 0 ? null : weightedRate / validDuration,
                (double) validDuration / durationMs,
                "模拟课程内容概要：当前报告用于验证上传、自动编排、数据展示和证据定位。",
                "模拟分析：有效窗口的平均抬头率由程序根据模拟计数计算。中段存在无可观测对象窗口，不能据此判断学生状态或教师教学质量。真实模块接入后将使用实际转写与观测数据。",
                firstEnd, secondEnd, reportId);
    }

    List<Long> sessionIds(long offeringId) {
        return jdbc.queryForList("SELECT id FROM class_session WHERE offering_id=? ORDER BY session_date DESC,id DESC",
                Long.class, offeringId);
    }

    SessionRow session(long sessionId) {
        return jdbc.query("""
                SELECT id,offering_id,schedule_id,session_date,title,created_at FROM class_session WHERE id=?""",
                (rs, n) -> new SessionRow(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                        rs.getDate(4).toLocalDate(), rs.getString(5), rs.getTimestamp(6).toLocalDateTime()), sessionId)
                .stream().findFirst().orElse(null);
    }

    List<MediaRow> media(long sessionId) {
        return jdbc.query("""
                SELECT id,storage_key,original_filename,content_type,byte_size,sha256,duration_ms,status
                FROM classroom_media WHERE class_session_id=? ORDER BY id""",
                (rs,n) -> new MediaRow(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),
                        rs.getLong(5),rs.getString(6),rs.getLong(7),rs.getString(8)), sessionId);
    }

    MediaAccess mediaAccess(long mediaId) {
        return jdbc.query("""
                SELECT m.storage_key,m.content_type,m.byte_size,s.offering_id
                FROM classroom_media m JOIN class_session s ON s.id=m.class_session_id WHERE m.id=?""",
                (rs,n) -> new MediaAccess(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getLong(4)), mediaId)
                .stream().findFirst().orElse(null);
    }

    /** S2-11：教师端只有 mediaId，无法据此解析证据时间，故把课堂会话标识一并提供。 */
    Long classSessionIdOf(long mediaId) {
        return jdbc.query("SELECT class_session_id FROM classroom_media WHERE id=?",
                (rs,n) -> rs.getLong(1), mediaId).stream().findFirst().orElse(null);
    }

    MediaAccess moduleMediaAccess(long mediaId,long jobId,String moduleType,int attempt) {        return jdbc.query("""
                SELECT m.storage_key,m.content_type,m.byte_size,s.offering_id
                FROM classroom_media m JOIN class_session s ON s.id=m.class_session_id
                JOIN classroom_module_job j ON j.media_id=m.id
                WHERE m.id=? AND j.id=? AND j.module_type=? AND j.attempt=?
                  AND j.status IN ('QUEUED','RUNNING')""",
                (rs,n)->new MediaAccess(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getLong(4)),
                mediaId,jobId,moduleType,attempt).stream().findFirst().orElse(null);
    }

    List<ClassroomAnalysisView.JobView> jobs(long mediaId) {
        return jdbc.query("""
                SELECT j.id,j.module_type,j.status,j.provider_id,j.module_version,j.result_revision,
                       j.error_code,j.error_message
                FROM classroom_module_job j JOIN classroom_analysis_report r ON r.media_id=j.media_id
                WHERE j.media_id=? AND j.id IN (r.transcript_job_id,r.head_up_job_id)
                ORDER BY j.module_type""",
                (rs,n) -> new ClassroomAnalysisView.JobView(rs.getLong(1),rs.getString(2),rs.getString(3),
                        rs.getString(4),rs.getString(5),(Integer)rs.getObject(6),rs.getString(7),rs.getString(8)), mediaId);
    }

    ModuleRetryState lockModuleRetryState(long mediaId) {
        return jdbc.query("""
                SELECT id,status,data_mode,transcript_job_id,head_up_job_id
                FROM classroom_analysis_report WHERE media_id=? ORDER BY id DESC LIMIT 1 FOR UPDATE""",
                (rs,n)->new ModuleRetryState(rs.getLong(1),rs.getString(2),rs.getString(3),
                        rs.getLong(4),rs.getLong(5)),mediaId).stream().findFirst().orElse(null);
    }

    ModuleRetryRequest moduleRetryRequest(long reportId,String requestKeySha256) {
        return jdbc.query("""
                SELECT requested_by_user_id,operation_type FROM classroom_module_retry_request
                WHERE report_id=? AND request_key_sha256=?""",
                (rs,n)->new ModuleRetryRequest(rs.getLong(1),rs.getString(2)),reportId,requestKeySha256)
                .stream().findFirst().orElse(null);
    }

    int nextModuleRetryGeneration(long reportId) {
        Integer value=jdbc.queryForObject("""
                SELECT COALESCE(MAX(generation),0)+1 FROM classroom_module_retry_request WHERE report_id=?""",
                Integer.class,reportId);
        return value==null?1:value;
    }

    void insertModuleRetryRequest(long reportId,int generation,long previousTranscriptJobId,
                                  long previousHeadUpJobId,Long replacementTranscriptJobId,
                                  Long replacementHeadUpJobId,long requestedByUserId,String requestKeySha256) {
        jdbc.update("""
                INSERT INTO classroom_module_retry_request
                (report_id,generation,previous_transcript_job_id,previous_head_up_job_id,
                 replacement_transcript_job_id,replacement_head_up_job_id,requested_by_user_id,request_key_sha256)
                VALUES (?,?,?,?,?,?,?,?)""",reportId,generation,previousTranscriptJobId,
                previousHeadUpJobId,replacementTranscriptJobId,replacementHeadUpJobId,
                requestedByUserId,requestKeySha256);
    }

    void insertModuleRecoveryRequest(long reportId,int generation,long previousTranscriptJobId,
                                     long previousHeadUpJobId,long replacementTranscriptJobId,
                                     long requestedByUserId,String requestKeySha256) {
        jdbc.update("""
                INSERT INTO classroom_module_retry_request
                (report_id,generation,previous_transcript_job_id,previous_head_up_job_id,
                 replacement_transcript_job_id,requested_by_user_id,request_key_sha256,operation_type)
                VALUES (?,?,?,?,?,?,?,'RECOVERY')""",reportId,generation,previousTranscriptJobId,
                previousHeadUpJobId,replacementTranscriptJobId,requestedByUserId,requestKeySha256);
    }

    boolean restartFailedReport(long reportId,long previousTranscriptJobId,long previousHeadUpJobId,
                                long transcriptJobId,long headUpJobId) {
        return jdbc.update("""
                UPDATE classroom_analysis_report SET status='WAITING_INPUT',error_message=NULL,
                transcript_job_id=?,head_up_job_id=?,mean_head_up_rate=NULL,valid_coverage_rate=NULL,
                summary_text=NULL,analysis_text=NULL,evidence_start_ms=NULL,evidence_end_ms=NULL
                WHERE id=? AND status='FAILED' AND data_mode='EXTERNAL'
                  AND transcript_job_id=? AND head_up_job_id=?""",
                transcriptJobId,headUpJobId,reportId,previousTranscriptJobId,previousHeadUpJobId)==1;
    }

    List<ModuleJobRow> dueJobs(int limit) {
        return jdbc.query("""
                SELECT j.id,j.media_id,j.module_type,j.status,j.provider_id,j.client_request_id,
                       j.provider_job_id,j.module_version,j.result_revision,
                       m.class_session_id,m.content_type,m.sha256,m.duration_ms,
                       j.attempt,j.consecutive_failures,j.deadline_at
                FROM classroom_module_job j
                JOIN classroom_media m ON m.id=j.media_id
                WHERE j.status IN ('QUEUED','RUNNING')
                  AND (j.next_poll_at IS NULL OR j.next_poll_at<=CURRENT_TIMESTAMP)
                  AND (j.lease_until IS NULL OR j.lease_until<CURRENT_TIMESTAMP)
                ORDER BY j.next_poll_at,j.id LIMIT ?""", (rs,n)->new ModuleJobRow(
                rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getString(5),
                rs.getString(6),rs.getString(7),rs.getString(8),(Integer)rs.getObject(9),
                rs.getLong(10),rs.getString(11),rs.getString(12),rs.getLong(13),rs.getInt(14),
                rs.getInt(15),rs.getTimestamp(16)==null?null:rs.getTimestamp(16).toLocalDateTime()), limit);
    }

    List<Long> waitingExternalJobs(int limit) {
        return jdbc.queryForList("""
                SELECT j.id FROM classroom_module_job j
                JOIN classroom_media m ON m.id=j.media_id
                JOIN classroom_analysis_report r ON r.media_id=m.id
                WHERE j.status='WAITING_EXTERNAL' AND m.status='READY'
                  AND r.status='WAITING_INPUT' AND r.data_mode='EXTERNAL'
                  AND j.provider_job_id IS NULL AND j.result_revision IS NULL
                ORDER BY j.id LIMIT ?""",Long.class,limit);
    }

    boolean promoteWaitingExternal(long jobId,String providerId,LocalDateTime deadlineAt) {
        return jdbc.update("""
                UPDATE classroom_module_job SET status='QUEUED',provider_id=?,deadline_at=?,
                next_poll_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND status='WAITING_EXTERNAL' AND provider_job_id IS NULL
                  AND result_revision IS NULL AND EXISTS (
                    SELECT 1 FROM classroom_analysis_report r
                    WHERE r.media_id=classroom_module_job.media_id
                      AND r.status='WAITING_INPUT' AND r.data_mode='EXTERNAL')""",
                providerId,deadlineAt,jobId)==1;
    }

    ModuleJobRow moduleJob(long jobId) {
        return jdbc.query("""
                SELECT j.id,j.media_id,j.module_type,j.status,j.provider_id,j.client_request_id,
                       j.provider_job_id,j.module_version,j.result_revision,
                       m.class_session_id,m.content_type,m.sha256,m.duration_ms,
                       j.attempt,j.consecutive_failures,j.deadline_at
                FROM classroom_module_job j JOIN classroom_media m ON m.id=j.media_id WHERE j.id=?""",
                (rs,n)->new ModuleJobRow(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4),
                        rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),(Integer)rs.getObject(9),
                        rs.getLong(10),rs.getString(11),rs.getString(12),rs.getLong(13),rs.getInt(14),
                        rs.getInt(15),rs.getTimestamp(16)==null?null:rs.getTimestamp(16).toLocalDateTime()),jobId)
                .stream().findFirst().orElse(null);
    }

    Long offeringIdForJob(long jobId) {
        return jdbc.query("""
                SELECT s.offering_id FROM classroom_module_job j JOIN classroom_media m ON m.id=j.media_id
                JOIN class_session s ON s.id=m.class_session_id WHERE j.id=?""",
                (rs,n)->rs.getLong(1),jobId).stream().findFirst().orElse(null);
    }

    boolean claim(long jobId, String owner, LocalDateTime leaseUntil, LocalDateTime processingDeadline) {
        return jdbc.update("""
                UPDATE classroom_module_job SET lease_owner=?,lease_until=?,
                deadline_at=CASE WHEN status='QUEUED' AND provider_job_id IS NULL
                                      AND consecutive_failures=0 THEN ? ELSE deadline_at END,
                updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND status IN ('QUEUED','RUNNING')
                  AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP)""",
                owner,leaseUntil,processingDeadline,jobId)==1;
    }

    void saveProgress(long jobId, String status, String providerId, String providerJobId,
                      String moduleVersion, int revision, LocalDateTime nextPollAt) {
        jdbc.update("""
                UPDATE classroom_module_job SET status=?,provider_id=?,provider_job_id=?,module_version=?,
                result_revision=?,next_poll_at=?,lease_owner=NULL,lease_until=NULL,last_integration_error=NULL,
                consecutive_failures=0,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND status IN ('QUEUED','RUNNING','WAITING_EXTERNAL')""", status,providerId,providerJobId,moduleVersion,
                revision,nextPollAt,jobId);
    }

    void deferAfterIntegrationFailure(long jobId, String message, LocalDateTime nextPollAt) {
        jdbc.update("""
                UPDATE classroom_module_job SET next_poll_at=?,lease_owner=NULL,lease_until=NULL,
                last_integration_error=?,consecutive_failures=consecutive_failures+1,
                updated_at=CURRENT_TIMESTAMP WHERE id=? AND status IN ('QUEUED','RUNNING')""",
                nextPollAt,truncate(message,1000),jobId);
    }

    boolean failIntegrationJob(long jobId,String code,String message) {
        return jdbc.update("""
                UPDATE classroom_module_job SET status='FAILED',error_code=?,error_message=?,next_poll_at=NULL,
                lease_owner=NULL,lease_until=NULL,last_integration_error=?,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND status IN ('QUEUED','RUNNING')""",
                code,truncate(message,1000),truncate(message,1000),jobId)==1;
    }

    long insertModuleResult(long jobId, int revision, String status, String payloadSha256, String envelopeJson) {
        return insert("""
                INSERT INTO classroom_module_result(job_id,revision,status,payload_sha256,envelope_json)
                VALUES (?,?,?,?,?)""",jobId,revision,status,payloadSha256,envelopeJson);
    }

    String moduleResultHash(long jobId,int revision) {
        return jdbc.query("SELECT payload_sha256 FROM classroom_module_result WHERE job_id=? AND revision=?",
                (rs,n)->rs.getString(1),jobId,revision).stream().findFirst().orElse(null);
    }

    ResultRef resultRef(long jobId,int revision) {
        return jdbc.query("""
                SELECT id,job_id,revision,status,payload_sha256 FROM classroom_module_result
                WHERE job_id=? AND revision=?""",
                (rs,n)->new ResultRef(rs.getLong(1),rs.getLong(2),rs.getInt(3),rs.getString(4),rs.getString(5)),
                jobId,revision).stream().findFirst().orElse(null);
    }

    String resultEnvelope(long resultId) {
        return jdbc.query("SELECT envelope_json FROM classroom_module_result WHERE id=?",
                (rs,n)->rs.getString(1),resultId).stream().findFirst().orElse(null);
    }

    String succeededHeadUpEnvelope(long jobId) {
        return jdbc.query("""
                SELECT envelope_json FROM classroom_module_result
                WHERE job_id=? AND revision=1 AND status='SUCCEEDED'""",
                (rs,n)->rs.getString(1),jobId).stream().findFirst().orElse(null);
    }

    List<ProcessedRange> processedRanges(long resultId) {
        return jdbc.query("""
                SELECT start_ms,end_ms FROM classroom_processed_range
                WHERE result_id=? ORDER BY range_index""",
                (rs,n)->new ProcessedRange(rs.getLong(1),rs.getLong(2)),resultId);
    }

    void insertProcessedRange(long resultId, int index, long startMs, long endMs) {
        jdbc.update("INSERT INTO classroom_processed_range(result_id,range_index,start_ms,end_ms) VALUES (?,?,?,?)",
                resultId,index,startMs,endMs);
    }

    void replaceTranscript(long jobId, List<ClassroomModuleContract.Segment> segments) {
        jdbc.update("DELETE FROM classroom_transcript_segment WHERE job_id=?",jobId);
        for(var segment:segments) jdbc.update("""
                INSERT INTO classroom_transcript_segment(job_id,segment_key,start_ms,end_ms,text_content,confidence,speaker_label)
                VALUES (?,?,?,?,?,?,?)""",jobId,segment.id(),segment.startMs(),segment.endMs(),segment.text(),
                segment.confidence(),segment.speakerLabel());
    }

    void replaceHeadUpWindows(long jobId, List<ClassroomModuleContract.Window> windows) {
        jdbc.update("DELETE FROM classroom_head_up_window WHERE job_id=?",jobId);
        for(var window:windows) jdbc.update("""
                INSERT INTO classroom_head_up_window(job_id,window_key,start_ms,end_ms,quality,quality_reason,
                head_up_count,valid_observed_count,sample_at_ms,head_up_ratio) VALUES (?,?,?,?,?,?,?,?,?,?)""",
                jobId,window.id(),window.startMs(),window.endMs(),window.quality(),window.qualityReason(),
                window.headUpCount(),window.validObservedCount(),window.sampleAtMs(),window.headUpRatio());
    }

    void finishJob(long jobId, ClassroomModuleContract.Envelope envelope) {
        String errorCode=envelope.error()==null?null:envelope.error().code();
        String errorMessage=envelope.error()==null?null:envelope.error().message();
        jdbc.update("""
                UPDATE classroom_module_job SET status=?,provider_id=?,provider_job_id=?,module_version=?,
                result_revision=?,error_code=?,error_message=?,next_poll_at=NULL,lease_owner=NULL,lease_until=NULL,
                last_integration_error=NULL,consecutive_failures=0,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND status IN ('QUEUED','RUNNING','WAITING_EXTERNAL')""",envelope.status(),
                envelope.providerId(),envelope.jobId(),envelope.moduleVersion(),envelope.revision(),errorCode,
                truncate(errorMessage,1000),jobId);
    }

    int succeededJobCount(long mediaId) {
        Integer value=jdbc.queryForObject("SELECT COUNT(*) FROM classroom_module_job WHERE media_id=? AND status='SUCCEEDED'",
                Integer.class,mediaId);
        return value==null?0:value;
    }

    void markReportReady(long mediaId, double mean, double coverage) {
        jdbc.update("""
                UPDATE classroom_analysis_report SET status='READY',data_mode='EXTERNAL',mean_head_up_rate=?,
                valid_coverage_rate=?,error_message=NULL WHERE media_id=?""",mean,coverage,mediaId);
    }

    void markReportReadyWithoutMean(long mediaId, double coverage) {
        jdbc.update("""
                UPDATE classroom_analysis_report SET status='READY',data_mode='EXTERNAL',mean_head_up_rate=NULL,
                valid_coverage_rate=?,error_message=NULL WHERE media_id=?""",coverage,mediaId);
    }

    void markReportFailed(long mediaId, String message) {
        jdbc.update("UPDATE classroom_analysis_report SET status='FAILED',error_message=? WHERE media_id=? AND status='WAITING_INPUT'",
                truncate(message,1000),mediaId);
    }

    HeadUpAggregate headUpAggregate(long mediaId) {
        return jdbc.query("""
                SELECT m.duration_ms,w.start_ms,w.end_ms,w.quality,w.head_up_count,w.valid_observed_count,w.head_up_ratio
                FROM classroom_media m JOIN classroom_module_job j ON j.media_id=m.id AND j.module_type='HEAD_UP'
                LEFT JOIN classroom_head_up_window w ON w.job_id=j.id WHERE m.id=? ORDER BY w.start_ms""", rs -> {
            long duration=0,valid=0; double weighted=0; boolean has=false;
            while(rs.next()) {
                duration=rs.getLong(1);
                if(rs.getObject(2)==null||!"VALID".equals(rs.getString(4))) continue;
                long span=rs.getLong(3)-rs.getLong(2); Double rate=(Double)rs.getObject(7);
                Integer up=(Integer)rs.getObject(5),observed=(Integer)rs.getObject(6);
                if(rate==null&&up!=null&&observed!=null&&observed>0) rate=(double)up/observed;
                if(rate!=null) { valid+=span; weighted+=rate*span; has=true; }
            }
            return new HeadUpAggregate(duration,valid,has?weighted/valid:null);
        },mediaId);
    }

    ReportRow report(long mediaId) {
        return jdbc.query("""
                SELECT id,status,data_mode,mean_head_up_rate,valid_coverage_rate,summary_text,
                analysis_text,evidence_start_ms,evidence_end_ms,error_message,transcript_job_id,head_up_job_id
                FROM classroom_analysis_report WHERE media_id=? ORDER BY id DESC LIMIT 1""",
                (rs,n) -> new ReportRow(rs.getLong(1),rs.getString(2),rs.getString(3),(Double)rs.getObject(4),
                        (Double)rs.getObject(5),rs.getString(6),rs.getString(7),(Long)rs.getObject(8),
                        (Long)rs.getObject(9),rs.getString(10),rs.getLong(11),rs.getLong(12)), mediaId)
                .stream().findFirst().orElse(null);
    }

    MediaInput mediaInput(long mediaId) {
        return jdbc.query("SELECT id,sha256,duration_ms,status FROM classroom_media WHERE id=?",
                (rs,n)->new MediaInput(rs.getLong(1),rs.getString(2),rs.getLong(3),rs.getString(4)),mediaId)
                .stream().findFirst().orElse(null);
    }

    List<WindowEvidence> windowEvidence(long jobId) {
        return jdbc.query("""
                SELECT window_key,start_ms,end_ms,sample_at_ms,quality,quality_reason,
                       head_up_count,valid_observed_count,head_up_ratio
                FROM classroom_head_up_window WHERE job_id=? ORDER BY start_ms,end_ms,window_key""",
                (rs,n)->new WindowEvidence(rs.getString(1),rs.getLong(2),rs.getLong(3),
                        (Long)rs.getObject(4),rs.getString(5),rs.getString(6),
                        (Integer)rs.getObject(7),(Integer)rs.getObject(8),(Double)rs.getObject(9)),jobId);
    }

    AnalysisRunRow analysisRunByRequest(long objectiveReportId,String requestKeySha256) {
        return jdbc.query("""
                SELECT id,objective_report_id,media_id,request_key_sha256,input_version,input_sha256,state
                FROM classroom_analysis_run WHERE objective_report_id=? AND request_key_sha256=?""",
                (rs,n)->new AnalysisRunRow(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getString(4),
                        rs.getString(5),rs.getString(6),rs.getString(7)),objectiveReportId,requestKeySha256)
                .stream().findFirst().orElse(null);
    }

    StoredAnalysisRun analysisRunInput(long runId) {
        return jdbc.query("""
                SELECT id,objective_report_id,media_id,transcript_result_id,head_up_result_id,
                       input_version,input_sha256,input_json,state
                FROM classroom_analysis_run WHERE id=?""",
                (rs,n)->new StoredAnalysisRun(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),
                        rs.getLong(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9)),runId)
                .stream().findFirst().orElse(null);
    }

    long insertAnalysisRun(long objectiveReportId,long mediaId,long transcriptResultId,long headUpResultId,
                           String requestKeySha256,String inputVersion,String inputSha256,String inputJson) {
        return insert("""
                INSERT INTO classroom_analysis_run(objective_report_id,media_id,transcript_result_id,
                head_up_result_id,request_key_sha256,input_version,input_sha256,input_json,state)
                VALUES (?,?,?,?,?,?,?,?,'PREPARED')""",objectiveReportId,mediaId,transcriptResultId,
                headUpResultId,requestKeySha256,inputVersion,inputSha256,inputJson);
    }

    List<ClassroomAnalysisView.SegmentView> segments(long jobId) {
        return jdbc.query("""
                SELECT segment_key,start_ms,end_ms,text_content FROM classroom_transcript_segment
                WHERE job_id=? ORDER BY start_ms,end_ms,segment_key""",
                (rs,n)->new ClassroomAnalysisView.SegmentView(rs.getString(1),rs.getLong(2),rs.getLong(3),rs.getString(4)),jobId);
    }

    List<ClassroomAnalysisView.WindowView> windows(long jobId) {
        return jdbc.query("""
                SELECT window_key,start_ms,end_ms,quality,quality_reason,head_up_count,valid_observed_count
                FROM classroom_head_up_window WHERE job_id=? ORDER BY start_ms,end_ms,window_key""", (rs,n)-> {
            Integer up=(Integer)rs.getObject(6), observed=(Integer)rs.getObject(7);
            Double rate=up==null||observed==null||observed==0?null:(double)up/observed;
            return new ClassroomAnalysisView.WindowView(rs.getString(1),rs.getLong(2),rs.getLong(3),
                    rs.getString(4),rs.getString(5),up,observed,rate);
        },jobId);
    }

    record SessionRow(long id,long offeringId,long scheduleId,LocalDate date,String title,java.time.LocalDateTime createdAt) {}
    record UploadRequestRow(long sessionId,String requestSha256) {}
    record MediaRow(long id,String storageKey,String filename,String contentType,long size,String sha256,long durationMs,String status) {}
    record MediaAccess(String storageKey,String contentType,long size,long offeringId) {}
    record ReportRow(long id,String status,String mode,Double mean,Double coverage,String summary,String analysis,
                     Long evidenceStart,Long evidenceEnd,String error,long transcriptJobId,long headUpJobId) {}
    record MediaInput(long id,String sha256,long durationMs,String status) {}
    record ResultRef(long id,long jobId,int revision,String status,String sha256) {}
    record ProcessedRange(long startMs,long endMs) {}
    record WindowEvidence(String id,long startMs,long endMs,Long sampleAtMs,String quality,
                          String qualityReason,Integer headUpCount,Integer validObservedCount,Double headUpRatio) {}
    record AnalysisRunRow(long id,long objectiveReportId,long mediaId,String requestKeySha256,
                          String inputVersion,String inputSha256,String state) {}
    record StoredAnalysisRun(long id,long objectiveReportId,long mediaId,long transcriptResultId,
                             long headUpResultId,String inputVersion,String inputSha256,String inputJson,String state) {}
    record ModuleJobRow(long id,long mediaId,String moduleType,String status,String providerId,
                        String clientRequestId,String providerJobId,String moduleVersion,Integer resultRevision,
                        long classSessionId,String contentType,String sha256,long durationMs,int attempt,
                        int consecutiveFailures,LocalDateTime deadlineAt) {}
    record HeadUpAggregate(long durationMs,long validDurationMs,Double mean) {}
    record ModuleRetryState(long reportId,String status,String mode,long transcriptJobId,long headUpJobId) {}
    record ModuleRetryRequest(long userId,String operationType) {}

    private static String truncate(String value,int length) {
        return value==null?null:(value.length()<=length?value:value.substring(0,length));
    }
}
