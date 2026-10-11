package com.aijiaoxue.classroom;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Internal paid-call reservations. The caller supplies an explicit cumulative ceiling. */
@Service
class ClassroomPaidTestBudgetService {
    static final int MAX_CONFIGURED_CAP_CENTS=10_000;
    private static final Pattern HASH=Pattern.compile("[0-9a-f]{64}");
    private static final Pattern MODEL=Pattern.compile("[A-Za-z0-9._:-]{1,100}");
    private static final Set<String> CATEGORIES=Set.of("REPORT","TRANSCRIPTION","HEAD_UP");
    private final JdbcTemplate jdbc;

    ClassroomPaidTestBudgetService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    /** A duplicate fingerprint reuses its reservation and must never trigger a second HTTP submission. */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    Reservation reserve(String category,long subjectId,String requestSha256,String modelId,
                        int callCeilingCents,int approvedTotalCapCents) {
        if(!CATEGORIES.contains(category)||subjectId<=0||requestSha256==null
                ||!HASH.matcher(requestSha256).matches()||modelId==null||!MODEL.matcher(modelId).matches()
                ||callCeilingCents<1||callCeilingCents>MAX_CONFIGURED_CAP_CENTS
                ||approvedTotalCapCents<1||approvedTotalCapCents>MAX_CONFIGURED_CAP_CENTS)
            throw new BudgetViolation("PAID_TEST_RESERVATION_INVALID");
        var existing=byHash(requestSha256);
        if(existing!=null) return reused(existing,category,subjectId,modelId,callCeilingCents);
        try {
            jdbc.update("""
                    INSERT INTO classroom_paid_test_reservation(request_sha256,category,subject_id,model_id,
                        reserved_cents,state) VALUES (?,?,?,?,?,'RESERVED')
                    """,requestSha256,category,subjectId,modelId,callCeilingCents);
        } catch(DuplicateKeyException collision) {
            // The winning transaction has committed before the unique-key loser can re-read it.
            existing=byHash(requestSha256);
            if(existing==null) throw collision;
            return reused(existing,category,subjectId,modelId,callCeilingCents);
        }
        int updated=jdbc.update("""
                UPDATE classroom_paid_test_budget
                SET reserved_cents=reserved_cents+?,updated_at=CURRENT_TIMESTAMP
                WHERE scope_key='DEC030_TEST' AND reserved_cents+?<=?
                """,callCeilingCents,callCeilingCents,approvedTotalCapCents);
        if(updated!=1) throw new BudgetViolation("PAID_TEST_BUDGET_EXHAUSTED");
        return new Reservation(byHash(requestSha256),false);
    }

    /** Exactly one caller may cross the durable boundary into a possibly charged submission. */
    @Transactional
    boolean tryStartSubmission(long reservationId) {
        return jdbc.update("""
                UPDATE classroom_paid_test_reservation SET state='SUBMISSION_UNKNOWN',updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND state='RESERVED'
                """,reservationId)==1;
    }

    /** Only metadata and a response hash are retained; no raw prompt, draft or API key. */
    @Transactional
    void markSucceeded(long reservationId,String providerRequestId,long inputTokens,long outputTokens,
                       long totalTokens,String responseSha256) {
        if(providerRequestId==null||providerRequestId.isBlank()||providerRequestId.length()>200
                ||inputTokens<0||outputTokens<0||inputTokens>Long.MAX_VALUE-outputTokens
                ||totalTokens<inputTokens+outputTokens||responseSha256==null
                ||!HASH.matcher(responseSha256).matches())
            throw new BudgetViolation("PAID_TEST_RESULT_INVALID");
        int updated=jdbc.update("""
                UPDATE classroom_paid_test_reservation
                SET state='SUCCEEDED',provider_request_id=?,input_tokens=?,output_tokens=?,total_tokens=?,
                    response_sha256=?,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND state IN ('RESERVED','SUBMISSION_UNKNOWN')
                """,providerRequestId,inputTokens,outputTokens,totalTokens,responseSha256,reservationId);
        if(updated==1) return;
        var row=byId(reservationId);
        if(row==null||!"SUCCEEDED".equals(row.state())||!providerRequestId.equals(row.providerRequestId())
                ||row.inputTokens()==null||row.inputTokens()!=inputTokens
                ||row.outputTokens()==null||row.outputTokens()!=outputTokens
                ||row.totalTokens()==null||row.totalTokens()!=totalTokens
                ||!responseSha256.equals(row.responseSha256()))
            throw new BudgetViolation("PAID_TEST_RESERVATION_STATE_INVALID");
    }

    @Transactional(readOnly=true)
    long reservedCents() {
        Long value=jdbc.queryForObject("SELECT reserved_cents FROM classroom_paid_test_budget WHERE scope_key='DEC030_TEST'",
                Long.class);
        if(value==null) throw new IllegalStateException("Paid-test budget row is missing");
        return value;
    }

    private Reservation reused(Row row,String category,long subjectId,String modelId,int callCeilingCents) {
        if(!row.category().equals(category)||row.subjectId()!=subjectId||!row.modelId().equals(modelId)
                ||row.reservedCents()!=callCeilingCents)
            throw new BudgetViolation("PAID_TEST_REQUEST_CONFLICT");
        return new Reservation(row,true);
    }

    private Row byHash(String requestSha256) {
        List<Row> rows=jdbc.query("""
                SELECT id,request_sha256,category,subject_id,model_id,reserved_cents,state,
                    provider_request_id,input_tokens,output_tokens,total_tokens,response_sha256
                FROM classroom_paid_test_reservation WHERE request_sha256=?
                """,(rs,index)->read(rs),requestSha256);
        return rows.isEmpty()?null:rows.get(0);
    }

    private Row byId(long id) {
        List<Row> rows=jdbc.query("""
                SELECT id,request_sha256,category,subject_id,model_id,reserved_cents,state,
                    provider_request_id,input_tokens,output_tokens,total_tokens,response_sha256
                FROM classroom_paid_test_reservation WHERE id=?
                """,(rs,index)->read(rs),id);
        return rows.isEmpty()?null:rows.get(0);
    }

    private static Row read(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getLong("id"),rs.getString("request_sha256"),rs.getString("category"),
                rs.getLong("subject_id"),rs.getString("model_id"),rs.getLong("reserved_cents"),
                rs.getString("state"),rs.getString("provider_request_id"),
                nullableLong(rs,"input_tokens"),nullableLong(rs,"output_tokens"),
                nullableLong(rs,"total_tokens"),rs.getString("response_sha256"));
    }

    private static Long nullableLong(java.sql.ResultSet rs,String column) throws java.sql.SQLException {
        long value=rs.getLong(column);
        return rs.wasNull()?null:value;
    }

    record Row(long id,String requestSha256,String category,long subjectId,String modelId,long reservedCents,
               String state,String providerRequestId,Long inputTokens,Long outputTokens,Long totalTokens,
               String responseSha256) {}
    record Reservation(Row row,boolean replayed) {}
    static final class BudgetViolation extends IllegalStateException {
        private final String code;
        BudgetViolation(String code) { super(code); this.code=code; }
        String code() { return code; }
    }
}
