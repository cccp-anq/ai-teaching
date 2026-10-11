package com.aijiaoxue.classroom;

import com.aijiaoxue.common.web.RequestIdFilter;
import com.aijiaoxue.fixtures.R__Phase1_acceptance_fixture;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.CookieManager;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.migration.JavaMigration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ClassroomModuleOrchestrationIntegrationTest.FixtureConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClassroomModuleOrchestrationIntegrationTest {
    private static final String PASSWORD="Fixture-Only-2026!";
    private static final String TOKEN="test-module-token";
    private static final String SIGNING_KEY="test-only-signing-key-with-at-least-32-bytes";
    private static final Path STORAGE=Path.of("..",".work","classroom-orchestration-test").toAbsolutePath().normalize();
    private static FakeModule transcription;
    private static FakeModule headUp;

    @Autowired WebApplicationContext context;
    @Autowired RequestIdFilter requestIds;
    @Autowired ClassroomAnalysisProperties properties;
    @Autowired TestMediaProbe mediaProbe;
    @Autowired ClassroomModuleWorker worker;
    @Autowired ClassroomReportWorker reportWorker;
    @Autowired ClassroomReportProperties reportProperties;
    @Autowired ClassroomAnalysisRunService analysisRuns;
    @Autowired ClassroomAnalysisInputSnapshotService inputSnapshots;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @LocalServerPort int port;
    MockMvc mvc;
    String originalFfprobePath;
    boolean retainFullChainAudit;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry values) throws Exception {
        boolean fullChain="true".equalsIgnoreCase(System.getenv("REAL_CLASSROOM_FULL_E2E"));
        boolean mysql=Boolean.getBoolean("mysqlMigration");
        if(fullChain&&!mysql) throw new IllegalArgumentException("Paid full-chain audit requires isolated MySQL");
        String url=mysql?Objects.requireNonNull(System.getenv("MIGRATION_TEST_URL"))
                :"jdbc:h2:mem:phase1-fixture-orchestration;MODE=MySQL;DB_CLOSE_DELAY=-1";
        if(mysql&&!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/ai_teaching_migration_[a-f0-9]{32}\\?sslMode=DISABLED&allowPublicKeyRetrieval=true"))
            throw new IllegalArgumentException("Dedicated MySQL test database required");
        String username=mysql?Objects.requireNonNull(System.getenv("MIGRATION_TEST_USERNAME")):"sa";
        String password=mysql?Objects.requireNonNull(System.getenv("MIGRATION_TEST_PASSWORD")):"";
        try(var db=DriverManager.getConnection(url,username,password);
            var tables=db.getMetaData().getTables(db.getCatalog(),mysql?null:"PUBLIC","%",new String[]{"TABLE"})) {
            if(tables.next()) throw new IllegalStateException("Orchestration suite requires a fresh database");
        }
        transcription=new FakeModule("TRANSCRIPTION","test-transcription",true);
        headUp=new FakeModule("HEAD_UP","test-head-up",false);
        values.add("spring.datasource.url",()->url);
        values.add("spring.datasource.username",()->username);
        values.add("spring.datasource.password",()->password);
        values.add("spring.datasource.driver-class-name",()->mysql?"com.mysql.cj.jdbc.Driver":"org.h2.Driver");
        values.add("server.servlet.session.cookie.secure",()->false);
        values.add("app.storage.root",()->fullChain?fullChainAuditRoot().resolve("main-media").toString():STORAGE.toString());
        values.add("app.classroom-analysis.integration-enabled",()->true);
        values.add("app.classroom-analysis.public-base-url",()->"http://127.0.0.1:1");
        values.add("app.classroom-analysis.media-signing-key",()->SIGNING_KEY);
        values.add("app.classroom-analysis.worker-initial-delay-ms",()->3_600_000);
        values.add("app.classroom-analysis.report.worker-initial-delay-ms",()->3_600_000);
        values.add("app.classroom-analysis.poll-interval-ms",()->1);
        values.add("app.classroom-analysis.transcription.base-url",transcription::baseUrl);
        values.add("app.classroom-analysis.transcription.bearer-token",()->TOKEN);
        values.add("app.classroom-analysis.transcription.provider-id",()->"test-transcription");
        values.add("app.classroom-analysis.transcription.module-version",()->"test-1");
        values.add("app.classroom-analysis.head-up.base-url",headUp::baseUrl);
        values.add("app.classroom-analysis.head-up.bearer-token",()->TOKEN);
        values.add("app.classroom-analysis.head-up.provider-id",()->"test-head-up");
        values.add("app.classroom-analysis.head-up.module-version",()->"test-1");
        values.add("app.classroom-analysis.head-up.window-ms",()->60_000);
        values.add("app.classroom-analysis.head-up.definition-id",()->"head-up-qwen-vl-v1");
    }

    @TestConfiguration(proxyBeanMethods=false)
    static class FixtureConfiguration {
        @Bean JavaMigration classroomOrchestrationFixture() throws Exception {
            return new R__Phase1_acceptance_fixture(true,true);
        }
        @Bean @Primary TestMediaProbe classroomMediaProbe(ClassroomAnalysisProperties properties,
                                                           ObjectMapper objectMapper) {
            return new TestMediaProbe(properties,objectMapper);
        }
    }

    @BeforeEach void setup() {
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(requestIds).apply(springSecurity()).build();
        jdbc.update("UPDATE user_role_scope SET offering_id=101 WHERE id=5");
        properties.setPublicBaseUrl("http://127.0.0.1:"+port);
        properties.setMockModulesEnabled(false);
        properties.setIntegrationEnabled(true);
        originalFfprobePath=properties.getFfprobePath();
        mediaProbe.useRealProbe=false;
        transcription.reset(); headUp.reset();
    }

    @AfterEach void cleanup() throws Exception {
        SecurityContextHolder.clearContext();
        reportProperties.setEnabled(false);
        reportProperties.setAllowLoopbackHttp(false);
        reportProperties.setApiKey("");
        reportProperties.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        mediaProbe.useRealProbe=false;
        properties.setFfprobePath(originalFfprobePath);
        if(retainFullChainAudit) return;
        jdbc.update("UPDATE user_role_scope SET offering_id=104 WHERE id=5");
        jdbc.update("DELETE FROM classroom_generated_report");
        jdbc.update("DELETE FROM classroom_report_retry_request");
        jdbc.update("DELETE FROM classroom_module_retry_request");
        jdbc.update("DELETE FROM classroom_report_dispatch");
        jdbc.update("DELETE FROM classroom_analysis_run");
        jdbc.update("DELETE FROM classroom_paid_test_reservation");
        jdbc.update("UPDATE classroom_paid_test_budget SET reserved_cents=0 WHERE scope_key='DEC030_TEST'");
        jdbc.update("DELETE FROM classroom_processed_range");
        jdbc.update("DELETE FROM classroom_module_result");
        jdbc.update("DELETE FROM classroom_transcript_segment");
        jdbc.update("DELETE FROM classroom_head_up_window");
        jdbc.update("DELETE FROM classroom_analysis_report");
        jdbc.update("UPDATE classroom_module_job SET recovery_source_job_id=NULL WHERE recovery_source_job_id IS NOT NULL");
        jdbc.update("DELETE FROM classroom_module_job");
        jdbc.update("DELETE FROM classroom_media");
        jdbc.update("DELETE FROM class_session");
        if(Files.isDirectory(STORAGE)) try(var files=Files.walk(STORAGE)) {
            files.sorted(Comparator.reverseOrder()).filter(path->!path.equals(STORAGE)).forEach(path->{
                try { Files.deleteIfExists(path); } catch(IOException failure) { throw new RuntimeException(failure); }
            });
        }
    }

    @AfterAll void stopServers() {
        if(transcription!=null) transcription.close();
        if(headUp!=null) headUp.close();
    }

    @Test void submitsRetriesPollsAndClosesSignedMediaAfterBothRealResults() throws Exception {
        var uploaded=mvc.perform(multipart("/api/v1/course-offerings/101/class-sessions/video")
                        .header("Idempotency-Key","orchestration-submit-0001")
                        .file(mp4()).param("scheduleId","1001").param("sessionDate","2026-09-14")
                        .param("durationMs","60000").with(csrf()).session(login()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.media[0].moduleJobs[0].status").value("QUEUED"))
                .andExpect(jsonPath("$.media[0].moduleJobs[1].status").value("QUEUED"))
                .andReturn();
        long mediaId=((Number)com.jayway.jsonpath.JsonPath.read(uploaded.getResponse().getContentAsString(),
                "$.media[0].mediaId")).longValue();

        makeDue(); worker.runOnce();
        assertEquals(1,transcription.postCount.get());
        assertEquals(1,headUp.postCount.get());
        assertEquals(1,transcription.healthCount.get());
        assertEquals(1,headUp.healthCount.get());
        assertEquals(1,jdbc.queryForObject("SELECT consecutive_failures FROM classroom_module_job WHERE media_id=? AND module_type='TRANSCRIPTION'",Integer.class,mediaId));
        assertNotNull(headUp.lastSourceUrl);
        String tampered=headUp.lastSourceUrl.substring(0,headUp.lastSourceUrl.length()-1)
                +(headUp.lastSourceUrl.endsWith("0")?"1":"0");
        var rejected=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(tampered)).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(404,rejected.statusCode());

        makeDue(); worker.runOnce();
        assertEquals(2,transcription.postCount.get());
        assertEquals(1,headUp.getCount.get());
        assertEquals(1,transcription.idempotencyKeys.stream().distinct().count());

        makeDue(); worker.runOnce();
        assertEquals(1,transcription.getCount.get());
        assertEquals("READY",jdbc.queryForObject("SELECT status FROM classroom_analysis_report WHERE media_id=?",String.class,mediaId));
        assertEquals("QUEUED",jdbc.queryForObject("SELECT state FROM classroom_report_dispatch WHERE media_id=?",
                String.class,mediaId));
        reportWorker.runOnce(); // Explicit opt-in is absent; no paid report is submitted.
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_paid_test_reservation",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_module_result",Integer.class));
        assertEquals(0.75,jdbc.queryForObject("SELECT mean_head_up_rate FROM classroom_analysis_report WHERE media_id=?",Double.class,mediaId));

        var pool=Executors.newFixedThreadPool(2);
        var start=new CountDownLatch(1);
        try {
            var first=pool.submit(()->{ start.await(); return analysisRuns.prepare(mediaId,"same-analysis-request-0001"); });
            var second=pool.submit(()->{ start.await(); return analysisRuns.prepare(mediaId,"same-analysis-request-0001"); });
            start.countDown();
            var firstRun=first.get(10,TimeUnit.SECONDS);
            var secondRun=second.get(10,TimeUnit.SECONDS);
            assertEquals(firstRun.id(),secondRun.id());
            assertTrue(firstRun.replayed()||secondRun.replayed());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_analysis_run WHERE media_id=?",
                    Integer.class,mediaId));
        } finally {
            start.countDown();
            pool.shutdownNow();
            pool.awaitTermination(10,TimeUnit.SECONDS);
        }

        var closed=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(headUp.lastSourceUrl)).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(404,closed.statusCode());

        var modelCalls=new AtomicInteger();
        var model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        model.createContext("/compatible-mode/v1/chat/completions",exchange->{
            modelCalls.incrementAndGet();
            try {
                var json=new ObjectMapper();
                var request=json.readTree(exchange.getRequestBody().readAllBytes());
                var packet=json.readTree(request.path("messages").get(1).path("content").asText());
                var segment=packet.path("evidenceIndex").get(0);
                String draft="""
                        {"reportVersion":"1.0","runId":%d,"inputSha256":"%s",
                         "topics":[{"startMs":0,"endMs":60000,"summary":"课堂讲解了基本概念",
                           "evidenceRefs":[{"resultId":%d,"itemType":"SEGMENT","itemId":"seg-1"}]}],
                         "changes":[],"interpretations":[],"limitations":["不能推断学习效果"]}
                        """.formatted(packet.path("runId").asLong(),packet.path("inputSha256").asText(),
                        segment.path("resultId").asLong());
                byte[] body=json.writeValueAsBytes(Map.of("id","local-full-pipeline","model","qwen3.7-plus",
                        "choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",draft))),
                        "usage",Map.of("prompt_tokens",20,"completion_tokens",30,"total_tokens",50)));
                exchange.sendResponseHeaders(200,body.length);
                try(var output=exchange.getResponseBody()) { output.write(body); }
            } catch(Exception failure) {
                byte[] body="local fixture failed".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500,body.length);
                try(var output=exchange.getResponseBody()) { output.write(body); }
            }
        });
        model.start();
        try {
            reportProperties.setAllowLoopbackHttp(true);
            reportProperties.setBaseUrl("http://127.0.0.1:"+model.getAddress().getPort()+"/compatible-mode/v1");
            reportProperties.setApiKey("local-full-pipeline-key");
            reportProperties.setEnabled(true);
            assertTrue(reportProperties.ready());
            reportWorker.runOnce();
            assertEquals("COMPLETED",jdbc.queryForObject(
                    "SELECT state FROM classroom_report_dispatch WHERE media_id=?",String.class,mediaId));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_generated_report",Integer.class));
            assertEquals("SUCCEEDED",jdbc.queryForObject(
                    "SELECT state FROM classroom_paid_test_reservation",String.class));
            reportWorker.runOnce();
            assertEquals(1,modelCalls.get());
        } finally { model.stop(0); }
    }

    @Test void waitingExternalJobsResumeWhenIntegrationBecomesReady() throws Exception {
        properties.setIntegrationEnabled(false);
        var uploaded=mvc.perform(multipart("/api/v1/course-offerings/101/class-sessions/video")
                        .header("Idempotency-Key","waiting-external-resume-0001")
                        .file(mp4()).param("scheduleId","1001").param("sessionDate","2026-09-14")
                        .param("durationMs","60000").with(csrf()).session(login()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.media[0].moduleJobs[0].status").value("WAITING_EXTERNAL"))
                .andExpect(jsonPath("$.media[0].moduleJobs[1].status").value("WAITING_EXTERNAL"))
                .andReturn();
        long mediaId=((Number)com.jayway.jsonpath.JsonPath.read(uploaded.getResponse().getContentAsString(),
                "$.media[0].mediaId")).longValue();
        worker.runOnce();
        assertEquals(0,transcription.postCount.get());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_module_job WHERE media_id=? AND status='WAITING_EXTERNAL'",
                Integer.class,mediaId));

        properties.setIntegrationEnabled(true);
        worker.runOnce();
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_module_job WHERE media_id=? AND status='WAITING_EXTERNAL'",
                Integer.class,mediaId));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_module_job WHERE media_id=? AND deadline_at IS NOT NULL",
                Integer.class,mediaId));
        assertEquals("test-transcription",jdbc.queryForObject("SELECT provider_id FROM classroom_module_job WHERE media_id=? AND module_type='TRANSCRIPTION'",
                String.class,mediaId));
        for(int attempt=0;attempt<4;attempt++) { makeDue(); worker.runOnce(); }
        assertEquals("READY",jdbc.queryForObject("SELECT status FROM classroom_analysis_report WHERE media_id=?",
                String.class,mediaId));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_module_result WHERE status='SUCCEEDED'",
                Integer.class));
    }

    @Test void rejectsModuleWhoseHealthIdentityDoesNotMatchRegistration() throws Exception {
        headUp.reportedProviderId="another-head-up";
        var uploaded=mvc.perform(multipart("/api/v1/course-offerings/101/class-sessions/video")
                        .header("Idempotency-Key","orchestration-identity-0001")
                        .file(mp4()).param("scheduleId","1001").param("sessionDate","2026-09-14")
                        .param("durationMs","60000").with(csrf()).session(login()))
                .andExpect(status().isCreated()).andReturn();
        long mediaId=((Number)com.jayway.jsonpath.JsonPath.read(uploaded.getResponse().getContentAsString(),
                "$.media[0].mediaId")).longValue();

        makeDue(); worker.runOnce();

        assertEquals(1,headUp.healthCount.get());
        assertEquals(0,headUp.postCount.get());
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM classroom_module_job WHERE media_id=? AND module_type='HEAD_UP'",String.class,mediaId));
        assertEquals("MODULE_PREFLIGHT_MISMATCH",jdbc.queryForObject("SELECT error_code FROM classroom_module_job WHERE media_id=? AND module_type='HEAD_UP'",String.class,mediaId));
    }

    @Test void supervisorRebuildsRetryableFailedEnvelopeWithNewRequestKeyAndPreservedAudit() throws Exception {
        headUp.failTerminalOnce=true;
        var supervisor=login();
        var uploaded=mvc.perform(multipart("/api/v1/course-offerings/101/class-sessions/video")
                        .header("Idempotency-Key","module-retry-upload-0001")
                        .file(mp4()).param("scheduleId","1001").param("sessionDate","2026-09-14")
                        .param("durationMs","60000").with(csrf()).session(supervisor))
                .andExpect(status().isCreated()).andReturn();
        long mediaId=((Number)com.jayway.jsonpath.JsonPath.read(uploaded.getResponse().getContentAsString(),
                "$.media[0].mediaId")).longValue();
        for(int turn=0;turn<4;turn++) { makeDue(); worker.runOnce(); }
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM classroom_analysis_report WHERE media_id=?",
                String.class,mediaId));
        long failedJobId=jdbc.queryForObject("""
                SELECT id FROM classroom_module_job WHERE media_id=? AND module_type='HEAD_UP' AND attempt=1""",
                Long.class,mediaId);
        String oldKey=jdbc.queryForObject("SELECT client_request_id FROM classroom_module_job WHERE id=?",
                String.class,failedJobId);
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM classroom_module_result WHERE job_id=?",
                String.class,failedJobId));
        assertTrue(jdbc.queryForObject("SELECT envelope_json FROM classroom_module_result WHERE job_id=?",
                String.class,failedJobId).contains("retryable\":true"));

        String path="/api/v1/class-sessions/media/"+mediaId+"/module-jobs/retry";
        mvc.perform(post(path).contentType("application/json").content("{\"confirmAdditionalCharge\":true}")
                        .header("Idempotency-Key","module-retry-teacher-0001")
                        .with(csrf()).session(login("teacher-a-test")))
                .andExpect(status().isNotFound());
        mvc.perform(post(path).contentType("application/json").content("{\"confirmAdditionalCharge\":false}")
                        .header("Idempotency-Key","module-retry-action-0001").with(csrf()).session(supervisor))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post(path).contentType("application/json").content("{\"confirmAdditionalCharge\":true}")
                        .header("Idempotency-Key","module-retry-action-0001").with(csrf()).session(supervisor))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.replayed").value(false));
        mvc.perform(post(path).contentType("application/json").content("{\"confirmAdditionalCharge\":true}")
                        .header("Idempotency-Key","module-retry-action-0001").with(csrf()).session(supervisor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));
        mvc.perform(post(path).contentType("application/json").content("{\"confirmAdditionalCharge\":true}")
                        .header("Idempotency-Key","module-retry-action-0002").with(csrf()).session(supervisor))
                .andExpect(status().isConflict());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_module_retry_request",Integer.class));
        assertEquals(1,jdbc.queryForObject("""
                SELECT COUNT(*) FROM classroom_module_job WHERE media_id=? AND module_type='HEAD_UP' AND attempt=2""",
                Integer.class,mediaId));
        assertEquals(1,jdbc.queryForObject("""
                SELECT COUNT(*) FROM classroom_module_job WHERE media_id=? AND module_type='TRANSCRIPTION'""",
                Integer.class,mediaId));
        long replacementJobId=jdbc.queryForObject("""
                SELECT id FROM classroom_module_job WHERE media_id=? AND module_type='HEAD_UP' AND attempt=2""",
                Long.class,mediaId);
        String newKey=jdbc.queryForObject("SELECT client_request_id FROM classroom_module_job WHERE id=?",
                String.class,replacementJobId);
        assertNotEquals(oldKey,newKey);
        assertEquals(failedJobId,jdbc.queryForObject("""
                SELECT previous_head_up_job_id FROM classroom_module_retry_request""",Long.class));
        assertEquals(replacementJobId,jdbc.queryForObject("""
                SELECT replacement_head_up_job_id FROM classroom_module_retry_request""",Long.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_report_dispatch WHERE media_id=?",
                Integer.class,mediaId)); // A report dispatch is created only after READY.
        for(int turn=0;turn<3;turn++) { makeDue(); worker.runOnce(); }
        assertEquals("READY",jdbc.queryForObject("SELECT status FROM classroom_analysis_report WHERE media_id=?",
                String.class,mediaId));
        assertEquals(2,headUp.postCount.get());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_module_result WHERE status='SUCCEEDED'",
                Integer.class));
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM classroom_module_job WHERE id=?",
                String.class,failedJobId));
        mvc.perform(get("/api/v1/class-sessions/"+com.jayway.jsonpath.JsonPath.read(
                        uploaded.getResponse().getContentAsString(),"$.classSessionId")).session(supervisor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.media[0].moduleJobs.length()").value(2));
    }

    @Test void supervisorRecoversExistingSuccessfulTranscriptionWithoutNewSubmission() throws Exception {
        var supervisor=login();
        var uploaded=mvc.perform(multipart("/api/v1/course-offerings/101/class-sessions/video")
                        .header("Idempotency-Key","recovery-upload-0001")
                        .file(mp4()).param("scheduleId","1001").param("sessionDate","2026-09-14")
                        .param("durationMs","60000").with(csrf()).session(supervisor))
                .andExpect(status().isCreated()).andReturn();
        long mediaId=((Number)com.jayway.jsonpath.JsonPath.read(uploaded.getResponse().getContentAsString(),
                "$.media[0].mediaId")).longValue();
        makeDue(); worker.runOnce();
        makeDue(); worker.runOnce();
        long sourceId=jdbc.queryForObject("""
                SELECT id FROM classroom_module_job WHERE media_id=? AND module_type='TRANSCRIPTION'""",
                Long.class,mediaId);
        assertNotNull(jdbc.queryForObject("SELECT provider_job_id FROM classroom_module_job WHERE id=?",
                String.class,sourceId));
        assertEquals("SUCCEEDED",jdbc.queryForObject("""
                SELECT status FROM classroom_module_job WHERE media_id=? AND module_type='HEAD_UP'""",
                String.class,mediaId));
        jdbc.update("""
                UPDATE classroom_module_job SET status='FAILED',error_code='MODULE_INTEGRATION_FAILED',
                error_message='prior result persistence failure',next_poll_at=NULL WHERE id=?""",sourceId);
        long newerFailed=insertFailedTranscriptionAttempt(mediaId,2,"missing-provider-job");
        jdbc.update("""
                UPDATE classroom_analysis_report SET status='FAILED',error_message='prior result persistence failure',
                transcript_job_id=? WHERE media_id=?""",newerFailed,mediaId);
        String path="/api/v1/class-sessions/media/"+mediaId+"/transcription-result/recover";
        mvc.perform(post(path).contentType("application/json").content("{\"confirmExistingResult\":true}")
                        .header("Idempotency-Key","recovery-action-0001")
                        .with(csrf()).session(login("teacher-a-test")))
                .andExpect(status().isNotFound());
        mvc.perform(post(path).contentType("application/json").content("{\"confirmExistingResult\":false}")
                        .header("Idempotency-Key","recovery-action-0001")
                        .with(csrf()).session(supervisor))
                .andExpect(status().isUnprocessableEntity());
        int submittedBefore=transcription.postCount.get();
        mvc.perform(post(path).contentType("application/json").content("{\"confirmExistingResult\":true}")
                        .header("Idempotency-Key","recovery-action-0001")
                        .with(csrf()).session(supervisor))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("READY"));
        mvc.perform(post(path).contentType("application/json").content("{\"confirmExistingResult\":true}")
                        .header("Idempotency-Key","recovery-action-0001")
                        .with(csrf()).session(supervisor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));
        assertEquals(submittedBefore,transcription.postCount.get());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_module_retry_request WHERE report_id=(SELECT id FROM classroom_analysis_report WHERE media_id=?)",Integer.class,mediaId));
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM classroom_module_job WHERE id=?",String.class,sourceId));
        assertEquals(newerFailed,jdbc.queryForObject("SELECT previous_transcript_job_id FROM classroom_module_retry_request",Long.class));
        assertEquals("READY",jdbc.queryForObject("SELECT status FROM classroom_analysis_report WHERE media_id=?",String.class,mediaId));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_transcript_segment WHERE job_id=(SELECT id FROM classroom_module_job WHERE recovery_source_job_id=?)",Integer.class,sourceId));
        assertEquals("QUEUED",jdbc.queryForObject("SELECT state FROM classroom_report_dispatch WHERE media_id=?",String.class,mediaId));
    }

    @Test void supervisorCannotCreateAChargedModuleAttemptPastTheConfiguredLimit() throws Exception {
        headUp.failTerminalOnce=true;
        var supervisor=login();
        var uploaded=mvc.perform(multipart("/api/v1/course-offerings/101/class-sessions/video")
                        .header("Idempotency-Key","module-limit-upload-0001")
                        .file(mp4()).param("scheduleId","1001").param("sessionDate","2026-09-14")
                        .param("durationMs","60000").with(csrf()).session(supervisor))
                .andExpect(status().isCreated()).andReturn();
        long mediaId=((Number)com.jayway.jsonpath.JsonPath.read(uploaded.getResponse().getContentAsString(),
                "$.media[0].mediaId")).longValue();
        for(int turn=0;turn<4;turn++) { makeDue(); worker.runOnce(); }
        jdbc.update("UPDATE classroom_module_job SET attempt=? WHERE media_id=? AND module_type='HEAD_UP'",
                properties.getMaxModuleAttempts(),mediaId);

        mvc.perform(post("/api/v1/class-sessions/media/"+mediaId+"/module-jobs/retry")
                        .contentType("application/json").content("{\"confirmAdditionalCharge\":true}")
                        .header("Idempotency-Key","module-limit-action-0001")
                        .with(csrf()).session(supervisor))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MODULE_RETRY_LIMIT_REACHED"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_module_retry_request",Integer.class));
    }

    private long insertFailedTranscriptionAttempt(long mediaId,int attempt,String providerJobId) {
        var key=new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection->{
            var statement=connection.prepareStatement("""
                    INSERT INTO classroom_module_job(media_id,module_type,status,provider_id,client_request_id,
                    provider_job_id,module_version,attempt,error_code,error_message)
                    VALUES (?,'TRANSCRIPTION','FAILED','test-transcription',?,?,?,?,'MODULE_INTEGRATION_FAILED',
                    'newer local persistence failure')""",new String[]{"id"});
            statement.setLong(1,mediaId);
            statement.setString(2,"newer-client-"+mediaId);
            statement.setString(3,providerJobId);
            statement.setString(4,"test-1");
            statement.setInt(5,attempt);
            return statement;
        },key);
        return key.getKey().longValue();
    }

    @Test void queuedJobStartsItsDeadlineWhenClaimedAfterAnOfflineWait() throws Exception {
        var uploaded=mvc.perform(multipart("/api/v1/course-offerings/101/class-sessions/video")
                        .header("Idempotency-Key","offline-queue-deadline-0001")
                        .file(mp4()).param("scheduleId","1001").param("sessionDate","2026-09-14")
                        .param("durationMs","60000").with(csrf()).session(login()))
                .andExpect(status().isCreated()).andReturn();
        long mediaId=((Number)com.jayway.jsonpath.JsonPath.read(uploaded.getResponse().getContentAsString(),
                "$.media[0].mediaId")).longValue();
        jdbc.update("UPDATE classroom_module_job SET deadline_at=? WHERE media_id=? AND module_type='HEAD_UP'",
                java.time.LocalDateTime.now().minusDays(1),mediaId);
        makeDue(); worker.runOnce();
        assertEquals(1,headUp.postCount.get());
        assertEquals(0,jdbc.queryForObject("""
                SELECT COUNT(*) FROM classroom_module_job WHERE media_id=? AND module_type='HEAD_UP'
                  AND status='FAILED'""",Integer.class,mediaId));
        assertTrue(jdbc.queryForObject("""
                SELECT deadline_at FROM classroom_module_job WHERE media_id=? AND module_type='HEAD_UP'""",
                java.time.LocalDateTime.class,mediaId).isAfter(java.time.LocalDateTime.now()));
    }

    @Test void realServletStreamsAuthorizedPlaybackRanges() throws Exception {
        var uploaded=mvc.perform(multipart("/api/v1/course-offerings/101/class-sessions/video")
                        .header("Idempotency-Key","playback-range-0001")
                        .file(mp4()).param("scheduleId","1001").param("sessionDate","2026-09-14")
                        .param("durationMs","60000").with(csrf()).session(login()))
                .andExpect(status().isCreated()).andReturn();
        long mediaId=((Number)com.jayway.jsonpath.JsonPath.read(uploaded.getResponse().getContentAsString(),
                "$.media[0].mediaId")).longValue();
        URI mediaUrl=URI.create("http://127.0.0.1:"+port+"/api/v1/class-sessions/media/"+mediaId+"/content");
        var anonymous=HttpClient.newHttpClient().send(HttpRequest.newBuilder(mediaUrl)
                .header("Range","bytes=4-7").GET().build(),HttpResponse.BodyHandlers.discarding());
        assertEquals(401,anonymous.statusCode());

        var client=HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        URI csrfUrl=URI.create("http://127.0.0.1:"+port+"/api/v1/auth/csrf");
        var csrfResponse=client.send(HttpRequest.newBuilder(csrfUrl).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200,csrfResponse.statusCode());
        String token=new ObjectMapper().readTree(csrfResponse.body()).path("token").asText();
        URI loginUrl=URI.create("http://127.0.0.1:"+port+"/api/v1/auth/login");
        var signedIn=client.send(HttpRequest.newBuilder(loginUrl)
                .header("Content-Type","application/x-www-form-urlencoded")
                .header("X-CSRF-TOKEN",token)
                .POST(HttpRequest.BodyPublishers.ofString("username=admin-test&password=Fixture-Only-2026%21"))
                .build(),HttpResponse.BodyHandlers.discarding());
        assertEquals(200,signedIn.statusCode());

        var ranged=client.send(HttpRequest.newBuilder(mediaUrl).header("Range","bytes=4-7")
                .GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(206,ranged.statusCode());
        assertEquals("bytes 4-7/16",ranged.headers().firstValue("Content-Range").orElseThrow());
        assertEquals("private, no-store",ranged.headers().firstValue("Cache-Control").orElseThrow());
        assertArrayEquals(new byte[]{'f','t','y','p'},ranged.body());

        var impossible=client.send(HttpRequest.newBuilder(mediaUrl).header("Range","bytes=16-20")
                .GET().build(),HttpResponse.BodyHandlers.discarding());
        assertEquals(416,impossible.statusCode());
    }

    @Test void realMediaKeepsOneVerifiedTimelineThroughBothFakeModules() throws Exception {
        String mediaSetting=System.getProperty("classroomMediaFixture","");
        String probeSetting=System.getProperty("classroomFfprobe","");
        Assumptions.assumeTrue(!mediaSetting.isBlank()&&!probeSetting.isBlank(),
                "Set classroomMediaFixture and classroomFfprobe for the optional real-media check");
        Path fixture=Path.of(mediaSetting).toAbsolutePath().normalize();
        Path ffprobe=Path.of(probeSetting).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(fixture),"Synthetic media fixture is missing");
        assertTrue(Files.isRegularFile(ffprobe),"ffprobe executable is missing");
        byte[] bytes=Files.readAllBytes(fixture);
        String expectedSha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        long expectedDurationMs=20_000;
        properties.setFfprobePath(ffprobe.toString());
        mediaProbe.useRealProbe=true;
        transcription.expectedMediaBytes=bytes; headUp.expectedMediaBytes=bytes;
        transcription.expectedDurationMs=expectedDurationMs; headUp.expectedDurationMs=expectedDurationMs;

        var session=login();
        var uploaded=mvc.perform(multipart("/api/v1/course-offerings/101/class-sessions/video")
                        .header("Idempotency-Key","real-media-timeline-0001")
                        .file(new MockMultipartFile("video",fixture.getFileName().toString(),"video/mp4",bytes))
                        .param("scheduleId","1001").param("sessionDate","2026-09-14")
                        .param("durationMs",Long.toString(expectedDurationMs))
                        .with(csrf()).session(session))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.media[0].durationMs").value(expectedDurationMs))
                .andExpect(jsonPath("$.media[0].sha256").value(expectedSha))
                .andReturn();
        long classSessionId=((Number)com.jayway.jsonpath.JsonPath.read(
                uploaded.getResponse().getContentAsString(),"$.classSessionId")).longValue();
        long mediaId=((Number)com.jayway.jsonpath.JsonPath.read(
                uploaded.getResponse().getContentAsString(),"$.media[0].mediaId")).longValue();

        for(int attempt=0;attempt<3;attempt++) { makeDue(); worker.runOnce(); }
        assertEquals("READY",jdbc.queryForObject(
                "SELECT status FROM classroom_analysis_report WHERE media_id=?",String.class,mediaId));
        assertEquals(expectedDurationMs,jdbc.queryForObject(
                "SELECT duration_ms FROM classroom_media WHERE id=?",Long.class,mediaId));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_module_result",Integer.class));
        assertEquals(0.75,jdbc.queryForObject(
                "SELECT mean_head_up_rate FROM classroom_analysis_report WHERE media_id=?",Double.class,mediaId));
        assertEquals(1.0,jdbc.queryForObject(
                "SELECT valid_coverage_rate FROM classroom_analysis_report WHERE media_id=?",Double.class,mediaId));
        mvc.perform(get("/api/v1/class-sessions/"+classSessionId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.media[0].report.transcript[0].endMs").value(expectedDurationMs))
                .andExpect(jsonPath("$.media[0].report.headUpWindows[0].endMs").value(expectedDurationMs));
        assertEquals(expectedDurationMs/2,jdbc.queryForObject(
                "SELECT sample_at_ms FROM classroom_head_up_window",Long.class));
    }

    @Test void explicitlyConfiguredRealModulesProcessOneSyntheticMedia() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("REAL_CLASSROOM_MODULE_E2E")),
                "Explicit REAL_CLASSROOM_MODULE_E2E=true is required for paid module calls");
        boolean fullChain="true".equalsIgnoreCase(System.getenv("REAL_CLASSROOM_FULL_E2E"));
        retainFullChainAudit=fullChain;
        Path fixture=Path.of(requiredEnvironment("REAL_CLASSROOM_MEDIA_FIXTURE")).toAbsolutePath().normalize();
        Path ffprobe=Path.of(requiredEnvironment("REAL_CLASSROOM_FFPROBE")).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(fixture),"Synthetic media fixture is missing");
        assertTrue(Files.isRegularFile(ffprobe),"ffprobe executable is missing");
        var transcriptionModule=properties.getTranscription();
        var headUpModule=properties.getHeadUp();
        String oldTranscriptionUrl=transcriptionModule.getBaseUrl();
        String oldTranscriptionToken=transcriptionModule.getBearerToken();
        String oldTranscriptionProvider=transcriptionModule.getProviderId();
        String oldTranscriptionVersion=transcriptionModule.getModuleVersion();
        String oldHeadUpUrl=headUpModule.getBaseUrl();
        String oldHeadUpToken=headUpModule.getBearerToken();
        String oldHeadUpProvider=headUpModule.getProviderId();
        String oldHeadUpVersion=headUpModule.getModuleVersion();
        int oldFailureLimit=properties.getMaxConsecutiveFailures();
        long oldDeadline=properties.getJobDeadlineSeconds();
        try {
            transcriptionModule.setBaseUrl(requiredEnvironment("REAL_TRANSCRIPTION_URL"));
            transcriptionModule.setBearerToken(requiredEnvironment("REAL_TRANSCRIPTION_TOKEN"));
            transcriptionModule.setProviderId("transcription-local");
            transcriptionModule.setModuleVersion("transcription-1.0.0");
            headUpModule.setBaseUrl(requiredEnvironment("REAL_HEADUP_URL"));
            headUpModule.setBearerToken(requiredEnvironment("REAL_HEADUP_TOKEN"));
            headUpModule.setProviderId("head-up-local");
            headUpModule.setModuleVersion("head-up-0.1.0");
            properties.setFfprobePath(ffprobe.toString());
            properties.setMaxConsecutiveFailures(fullChain?1:2);
            properties.setJobDeadlineSeconds(600);
            mediaProbe.useRealProbe=true;
            assertTrue(properties.integrationReady(),"Paid module integration is not fully configured");
            byte[] bytes=Files.readAllBytes(fixture);
            var session=login();
            var uploaded=mvc.perform(multipart("/api/v1/course-offerings/101/class-sessions/video")
                            .header("Idempotency-Key","paid-synthetic-module-0001")
                            .file(new MockMultipartFile("video",fixture.getFileName().toString(),"video/mp4",bytes))
                            .param("scheduleId","1001").param("sessionDate","2026-09-14")
                            .param("durationMs","20000").with(csrf()).session(session))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.media[0].moduleJobs[0].status").value("QUEUED"))
                    .andExpect(jsonPath("$.media[0].moduleJobs[1].status").value("QUEUED"))
                    .andReturn();
            long mediaId=((Number)com.jayway.jsonpath.JsonPath.read(uploaded.getResponse().getContentAsString(),
                    "$.media[0].mediaId")).longValue();
            makeDue();
            Instant deadline=Instant.now().plus(Duration.ofMinutes(10));
            while(Instant.now().isBefore(deadline)) {
                worker.runOnce();
                var states=jdbc.queryForList(
                        "SELECT status FROM classroom_module_job WHERE media_id=? ORDER BY module_type",String.class,mediaId);
                String reportStatus=jdbc.queryForObject(
                        "SELECT status FROM classroom_analysis_report WHERE media_id=?",String.class,mediaId);
                if("READY".equals(reportStatus)) {
                    assertEquals(2,jdbc.queryForObject(
                            "SELECT COUNT(*) FROM classroom_module_result r JOIN classroom_module_job j ON j.id=r.job_id WHERE j.media_id=?",
                            Integer.class,mediaId));
                    assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM classroom_transcript_segment",Integer.class)>0);
                    assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM classroom_head_up_window",Integer.class)>0);
                    assertEquals("EXTERNAL",jdbc.queryForObject(
                            "SELECT data_mode FROM classroom_analysis_report WHERE media_id=?",String.class,mediaId));
                    if(fullChain) {
                        // Validate the actual module evidence before permitting the one paid report call.
                        assertNotNull(inputSnapshots.build(mediaId));
                        assertEquals("QUEUED",jdbc.queryForObject(
                                "SELECT state FROM classroom_report_dispatch WHERE media_id=?",String.class,mediaId));
                        reportProperties.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
                        reportProperties.setApiKey(requiredEnvironment("REAL_CLASSROOM_REPORT_API_KEY"));
                        reportProperties.setModel("qwen3.7-plus");
                        reportProperties.setTimeoutSeconds(60);
                        reportProperties.setMaxOutputTokens(1024);
                        reportProperties.setCallCeilingCents(100);
                        reportProperties.setTotalCapCents(2000);
                        reportProperties.setBatchSize(1);
                        reportProperties.setLeaseSeconds(120);
                        reportProperties.setEnabled(true);
                        assertTrue(reportProperties.ready());
                        try {
                            reportWorker.runOnce(); // At most one possibly chargeable submission.
                            assertEquals("COMPLETED",jdbc.queryForObject(
                                    "SELECT state FROM classroom_report_dispatch WHERE media_id=?",String.class,mediaId));
                            assertEquals(1,jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM classroom_generated_report",Integer.class));
                            mvc.perform(get("/api/v1/class-sessions/media/"+mediaId+"/generated-report")
                                            .session(session))
                                    .andExpect(status().isOk())
                                    .andExpect(jsonPath("$.topics.length()").value(1))
                                    .andExpect(jsonPath("$.runId").doesNotExist());
                        } finally {
                            var audit=jdbc.queryForList("SELECT state,input_tokens,output_tokens,total_tokens "
                                    +"FROM classroom_paid_test_reservation WHERE category='REPORT'");
                            System.out.println("[full-chain-audit] mediaId="+mediaId+", reportDispatch="
                                    +jdbc.queryForObject("SELECT state FROM classroom_report_dispatch WHERE media_id=?",
                                            String.class,mediaId)+", reportReservation="+audit);
                        }
                    }
                    return;
                }
                if(states.stream().allMatch(state->"SUCCEEDED".equals(state)||"FAILED".equals(state))) {
                    var outcomes=jdbc.query(
                            "SELECT module_type,status,error_code FROM classroom_module_job WHERE media_id=? ORDER BY module_type",
                            (rs,n)->rs.getString(1)+":"+rs.getString(2)+":"+rs.getString(3),mediaId);
                    fail("Real module jobs finished without a READY report: "+outcomes);
                }
                Thread.sleep(2_000);
            }
            var outcomes=jdbc.query(
                    "SELECT module_type,status,error_code FROM classroom_module_job WHERE media_id=? ORDER BY module_type",
                    (rs,n)->rs.getString(1)+":"+rs.getString(2)+":"+rs.getString(3),mediaId);
            fail("Real module jobs did not become READY within ten minutes: "+outcomes);
        } finally {
            transcriptionModule.setBaseUrl(oldTranscriptionUrl);
            transcriptionModule.setBearerToken(oldTranscriptionToken);
            transcriptionModule.setProviderId(oldTranscriptionProvider);
            transcriptionModule.setModuleVersion(oldTranscriptionVersion);
            headUpModule.setBaseUrl(oldHeadUpUrl);
            headUpModule.setBearerToken(oldHeadUpToken);
            headUpModule.setProviderId(oldHeadUpProvider);
            headUpModule.setModuleVersion(oldHeadUpVersion);
            properties.setMaxConsecutiveFailures(oldFailureLimit);
            properties.setJobDeadlineSeconds(oldDeadline);
        }
    }

    private static String requiredEnvironment(String name) {
        String value=System.getenv(name);
        if(value==null||value.isBlank()) throw new IllegalStateException(name+" is required");
        return value;
    }

    private static Path fullChainAuditRoot() {
        Path work=Path.of("..",".work").toAbsolutePath().normalize();
        Path root=Path.of(requiredEnvironment("REAL_CLASSROOM_AUDIT_ROOT")).toAbsolutePath().normalize();
        if(!root.startsWith(work)||!root.getFileName().toString().startsWith("real-module-e2e-"))
            throw new IllegalArgumentException("Full-chain audit root must be a dedicated project work directory");
        return root;
    }

    private void makeDue() {
        jdbc.update("UPDATE classroom_module_job SET next_poll_at=?,lease_owner=NULL,lease_until=NULL WHERE status IN ('QUEUED','RUNNING')",
                java.time.LocalDateTime.now().minusSeconds(1));
    }

    private MockHttpSession login() throws Exception {
        return login("supervisor-a-test");
    }

    private MockHttpSession login(String username) throws Exception {
        return (MockHttpSession)mvc.perform(post("/api/v1/auth/login").with(csrf())
                        .param("username",username).param("password",PASSWORD))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    private MockMultipartFile mp4() {
        return new MockMultipartFile("video","lesson.mp4","video/mp4",
                new byte[]{0,0,0,24,'f','t','y','p','i','s','o','m',0,0,0,0});
    }

    static final class TestMediaProbe implements ClassroomMediaProbe {
        private final FfprobeClassroomMediaProbe realProbe;
        volatile boolean useRealProbe;
        TestMediaProbe(ClassroomAnalysisProperties properties,ObjectMapper objectMapper) {
            realProbe=new FfprobeClassroomMediaProbe(properties,objectMapper);
        }
        @Override public ProbeResult probe(Path mediaFile) {
            return useRealProbe?realProbe.probe(mediaFile):new ProbeResult(60_000,true,true);
        }
    }

    private static final class FakeModule implements AutoCloseable {
        private final String moduleType;
        private final String providerId;
        private final boolean failFirst;
        private final HttpServer server;
        private final ObjectMapper mapper=new ObjectMapper();
        private final Map<String,JsonNode> requests=new ConcurrentHashMap<>();
        private final AtomicInteger postCount=new AtomicInteger();
        private final AtomicInteger getCount=new AtomicInteger();
        private final AtomicInteger healthCount=new AtomicInteger();
        private final List<String> idempotencyKeys=new ArrayList<>();
        private volatile String lastSourceUrl;
        private volatile String reportedProviderId;
        private volatile byte[] expectedMediaBytes;
        private volatile long expectedDurationMs;
        private volatile boolean failTerminalOnce;

        FakeModule(String moduleType,String providerId,boolean failFirst) throws IOException {
            this.moduleType=moduleType; this.providerId=providerId; this.failFirst=failFirst;
            this.reportedProviderId=providerId;
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/api/v1/jobs",this::handle);
            server.createContext("/api/v1/health",this::health);
            server.start();
        }
        String baseUrl() { return "http://127.0.0.1:"+server.getAddress().getPort(); }
        void reset() { postCount.set(0); getCount.set(0); healthCount.set(0); requests.clear();
            idempotencyKeys.clear(); lastSourceUrl=null; reportedProviderId=providerId;
            expectedMediaBytes=new byte[]{0,0,0,24,'f','t','y','p','i','s','o','m',0,0,0,0};
            expectedDurationMs=60_000; failTerminalOnce=false; }
        @Override public void close() { server.stop(0); }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                if(!("Bearer "+TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    send(exchange,401,"{}"); return;
                }
                if("POST".equals(exchange.getRequestMethod())) post(exchange); else get(exchange);
            } catch(Exception failure) {
                send(exchange,500,"{}");
            }
        }
        private void health(HttpExchange exchange) throws IOException {
            healthCount.incrementAndGet();
            if(!("Bearer "+TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                send(exchange,401,"{}"); return;
            }
            Map<String,Object> root=new java.util.LinkedHashMap<>();
            root.put("status","UP"); root.put("moduleType",moduleType); root.put("providerId",reportedProviderId);
            root.put("moduleVersion","test-1");
            if("TRANSCRIPTION".equals(moduleType)) {
                root.put("schemaVersion","1.0"); root.put("timeBase","MEDIA_START_MS");
                root.put("capabilities",Map.of("languages",List.of("zh-CN"),"maxDurationMs","7200000"));
            } else {
                root.put("apiVersion","1.0"); root.put("definitionId","head-up-qwen-vl-v1");
                root.put("outputModes",List.of("COUNTS","RATIO_ONLY"));
                root.put("auth",Map.of("bearerRequired",true,"tokensConfigured",true));
                root.put("limits",Map.of("maxDurationMs",7200000));
            }
            send(exchange,200,mapper.writeValueAsString(root));
        }
        private void post(HttpExchange exchange) throws Exception {
            int call=postCount.incrementAndGet();
            String key=exchange.getRequestHeaders().getFirst("Idempotency-Key");
            idempotencyKeys.add(key);
            JsonNode request=mapper.readTree(exchange.getRequestBody().readAllBytes());
            assertEquals(key,request.path("clientRequestId").asText());
            if(failFirst&&call==1) { send(exchange,503,"{}"); return; }
            requests.put(key,request);
            assertEquals(expectedDurationMs,request.path("media").path("durationMs").longValue());
            assertEquals(expectedDurationMs,request.path("analysisRange").path("endMs").longValue());
            lastSourceUrl=request.path("media").path("sourceUrl").asText();
            var media=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(lastSourceUrl)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200,media.statusCode());
            assertArrayEquals(expectedMediaBytes,media.body());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(media.body())),
                    request.path("media").path("sha256").asText());
            send(exchange,202,envelope(request,false));
        }
        private void get(HttpExchange exchange) throws Exception {
            getCount.incrementAndGet();
            String key=exchange.getRequestHeaders().getFirst("X-Client-Request-Id");
            JsonNode request=requests.get(key);
            if(request==null) { send(exchange,404,"{}"); return; }
            if(failTerminalOnce) {
                failTerminalOnce=false;
                send(exchange,200,failedEnvelope(request)); return;
            }
            send(exchange,200,envelope(request,true));
        }
        private String failedEnvelope(JsonNode request) throws Exception {
            var failed=(tools.jackson.databind.node.ObjectNode)mapper.readTree(envelope(request,true));
            failed.put("status","FAILED"); failed.put("revision",1); failed.putNull("payload");
            failed.set("error",mapper.valueToTree(Map.of("code","TEMPORARY_MODULE_ERROR",
                    "message","temporary module failure","retryable",true)));
            return mapper.writeValueAsString(failed);
        }
        private String envelope(JsonNode request,boolean success) throws Exception {
            long durationMs=request.path("media").path("durationMs").longValue();
            Map<String,Object> root=new java.util.LinkedHashMap<>();
            root.put("schemaVersion","1.0"); root.put("clientRequestId",request.path("clientRequestId").asText());
            root.put("jobId",providerId+"-job"); root.put("moduleType",moduleType); root.put("providerId",providerId);
            root.put("moduleVersion","test-1"); root.put("classSessionId",request.path("classSessionId").asText());
            root.put("mediaId",request.path("mediaId").asText()); root.put("mediaSha256",request.path("media").path("sha256").asText());
            root.put("durationMs",durationMs); root.put("timeBase","MEDIA_START_MS");
            root.put("analysisRange",Map.of("startMs",0,"endMs",durationMs));
            root.put("status",success?"SUCCEEDED":"QUEUED"); root.put("revision",success?1:0);
            root.put("createdAt","2026-09-22T00:00:00Z"); root.put("updatedAt","2026-09-22T00:00:01Z");
            root.put("payload",success?payload(durationMs):null); root.put("error",null);
            return mapper.writeValueAsString(root);
        }
        private Map<String,Object> payload(long durationMs) {
            if("TRANSCRIPTION".equals(moduleType)) return Map.of(
                    "language","zh-CN","engineVersion","test","processedRanges",List.of(Map.of("startMs",0,"endMs",durationMs)),
                    "segments",List.of(Map.of("id","seg-1","startMs",0,"endMs",durationMs,"text","假模块转写")),"warnings",List.of());
            return Map.of("definitionId","head-up-qwen-vl-v1","denominatorDefinition","可观测对象",
                    "sampleMode","REPRESENTATIVE_FRAME","valueMode","COUNTS","windowMs",60_000,
                    "processedRanges",List.of(Map.of("startMs",0,"endMs",durationMs)),
                    "windows",List.of(Map.of("id","win-1","startMs",0,"endMs",durationMs,"sampleAtMs",durationMs/2,
                            "quality","VALID","headUpCount",3,"validObservedCount",4)),"warnings",List.of());
        }
        private static void send(HttpExchange exchange,int status,String body) throws IOException {
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        }
    }
}
