package com.aijiaoxue.classroom;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Private, validated AI drafts. Callers must authorize the related offering before reading. */
@Service
class ClassroomGeneratedReportStore {
    private static final Pattern MODEL=Pattern.compile("[A-Za-z0-9._:-]{1,100}");
    private final JdbcTemplate jdbc;
    private final ClassroomAnalysisRunService runs;
    private final ObjectMapper mapper;

    ClassroomGeneratedReportStore(JdbcTemplate jdbc,ClassroomAnalysisRunService runs,ObjectMapper mapper) {
        this.jdbc=jdbc; this.runs=runs; this.mapper=mapper;
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    StoredReport persist(long runId,long reservationId,String modelId,String responseJson) {
        if(runId<=0||reservationId<=0||modelId==null||!MODEL.matcher(modelId).matches()
                ||responseJson==null||responseJson.getBytes(StandardCharsets.UTF_8).length>64*1024)
            throw new IllegalArgumentException("Generated report metadata is invalid");
        RunRow run=lockRun(runId);
        if(run==null) throw new IllegalStateException("Analysis run is missing");
        String responseSha=sha256(responseJson);
        ReservationRow reservation=reservation(reservationId);
        if(reservation==null||!"REPORT".equals(reservation.category())||reservation.subjectId()!=runId
                ||!"SUCCEEDED".equals(reservation.state())||!modelId.equals(reservation.modelId())
                ||!responseSha.equals(reservation.responseSha256()))
            throw new IllegalStateException("Report response is not bound to a successful reservation");
        StoredReport existing=reportRow(runId);
        if(existing!=null) {
            if(!"COMPLETED".equals(run.state())||existing.reservationId()!=reservationId
                    ||!existing.inputSha256().equals(run.inputSha256())
                    ||!existing.modelId().equals(modelId)||!existing.responseSha256().equals(responseSha))
                throw new IllegalStateException("Generated report conflicts with existing run");
            return existing;
        }
        if(!"PREPARED".equals(run.state()))
            throw new IllegalStateException("Analysis run is not ready for a generated report");
        var draft=runs.validateDraft(runId,responseJson);
        if(draft.runId()!=runId||!run.inputSha256().equals(draft.inputSha256()))
            throw new IllegalStateException("Validated draft does not match stored run");
        String normalized;
        try { normalized=mapper.writeValueAsString(draft); }
        catch(Exception failure) { throw new IllegalStateException("Cannot serialize validated report"); }
        if(normalized.getBytes(StandardCharsets.UTF_8).length>90_000)
            throw new IllegalStateException("Validated report exceeds local size limit");
        var key=new GeneratedKeyHolder();
        String outputSha=sha256(normalized);
        jdbc.update(connection->{
            PreparedStatement statement=connection.prepareStatement("""
                    INSERT INTO classroom_generated_report(run_id,reservation_id,input_sha256,model_id,
                    response_sha256,report_version,normalized_sha256,normalized_json)
                    VALUES (?,?,?,?,?,'1.0',?,?)""",new String[]{"id"});
            statement.setLong(1,runId); statement.setLong(2,reservationId);
            statement.setString(3,run.inputSha256()); statement.setString(4,modelId);
            statement.setString(5,responseSha); statement.setString(6,outputSha);
            statement.setString(7,normalized);
            return statement;
        },key);
        if(jdbc.update("UPDATE classroom_analysis_run SET state='COMPLETED' WHERE id=? AND state='PREPARED'",runId)!=1)
            throw new IllegalStateException("Analysis run changed during report persistence");
        return new StoredReport(key.getKey().longValue(),runId,reservationId,run.inputSha256(),modelId,
                responseSha,outputSha,normalized);
    }

    @Transactional(readOnly=true)
    StoredReport report(long runId) {
        StoredReport row=reportRow(runId);
        if(row==null) return null;
        List<RunRow> runs=jdbc.query("SELECT state,input_sha256 FROM classroom_analysis_run WHERE id=?",
                (rs,n)->new RunRow(rs.getString(1),rs.getString(2)),runId);
        if(runs.size()!=1||!"COMPLETED".equals(runs.get(0).state())
                ||!row.inputSha256().equals(runs.get(0).inputSha256()))
            throw new IllegalStateException("Stored generated report is not bound to a completed run");
        return row;
    }

    @Transactional(readOnly=true)
    StoredReport latestForMedia(long mediaId) {
        List<Long> runIds=jdbc.query("""
                SELECT r.run_id FROM classroom_generated_report r
                JOIN classroom_analysis_run a ON a.id=r.run_id
                -- A delayed older run may persist after a supervisor's newer generation.
                -- Select by immutable run creation order, not report insert order.
                WHERE a.media_id=? ORDER BY a.id DESC LIMIT 1""",
                (rs,n)->rs.getLong(1),mediaId);
        return runIds.isEmpty()?null:report(runIds.get(0));
    }

    /** Do not show an older result while a newer supervisor-requested run is still active. */
    @Transactional(readOnly=true)
    StoredReport visibleForMedia(long mediaId) {
        var saved=latestForMedia(mediaId);
        if(saved==null) return null;
        List<DispatchState> dispatches=jdbc.query("""
                SELECT state,run_id,manual_generation FROM classroom_report_dispatch
                WHERE media_id=? ORDER BY id DESC LIMIT 1""",
                (rs,n)->new DispatchState(rs.getString(1),(Long)rs.getObject(2),rs.getInt(3)),mediaId);
        if(dispatches.isEmpty()) return saved;
        var dispatch=dispatches.get(0);
        if(("QUEUED".equals(dispatch.state())||"CLAIMED".equals(dispatch.state()))
                && (dispatch.runId()!=null && dispatch.runId()>saved.runId()
                    || dispatch.runId()==null && dispatch.manualGeneration()>0)) return null;
        return saved;
    }

    /** Metadata only: old report bodies remain private until version-bound evidence is available. */
    @Transactional(readOnly=true)
    VersionPage versionsForMedia(long mediaId,int page) {
        if(page<0||page>1000) throw new IllegalArgumentException("Report version page is invalid");
        int total=jdbc.queryForObject("""
                SELECT COUNT(*) FROM classroom_generated_report g
                JOIN classroom_analysis_run r ON r.id=g.run_id WHERE r.media_id=?""",
                Integer.class,mediaId);
        int offset=page*20;
        if(offset>=total) return new VersionPage(List.of(),null);
        var visible=visibleForMedia(mediaId);
        List<VersionRow> rows=jdbc.query("""
                SELECT g.id,g.run_id,g.created_at FROM classroom_generated_report g
                JOIN classroom_analysis_run r ON r.id=g.run_id
                WHERE r.media_id=? ORDER BY r.id DESC LIMIT 20 OFFSET ?""",
                (rs,n)->new VersionRow(rs.getLong(1),rs.getLong(2),
                        rs.getTimestamp(3).toLocalDateTime()),
                mediaId,offset);
        List<Version> items=new java.util.ArrayList<>(rows.size());
        for(int i=0;i<rows.size();i++) {
            var row=rows.get(i);
            report(row.runId()); // Reject corrupted or incomplete persisted reports.
            items.add(new Version(row.reportId(),row.createdAt(),
                    visible!=null&&visible.runId()==row.runId()));
        }
        return new VersionPage(List.copyOf(items),offset+rows.size()<total?page+1:null);
    }

    private RunRow lockRun(long runId) {
        List<RunRow> rows=jdbc.query("SELECT state,input_sha256 FROM classroom_analysis_run WHERE id=? FOR UPDATE",
                (rs,n)->new RunRow(rs.getString(1),rs.getString(2)),runId);
        return rows.isEmpty()?null:rows.get(0);
    }

    private ReservationRow reservation(long reservationId) {
        List<ReservationRow> rows=jdbc.query("""
                SELECT category,subject_id,model_id,state,response_sha256
                FROM classroom_paid_test_reservation WHERE id=?""",
                (rs,n)->new ReservationRow(rs.getString(1),rs.getLong(2),rs.getString(3),
                        rs.getString(4),rs.getString(5)),reservationId);
        return rows.isEmpty()?null:rows.get(0);
    }

    private StoredReport reportRow(long runId) {
        List<StoredReport> rows=jdbc.query("""
                SELECT id,run_id,reservation_id,input_sha256,model_id,response_sha256,
                       normalized_sha256,normalized_json
                FROM classroom_generated_report WHERE run_id=?""",
                (rs,n)->new StoredReport(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getString(4),
                        rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8)),runId);
        if(rows.isEmpty()) return null;
        StoredReport row=rows.get(0);
        if(!row.normalizedSha256().equals(sha256(row.normalizedJson())))
            throw new IllegalStateException("Stored generated report failed integrity check");
        return row;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private record RunRow(String state,String inputSha256) {}
    private record DispatchState(String state,Long runId,int manualGeneration) {}
    private record VersionRow(long reportId,long runId,LocalDateTime createdAt) {}
    record Version(long reportId,LocalDateTime generatedAt,boolean current) {}
    record VersionPage(List<Version> items,Integer nextPage) {}
    private record ReservationRow(String category,long subjectId,String modelId,String state,String responseSha256) {}
    record StoredReport(long id,long runId,long reservationId,String inputSha256,String modelId,
                        String responseSha256,String normalizedSha256,String normalizedJson) {}
}
