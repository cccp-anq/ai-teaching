package com.aijiaoxue.classroom;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
class ClassroomPaidTestBudgetIntegrationTest {
    @Autowired ClassroomPaidTestBudgetService budget;
    @Autowired JdbcTemplate jdbc;
    private final ObjectMapper mapper=new ObjectMapper();
    private HttpServer server;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry values) throws Exception {
        boolean mysql=Boolean.getBoolean("mysqlMigration");
        String url=mysql?Objects.requireNonNull(System.getenv("MIGRATION_TEST_URL"))
                :"jdbc:h2:mem:paid_budget_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1";
        if(mysql&&!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/ai_teaching_migration_[a-f0-9]{32}\\?sslMode=DISABLED&allowPublicKeyRetrieval=true"))
            throw new IllegalArgumentException("Dedicated MySQL test database required");
        String user=mysql?Objects.requireNonNull(System.getenv("MIGRATION_TEST_USERNAME")):"sa";
        String password=mysql?Objects.requireNonNull(System.getenv("MIGRATION_TEST_PASSWORD")):"";
        try(var db=DriverManager.getConnection(url,user,password);
            var tables=db.getMetaData().getTables(db.getCatalog(),mysql?null:"PUBLIC","%",new String[]{"TABLE"})) {
            if(tables.next()) throw new IllegalStateException("Budget suite refuses nonempty databases");
        }
        values.add("spring.datasource.url",()->url);
        values.add("spring.datasource.username",()->user);
        values.add("spring.datasource.password",()->password);
        values.add("spring.datasource.driver-class-name",()->mysql?"com.mysql.cj.jdbc.Driver":"org.h2.Driver");
    }

    @BeforeEach void emptyLedger() {
        jdbc.update("DELETE FROM classroom_paid_test_reservation");
        jdbc.update("UPDATE classroom_paid_test_budget SET reserved_cents=0 WHERE scope_key='DEC030_TEST'");
    }

    @AfterEach void stopServer() { if(server!=null) { server.stop(0); server=null; } }

    @Test void cumulativeCeilingIsAtomicAndExhaustionDoesNotPersistAReservation() {
        var first=budget.reserve("REPORT",11,hash('a'),"qwen3.7-plus",1300,2000);
        assertFalse(first.replayed());
        assertEquals(1300,budget.reservedCents());
        assertEquals("PAID_TEST_BUDGET_EXHAUSTED",assertThrows(ClassroomPaidTestBudgetService.BudgetViolation.class,
                ()->budget.reserve("TRANSCRIPTION",12,hash('b'),"qwen3.8-flash",701,2000)).code());
        assertEquals(0,count(hash('b')));
        budget.reserve("HEAD_UP",13,hash('c'),"qwen-vl-max",700,2000);
        assertEquals(2000,budget.reservedCents());
        assertEquals("PAID_TEST_BUDGET_EXHAUSTED",assertThrows(ClassroomPaidTestBudgetService.BudgetViolation.class,
                ()->budget.reserve("REPORT",14,hash('d'),"qwen3.7-plus",1,2000)).code());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_paid_test_reservation",Integer.class));
    }

    @Test void explicitlyRaisedCeilingCanExceedOriginalTestCapButRemainsFinite() {
        budget.reserve("REPORT",15,hash('9'),"qwen3.7-plus",2500,5000);
        assertEquals(2500,budget.reservedCents());
        assertEquals("PAID_TEST_BUDGET_EXHAUSTED",assertThrows(ClassroomPaidTestBudgetService.BudgetViolation.class,
                ()->budget.reserve("REPORT",16,hash('8'),"qwen3.7-plus",2501,5000)).code());
        assertEquals("PAID_TEST_RESERVATION_INVALID",assertThrows(ClassroomPaidTestBudgetService.BudgetViolation.class,
                ()->budget.reserve("REPORT",17,hash('7'),"qwen3.7-plus",1,10001)).code());
    }

    @Test void duplicateFingerprintReplaysWithoutChargingOrResubmitting() {
        var first=budget.reserve("REPORT",21,hash('e'),"qwen3.7-plus",100,2000);
        var replay=budget.reserve("REPORT",21,hash('e'),"qwen3.7-plus",100,2000);
        assertTrue(replay.replayed());
        assertEquals(first.row().id(),replay.row().id());
        assertEquals(100,budget.reservedCents());
        assertEquals(1,count(hash('e')));
        assertEquals("PAID_TEST_REQUEST_CONFLICT",assertThrows(ClassroomPaidTestBudgetService.BudgetViolation.class,
                ()->budget.reserve("REPORT",22,hash('e'),"qwen3.7-plus",100,2000)).code());
        assertEquals("PAID_TEST_REQUEST_CONFLICT",assertThrows(ClassroomPaidTestBudgetService.BudgetViolation.class,
                ()->budget.reserve("REPORT",21,hash('e'),"qwen3.7-plus",101,2000)).code());
    }

    @Test void unknownSubmissionAndSuccessKeepReservationAndMetadataOnly() {
        var reserved=budget.reserve("REPORT",31,hash('f'),"qwen3.7-plus",150,2000);
        long id=reserved.row().id();
        assertTrue(budget.tryStartSubmission(id));
        assertFalse(budget.tryStartSubmission(id));
        assertEquals("SUBMISSION_UNKNOWN",budget.reserve("REPORT",31,hash('f'),"qwen3.7-plus",150,2000).row().state());
        assertEquals(150,budget.reservedCents());
        budget.markSucceeded(id,"provider-req-1",7,9,16,hash('0'));
        budget.markSucceeded(id,"provider-req-1",7,9,16,hash('0'));
        assertFalse(budget.tryStartSubmission(id));
        var replay=budget.reserve("REPORT",31,hash('f'),"qwen3.7-plus",150,2000);
        assertTrue(replay.replayed());
        assertEquals("SUCCEEDED",replay.row().state());
        assertEquals(Long.valueOf(7),replay.row().inputTokens());
        assertEquals(hash('0'),replay.row().responseSha256());
        assertEquals(150,budget.reservedCents());
        assertEquals("PAID_TEST_RESERVATION_STATE_INVALID",assertThrows(ClassroomPaidTestBudgetService.BudgetViolation.class,
                ()->budget.markSucceeded(id,"provider-req-2",7,9,16,hash('0'))).code());
    }

    @Test void concurrentDistinctReservationsCannotExceedCap() throws Exception {
        var start=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->reserveAfter(start,hash('1')));
            var second=pool.submit(()->reserveAfter(start,hash('2')));
            start.countDown();
            String a=first.get(15,TimeUnit.SECONDS);
            String b=second.get(15,TimeUnit.SECONDS);
            assertTrue(("RESERVED".equals(a)&&"PAID_TEST_BUDGET_EXHAUSTED".equals(b))
                    ||("RESERVED".equals(b)&&"PAID_TEST_BUDGET_EXHAUSTED".equals(a)),a+" / "+b);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1200,budget.reservedCents());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_paid_test_reservation",Integer.class));
    }

    @Test void concurrentSameFingerprintHasOneNewReservationAndOneReplay() throws Exception {
        var start=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->reserveAfter(start,hash('3')));
            var second=pool.submit(()->reserveAfter(start,hash('3')));
            start.countDown();
            String a=first.get(15,TimeUnit.SECONDS);
            String b=second.get(15,TimeUnit.SECONDS);
            assertTrue(("RESERVED".equals(a)&&"REPLAYED".equals(b))
                    ||("RESERVED".equals(b)&&"REPLAYED".equals(a)),a+" / "+b);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1200,budget.reservedCents());
        assertEquals(1,count(hash('3')));
    }

    @Test void reportPathReservesBeforeOneSubmissionAndReplaysWithoutHttp() throws Exception {
        var calls=new AtomicInteger();
        startModel(calls,200);
        var invocation=reportInvocation(77,"qwen3.7-plus",false);
        var first=invocation.invoke(77,100,2000);
        assertFalse(first.replayed());
        assertEquals(77,first.report().runId());
        assertEquals(1,calls.get());
        assertEquals(100,budget.reservedCents());
        var replay=invocation.invoke(77,100,2000);
        assertTrue(replay.replayed());
        assertNull(replay.report());
        assertEquals(first.reservationId(),replay.reservationId());
        assertEquals(1,calls.get());
        assertEquals("SUCCEEDED",jdbc.queryForObject(
                "SELECT state FROM classroom_paid_test_reservation WHERE id=?",String.class,first.reservationId()));
        var conflict=assertThrows(ClassroomReportModelInvocation.PreSubmissionFailure.class,
                ()->reportInvocation(77,"qwen3.8-flash",false).invoke(77,100,2000));
        assertEquals("PAID_TEST_REQUEST_CONFLICT",
                ((ClassroomPaidTestBudgetService.BudgetViolation)conflict.getCause()).code());
        assertEquals(1,calls.get());
    }

    @Test void reportPathRejectsExhaustedBudgetBeforeHttp() throws Exception {
        var calls=new AtomicInteger();
        startModel(calls,200);
        budget.reserve("TRANSCRIPTION",5,hash('4'),"qwen3.8-flash",1900,2000);
        var exhausted=assertThrows(ClassroomReportModelInvocation.PreSubmissionFailure.class,
                ()->reportInvocation(78,"qwen3.7-plus",false).invoke(78,101,2000));
        assertEquals("PAID_TEST_BUDGET_EXHAUSTED",
                ((ClassroomPaidTestBudgetService.BudgetViolation)exhausted.getCause()).code());
        assertEquals(0,calls.get());
        assertEquals(1900,budget.reservedCents());
    }

    @Test void reservedReplayCanSubmitOnceButUnknownReplayCannot() throws Exception {
        var calls=new AtomicInteger();
        startModel(calls,200);
        long runId=81;
        String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                ("classroom-report-run-v1:"+runId+":"+hash('a')).getBytes(StandardCharsets.UTF_8)));
        var reserved=budget.reserve("REPORT",runId,fingerprint,"qwen3.7-plus",100,2000);
        assertEquals("RESERVED",reserved.row().state());
        var invocation=reportInvocation(runId,"qwen3.7-plus",false);
        assertFalse(invocation.invoke(runId,100,2000).replayed());
        assertEquals(1,calls.get());
        assertTrue(invocation.invoke(runId,100,2000).replayed());
        assertEquals(1,calls.get());
        assertFalse(budget.tryStartSubmission(reserved.row().id()));
    }

    @Test void reportPathTreatsHttpFailureAsUnknownAndNeverAutomaticallyResubmits() throws Exception {
        var calls=new AtomicInteger();
        startModel(calls,429);
        var invocation=reportInvocation(79,"qwen3.7-plus",false);
        assertEquals("REPORT_HTTP_ERROR",assertThrows(ClassroomReportModelClient.ModelCallFailure.class,
                ()->invocation.invoke(79,120,2000)).code());
        var replay=invocation.invoke(79,120,2000);
        assertTrue(replay.replayed());
        assertEquals("SUBMISSION_UNKNOWN",jdbc.queryForObject(
                "SELECT state FROM classroom_paid_test_reservation WHERE id=?",String.class,replay.reservationId()));
        assertEquals(120,budget.reservedCents());
        assertEquals(1,calls.get());
    }

    @Test void reportPathRecordsPaidResponseEvenWhenDraftValidationFails() throws Exception {
        var calls=new AtomicInteger();
        startModel(calls,200);
        var invocation=reportInvocation(80,"qwen3.7-plus",true);
        assertThrows(IllegalArgumentException.class,()->invocation.invoke(80,130,2000));
        var replay=invocation.invoke(80,130,2000);
        assertTrue(replay.replayed());
        assertEquals("SUCCEEDED",jdbc.queryForObject(
                "SELECT state FROM classroom_paid_test_reservation WHERE id=?",String.class,replay.reservationId()));
        assertEquals(130,budget.reservedCents());
        assertEquals(1,calls.get());
    }

    private ClassroomReportModelInvocation reportInvocation(long runId,String model,boolean invalidDraft) {
        var runs=mock(ClassroomAnalysisRunService.class);
        var reports=mock(ClassroomGeneratedReportStore.class);
        var packet=packet(runId);
        when(runs.prepareEgress(runId)).thenReturn(packet);
        if(invalidDraft) when(reports.persist(eq(runId),anyLong(),eq(model),anyString()))
                .thenThrow(new IllegalArgumentException("invalid report draft"));
        else when(reports.persist(eq(runId),anyLong(),eq(model),anyString())).thenAnswer(call->
                new ClassroomGeneratedReportStore.StoredReport(999,runId,call.getArgument(1),
                        packet.inputSha256(),model,hash('b'),hash('c'),"{}"));
        var settings=new ClassroomReportModelClient.Settings(
                URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/compatible-mode/v1"),
                "fake-local-key",model,10,1024,true);
        return new ClassroomReportModelInvocation(runs,budget,
                new ClassroomReportModelClient(settings,mapper),reports);
    }

    private ClassroomAnalysisEgressPacket.Packet packet(long runId) {
        var coverage=new ClassroomAnalysisInputSnapshotService.Coverage(20_000,1);
        return new ClassroomAnalysisEgressPacket.Packet(ClassroomAnalysisEgressPacket.VERSION,runId,
                hash('a'),20_000,11,22,new ClassroomAnalysisInputSnapshotService.Statistics(0.75,1),
                new ClassroomAnalysisInputSnapshotService.Completeness(coverage,coverage,coverage,coverage),
                List.of(new ClassroomAnalysisEgressPacket.Segment("seg-1",0,20_000,"讲解递归")),
                List.of(new ClassroomAnalysisEgressPacket.Window("win-1",0,20_000,10_000L,"VALID",3,4,0.75)),
                List.of(new ClassroomAnalysisInputSnapshotService.EvidenceItem(11,"SEGMENT","seg-1",0,20_000)),
                List.of("不能推断理解程度"));
    }

    private void startModel(AtomicInteger calls,int status) throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/compatible-mode/v1/chat/completions",exchange->{
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] response=status==200?mapper.writeValueAsBytes(Map.of("id","provider-test-1",
                    "model","qwen3.7-plus","choices",List.of(Map.of("finish_reason","stop",
                            "message",Map.of("content","{}"))),
                    "usage",Map.of("prompt_tokens",20,"completion_tokens",10,"total_tokens",30)))
                    :"rate limited".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,response.length);
            try(var output=exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
    }

    private String reserveAfter(CountDownLatch start,String hash) {
        try {
            start.await();
            return budget.reserve("REPORT",41,hash,"qwen3.7-plus",1200,2000).replayed()?"REPLAYED":"RESERVED";
        } catch(ClassroomPaidTestBudgetService.BudgetViolation failure) {
            return failure.code();
        } catch(InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private int count(String hash) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM classroom_paid_test_reservation WHERE request_sha256=?",
                Integer.class,hash);
    }

    private static String hash(char digit) { return String.valueOf(digit).repeat(64); }
}
