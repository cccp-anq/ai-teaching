package com.aijiaoxue.classroom;

import com.aijiaoxue.common.web.RequestIdFilter;
import com.aijiaoxue.fixtures.R__Phase1_acceptance_fixture;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Objects;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.migration.JavaMigration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.http.MediaType;

@SpringBootTest
@Import(ClassroomAnalysisIntegrationTest.FixtureConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional
class ClassroomAnalysisIntegrationTest {
    private static final String TEST_PASSWORD = "Fixture-Only-2026!";
    private static final Path STORAGE = Path.of("..",".work","classroom-analysis-test").toAbsolutePath().normalize();
    @Autowired WebApplicationContext context;
    @Autowired RequestIdFilter requestIds;
    @Autowired ClassroomAnalysisProperties properties;
    @Autowired StubClassroomMediaProbe mediaProbe;
    @Autowired ClassroomAnalysisInputSnapshotService inputSnapshots;
    @Autowired ClassroomAnalysisRunService analysisRuns;
    @Autowired ClassroomGeneratedReportStore generatedReports;
    @Autowired ClassroomPaidTestBudgetService paidBudget;
    @Autowired ClassroomReportWorker reportWorker;
    @Autowired ClassroomReportDispatch reportDispatch;
    @Autowired ClassroomReportProperties reportProperties;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    MockMvc mvc;
    HttpServer reportServer;
    boolean retainCommittedAudit;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry values) throws Exception {
        boolean mysql=Boolean.getBoolean("mysqlMigration");
        String url=mysql?Objects.requireNonNull(System.getenv("MIGRATION_TEST_URL"))
                :"jdbc:h2:mem:phase1-fixture-classroom;MODE=MySQL;DB_CLOSE_DELAY=-1";
        if(mysql&&!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/ai_teaching_migration_[a-f0-9]{32}\\?sslMode=DISABLED&allowPublicKeyRetrieval=true"))
            throw new IllegalArgumentException("Dedicated MySQL test database required");
        String user=mysql?Objects.requireNonNull(System.getenv("MIGRATION_TEST_USERNAME")):"sa";
        String password=mysql?Objects.requireNonNull(System.getenv("MIGRATION_TEST_PASSWORD")):"";
        try(var db=DriverManager.getConnection(url,user,password);
            var tables=db.getMetaData().getTables(db.getCatalog(),mysql?null:"PUBLIC","%",new String[]{"TABLE"})) {
            if(tables.next()) throw new IllegalStateException("Classroom suite refuses nonempty databases");
        }
        values.add("spring.datasource.url",()->url);
        values.add("spring.datasource.username",()->user);
        values.add("spring.datasource.password",()->password);
        values.add("spring.datasource.driver-class-name",()->mysql?"com.mysql.cj.jdbc.Driver":"org.h2.Driver");
        values.add("server.servlet.session.cookie.secure",()->false);
        values.add("app.storage.root",()->STORAGE.toString());
        values.add("app.classroom-analysis.mock-modules-enabled",()->false);
        values.add("app.classroom-analysis.report.worker-initial-delay-ms",()->"3600000");
    }

    @TestConfiguration(proxyBeanMethods=false)
    static class FixtureConfiguration {
        @Bean JavaMigration classroomFixture() throws Exception { return new R__Phase1_acceptance_fixture(true,true); }
        @Bean @Primary StubClassroomMediaProbe classroomMediaProbe() { return new StubClassroomMediaProbe(); }
    }

    @BeforeEach void setup() {
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(requestIds).apply(springSecurity()).build();
        properties.setMockModulesEnabled(false);
        mediaProbe.reset();
        jdbc.update("UPDATE user_role_scope SET offering_id=101 WHERE id=5");
    }
    @AfterEach void cleanupFiles() throws Exception {
        SecurityContextHolder.clearContext();
        jdbc.update("UPDATE user_role_scope SET offering_id=104 WHERE id=5");
        reportProperties.setEnabled(false);
        reportProperties.setAllowLoopbackHttp(false);
        reportProperties.setApiKey("");
        reportProperties.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        if(reportServer!=null) { reportServer.stop(0); reportServer=null; }
        if(Files.isDirectory(STORAGE)) try(var files=Files.walk(STORAGE)) {
            files.sorted(Comparator.reverseOrder()).filter(path->!path.equals(STORAGE)).forEach(path-> {
                try { Files.deleteIfExists(path); } catch(Exception failure) { throw new RuntimeException(failure); }
            });
        }
    }
    @AfterAll void cleanup() throws Exception {
        // The explicit paid probe keeps its synthetic run and reservation for audit. The
        // fixture cleaner cannot remove referenced users while that committed run exists.
        if(retainCommittedAudit) return;
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .javaMigrations(new R__Phase1_acceptance_fixture(false,true)).cleanDisabled(true).load().migrate();
    }

    @Test void uploadStoresVideoAndCreatesWaitingJobsWhenModulesAreAbsent() throws Exception {
        var response=mvc.perform(multipart("/api/v1/course-offerings/101/class-sessions/video")
                        .header("Idempotency-Key","upload-waiting-0001")
                        .file(mp4()).param("scheduleId","1001").param("sessionDate","2026-09-14")
                        .param("title","递归课堂").param("durationMs","60000")
                        .with(csrf()).session(login("supervisor-a-test")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed","false"))
                .andExpect(jsonPath("$.offeringId").value(101))
                .andExpect(jsonPath("$.scheduleId").value(1001))
                .andExpect(jsonPath("$.media[0].status").value("READY"))
                .andExpect(jsonPath("$.media[0].moduleJobs.length()").value(2))
                .andExpect(jsonPath("$.media[0].moduleJobs[0].status").value("WAITING_EXTERNAL"))
                .andExpect(jsonPath("$.media[0].moduleJobs[1].status").value("WAITING_EXTERNAL"))
                .andExpect(jsonPath("$.media[0].report.status").value("WAITING_INPUT"))
                .andExpect(jsonPath("$.media[0].report.dataMode").value("EXTERNAL"))
                .andReturn();
        long id=((Number)com.jayway.jsonpath.JsonPath.read(response.getResponse().getContentAsString(), "$.classSessionId")).longValue();
        long mediaId=((Number)com.jayway.jsonpath.JsonPath.read(response.getResponse().getContentAsString(), "$.media[0].mediaId")).longValue();
        assertEquals("INPUT_NOT_READY",assertThrows(ClassroomAnalysisException.class,
                ()->inputSnapshots.build(mediaId)).code());
        assertEquals("INPUT_NOT_READY",assertThrows(ClassroomAnalysisException.class,
                ()->analysisRuns.prepare(mediaId,"analysis-waiting-0001")).code());
        mvc.perform(get("/api/v1/class-sessions/"+id).session(login("admin-test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("递归课堂"));
        mvc.perform(get("/api/v1/class-sessions/media/"+mediaId+"/content").session(login("admin-test")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(content().bytes(mp4().getBytes()));
        mvc.perform(get("/api/v1/class-sessions/media/"+mediaId+"/content")
                        .header("Range","bytes=4-7").session(login("admin-test")))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range","bytes 4-7/16"))
                .andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(content().bytes(new byte[]{'f','t','y','p'}));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM class_session",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_module_job",Integer.class));
    }

    @Test void globalAdminCanUploadClassroomVideo() throws Exception {
        mvc.perform(multipart("/api/v1/course-offerings/101/class-sessions/video")
                        .header("Idempotency-Key","admin-upload-0001")
                        .file(mp4()).param("scheduleId","1001").param("sessionDate","2026-09-14")
                        .param("durationMs","60000").with(csrf()).session(login("admin-test")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.offeringId").value(101))
                .andExpect(jsonPath("$.media[0].moduleJobs.length()").value(2));
    }

    @Test void uploadStoresServerProbedDurationWithinConfiguredTolerance() throws Exception {
        mediaProbe.result=new ClassroomMediaProbe.ProbeResult(59_250,true,true);
        mvc.perform(multipart("/api/v1/course-offerings/101/class-sessions/video")
                        .header("Idempotency-Key","upload-probed-duration-0001")
                        .file(mp4()).param("scheduleId","1001").param("sessionDate","2026-09-14")
                        .param("durationMs","60000").with(csrf()).session(login("supervisor-a-test")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.media[0].durationMs").value(59_250));
        assertEquals(59_250L,jdbc.queryForObject("SELECT duration_ms FROM classroom_media",Long.class));
    }

    @Test void uploadRejectsDurationOutsideConfiguredToleranceBeforeCreatingRecords() throws Exception {
        mediaProbe.result=new ClassroomMediaProbe.ProbeResult(63_000,true,true);
        mvc.perform(multipart("/api/v1/course-offerings/101/class-sessions/video")
                        .header("Idempotency-Key","upload-duration-mismatch-0001")
                        .file(mp4()).param("scheduleId","1001").param("sessionDate","2026-09-14")
                        .param("durationMs","60000").with(csrf()).session(login("supervisor-a-test")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VIDEO_DURATION_MISMATCH"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM class_session",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_media",Integer.class));
    }

    @Test void uploadRejectsMediaWithoutAudioBeforeCreatingRecords() throws Exception {
        mediaProbe.result=new ClassroomMediaProbe.ProbeResult(60_000,true,false);
        mvc.perform(multipart("/api/v1/course-offerings/101/class-sessions/video")
                        .header("Idempotency-Key","upload-no-audio-0001")
                        .file(mp4()).param("scheduleId","1001").param("sessionDate","2026-09-14")
                        .param("durationMs","60000").with(csrf()).session(login("supervisor-a-test")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AUDIO_STREAM_REQUIRED"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM class_session",Integer.class));
    }

    @Test void uploadRejectsMediaWithoutVideoBeforeCreatingRecords() throws Exception {
        mediaProbe.result=new ClassroomMediaProbe.ProbeResult(60_000,false,true);
        mvc.perform(multipart("/api/v1/course-offerings/101/class-sessions/video")
                        .header("Idempotency-Key","upload-no-video-0001")
                        .file(mp4()).param("scheduleId","1001").param("sessionDate","2026-09-14")
                        .param("durationMs","60000").with(csrf()).session(login("supervisor-a-test")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VIDEO_STREAM_REQUIRED"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM class_session",Integer.class));
    }

    @Test void uploadReplaysTheSameRequestAndRejectsKeyReuseWithDifferentContent() throws Exception {
        var session=login("supervisor-a-test");
        String key="upload-retry-0001";
        var first=mvc.perform(multipart("/api/v1/course-offerings/101/class-sessions/video")
                        .header("Idempotency-Key",key).file(mp4()).param("scheduleId","1001")
                        .param("sessionDate","2026-09-14").param("title","幂等课堂")
                        .param("durationMs","60000").with(csrf()).session(session))
                .andExpect(status().isCreated()).andReturn();
        long classSessionId=((Number)com.jayway.jsonpath.JsonPath.read(
                first.getResponse().getContentAsString(),"$.classSessionId")).longValue();
        long mediaId=((Number)com.jayway.jsonpath.JsonPath.read(
                first.getResponse().getContentAsString(),"$.media[0].mediaId")).longValue();

        mvc.perform(multipart("/api/v1/course-offerings/101/class-sessions/video")
                        .header("Idempotency-Key",key).file(mp4()).param("scheduleId","1001")
                        .param("sessionDate","2026-09-14").param("title","幂等课堂")
                        .param("durationMs","60000").with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed","true"))
                .andExpect(jsonPath("$.classSessionId").value(classSessionId))
                .andExpect(jsonPath("$.media[0].mediaId").value(mediaId));

        mvc.perform(multipart("/api/v1/course-offerings/101/class-sessions/video")
                        .header("Idempotency-Key",key).file(mp4()).param("scheduleId","1001")
                        .param("sessionDate","2026-09-14").param("title","不同课堂")
                        .param("durationMs","60000").with(csrf()).session(session))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM class_session",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_media",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_module_job",Integer.class));
        String storedKey=jdbc.queryForObject("SELECT upload_idempotency_key_sha256 FROM class_session",
                String.class);
        assertNotEquals(key,storedKey);
        assertEquals(64,storedKey.length());
    }

    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void simultaneousUploadsWithOneKeyCreateOnlyOneClassroom() throws Exception {
        var firstSession=login("supervisor-a-test");
        var secondSession=login("supervisor-a-test");
        var bothAtProbe=new CountDownLatch(2);
        var releaseProbe=new CountDownLatch(1);
        mediaProbe.bothAtProbe=bothAtProbe;
        mediaProbe.releaseProbe=releaseProbe;
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->uploadConcurrently(firstSession));
            var second=pool.submit(()->uploadConcurrently(secondSession));
            assertTrue(bothAtProbe.await(10,TimeUnit.SECONDS));
            releaseProbe.countDown();
            var firstResult=first.get(15,TimeUnit.SECONDS);
            var secondResult=second.get(15,TimeUnit.SECONDS);
            assertEquals(1,java.util.stream.Stream.of(firstResult,secondResult)
                    .filter(result->result.getResponse().getStatus()==201).count());
            assertEquals(1,java.util.stream.Stream.of(firstResult,secondResult)
                    .filter(result->result.getResponse().getStatus()==200).count());
            assertEquals("true",java.util.stream.Stream.of(firstResult,secondResult)
                    .filter(result->result.getResponse().getStatus()==200).findFirst().orElseThrow()
                    .getResponse().getHeader("Idempotency-Replayed"));
            long firstId=((Number)com.jayway.jsonpath.JsonPath.read(
                    firstResult.getResponse().getContentAsString(),"$.classSessionId")).longValue();
            long secondId=((Number)com.jayway.jsonpath.JsonPath.read(
                    secondResult.getResponse().getContentAsString(),"$.classSessionId")).longValue();
            assertEquals(firstId,secondId);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM class_session",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_media",Integer.class));
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_module_job",Integer.class));
        } finally {
            releaseProbe.countDown();
            pool.shutdownNow();
            pool.awaitTermination(10,TimeUnit.SECONDS);
            mediaProbe.bothAtProbe=null;
            mediaProbe.releaseProbe=null;
            jdbc.update("DELETE FROM classroom_analysis_report WHERE media_id IN (SELECT id FROM classroom_media WHERE class_session_id IN (SELECT id FROM class_session WHERE title='并发幂等课堂'))");
            jdbc.update("DELETE FROM classroom_module_job WHERE media_id IN (SELECT id FROM classroom_media WHERE class_session_id IN (SELECT id FROM class_session WHERE title='并发幂等课堂'))");
            jdbc.update("DELETE FROM classroom_media WHERE class_session_id IN (SELECT id FROM class_session WHERE title='并发幂等课堂')");
            jdbc.update("DELETE FROM class_session WHERE title='并发幂等课堂'");
        }
    }

    private org.springframework.test.web.servlet.MvcResult uploadConcurrently(MockHttpSession session) throws Exception {
        return mvc.perform(multipart("/api/v1/course-offerings/101/class-sessions/video")
                .header("Idempotency-Key","concurrent-upload-0001").file(mp4())
                .param("scheduleId","1001").param("sessionDate","2026-09-14")
                .param("title","并发幂等课堂").param("durationMs","60000")
                .with(csrf()).session(session)).andReturn();
    }

    @Test void explicitMockModeCompletesAutomaticPipelineAndLabelsEveryResultSimulated() throws Exception {
        properties.setMockModulesEnabled(true);
        var uploaded=mvc.perform(multipart("/api/v1/course-offerings/101/class-sessions/video")
                        .header("Idempotency-Key","upload-mock-0001")
                        .file(mp4()).param("scheduleId","1001").param("sessionDate","2026-09-14")
                        .param("durationMs","60000").with(csrf()).session(login("supervisor-a-test")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.media[0].moduleJobs[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.media[0].moduleJobs[1].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.media[0].report.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.media[0].report.dataMode").value("SIMULATED"))
                .andExpect(jsonPath("$.media[0].report.meanHeadUpRate").value(1.85d/3d))
                .andExpect(jsonPath("$.media[0].report.validCoverageRate").value(0.75))
                .andExpect(jsonPath("$.media[0].report.transcript.length()").value(2))
                .andExpect(jsonPath("$.media[0].report.headUpWindows.length()").value(4))
                .andExpect(jsonPath("$.media[0].report.headUpWindows[2].headUpRate").doesNotExist())
                .andExpect(jsonPath("$.media[0].report.analysis").value(org.hamcrest.Matchers.containsString("不能据此判断")))
                .andReturn();
        long mediaId=((Number)com.jayway.jsonpath.JsonPath.read(uploaded.getResponse().getContentAsString(),
                "$.media[0].mediaId")).longValue();
        assertEquals("INPUT_NOT_READY",assertThrows(ClassroomAnalysisException.class,
                ()->inputSnapshots.build(mediaId)).code());
        assertEquals("INPUT_NOT_READY",assertThrows(ClassroomAnalysisException.class,
                ()->analysisRuns.prepare(mediaId,"analysis-simulated-0001")).code());
    }

    @Test void uploadFailsClosedForAuthenticationAuthorizationAssociationAndSignature() throws Exception {
        var request=multipart("/api/v1/course-offerings/101/class-sessions/video")
                .file(mp4()).param("scheduleId","1001").param("sessionDate","2026-09-14").param("durationMs","60000");
        mvc.perform(request.with(csrf())).andExpect(status().isUnauthorized());

        mvc.perform(multipart("/api/v1/course-offerings/101/class-sessions/video").file(mp4())
                        .param("scheduleId","1001").param("sessionDate","2026-09-14").param("durationMs","60000")
                        .with(csrf()).session(login("supervisor-a-test")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));

        mvc.perform(multipart("/api/v1/course-offerings/109/class-sessions/video").file(mp4())
                        .header("Idempotency-Key","upload-hidden-0001")
                        .param("scheduleId","1001").param("sessionDate","2026-09-14").param("durationMs","60000")
                        .with(csrf()).session(login("teacher-a-test")))
                .andExpect(status().isNotFound());

        for(String name:List.of("director-a-test","teacher-b-test"))
            mvc.perform(multipart("/api/v1/course-offerings/101/class-sessions/video").file(mp4())
                            .header("Idempotency-Key","upload-role-denied-"+name)
                            .param("scheduleId","1001").param("sessionDate","2026-09-14")
                            .param("durationMs","60000").with(csrf()).session(login(name)))
                    .andExpect(status().isForbidden());

        jdbc.update("UPDATE user_role_scope SET offering_id=104 WHERE id=5");
        mvc.perform(multipart("/api/v1/course-offerings/101/class-sessions/video").file(mp4())
                        .header("Idempotency-Key","upload-wrong-supervisor-0001")
                        .param("scheduleId","1001").param("sessionDate","2026-09-14")
                        .param("durationMs","60000").with(csrf()).session(login("supervisor-a-test")))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE user_role_scope SET offering_id=101 WHERE id=5");

        mvc.perform(multipart("/api/v1/course-offerings/101/class-sessions/video").file(mp4())
                        .header("Idempotency-Key","upload-schedule-0001")
                        .param("scheduleId","1003").param("sessionDate","2026-09-14").param("durationMs","60000")
                        .with(csrf()).session(login("supervisor-a-test")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SCHEDULE_MISMATCH"));

        mvc.perform(multipart("/api/v1/course-offerings/101/class-sessions/video")
                        .header("Idempotency-Key","upload-signature-0001")
                        .file(new MockMultipartFile("video","fake.mp4","video/mp4","not-video".getBytes()))
                        .param("scheduleId","1001").param("sessionDate","2026-09-14").param("durationMs","60000")
                        .with(csrf()).session(login("supervisor-a-test")))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("VIDEO_SIGNATURE_MISMATCH"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM class_session",Integer.class));
    }

    @Test void listDoesNotExposeAnotherOffering() throws Exception {
        mvc.perform(get("/api/v1/course-offerings/109/class-sessions").session(login("teacher-a-test")))
                .andExpect(status().isNotFound());
    }

    @Test void importsStrictRealModuleResultsAndMarksReportReadyAfterBothInputs() throws Exception {
        var session=login("supervisor-a-test");
        var upload=mvc.perform(multipart("/api/v1/course-offerings/101/class-sessions/video")
                        .header("Idempotency-Key","upload-results-0001")
                        .file(mp4()).param("scheduleId","1001").param("sessionDate","2026-09-14")
                        .param("durationMs","60000").with(csrf()).session(session))
                .andExpect(status().isCreated()).andReturn();
        String body=upload.getResponse().getContentAsString();
        long classSessionId=((Number)com.jayway.jsonpath.JsonPath.read(body,"$.classSessionId")).longValue();
        long mediaId=((Number)com.jayway.jsonpath.JsonPath.read(body,"$.media[0].mediaId")).longValue();
        long transcriptId=jdbc.queryForObject("SELECT id FROM classroom_module_job WHERE media_id=? AND module_type='TRANSCRIPTION'",
                Long.class,mediaId);
        long headUpId=jdbc.queryForObject("SELECT id FROM classroom_module_job WHERE media_id=? AND module_type='HEAD_UP'",
                Long.class,mediaId);
        String transcriptKey=jdbc.queryForObject("SELECT client_request_id FROM classroom_module_job WHERE id=?",
                String.class,transcriptId);
        String headUpKey=jdbc.queryForObject("SELECT client_request_id FROM classroom_module_job WHERE id=?",
                String.class,headUpId);

        mvc.perform(post("/api/v1/class-sessions/module-jobs/"+transcriptId+"/result")
                        .contentType(MediaType.APPLICATION_JSON).content(transcriptEnvelope(transcriptKey,classSessionId,mediaId))
                        .with(csrf()).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.media[0].report.status").value("WAITING_INPUT"))
                .andExpect(jsonPath("$.media[0].report.transcript.length()").value(2));

        mvc.perform(post("/api/v1/class-sessions/module-jobs/"+headUpId+"/result")
                        .contentType(MediaType.APPLICATION_JSON).content(headUpEnvelope(headUpKey,classSessionId,mediaId))
                        .with(csrf()).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.media[0].report.status").value("READY"))
                .andExpect(jsonPath("$.media[0].report.dataMode").value("EXTERNAL"))
                .andExpect(jsonPath("$.media[0].report.meanHeadUpRate").value(0.75))
                .andExpect(jsonPath("$.media[0].report.validCoverageRate").value(0.5))
                .andExpect(jsonPath("$.media[0].report.headUpWindows.length()").value(2));
        var snapshot=inputSnapshots.build(mediaId);
        assertEquals(snapshot.sha256(),inputSnapshots.build(mediaId).sha256());
        assertEquals("classroom-analysis-input-v4",snapshot.input().version());
        assertEquals(jdbc.queryForObject("SELECT id FROM classroom_analysis_report WHERE media_id=?",Long.class,mediaId),
                snapshot.input().objectiveReportId());
        assertEquals(mediaId,snapshot.input().mediaId());
        assertEquals(2,snapshot.input().transcript().size());
        assertEquals("seg-1",snapshot.input().transcript().get(0).id());
        assertEquals(2,snapshot.input().headUpWindows().size());
        assertEquals(java.util.List.of(new ClassroomAnalysisRepository.ProcessedRange(0,60_000)),
                snapshot.input().transcriptProcessedRanges());
        assertEquals(java.util.List.of(new ClassroomAnalysisRepository.ProcessedRange(0,60_000)),
                snapshot.input().headUpProcessedRanges());
        assertEquals(0.75,snapshot.input().headUpWindows().get(0).headUpRate());
        assertNull(snapshot.input().headUpWindows().get(1).headUpRate());
        assertEquals(0.75,snapshot.input().statistics().meanHeadUpRate());
        assertEquals(0.5,snapshot.input().statistics().validCoverageRate());
        assertEquals(60_000,snapshot.input().completeness().transcriptProcessed().coveredMs());
        assertEquals(1.0,snapshot.input().completeness().transcriptProcessed().rate());
        assertEquals(60_000,snapshot.input().completeness().transcriptSegments().coveredMs());
        assertEquals(1.0,snapshot.input().completeness().transcriptSegments().rate());
        assertEquals(60_000,snapshot.input().completeness().headUpProcessed().coveredMs());
        assertEquals(1.0,snapshot.input().completeness().headUpProcessed().rate());
        assertEquals(30_000,snapshot.input().completeness().headUpValidWindows().coveredMs());
        assertEquals(0.5,snapshot.input().completeness().headUpValidWindows().rate());
        assertEquals(4,snapshot.input().evidenceIndex().size());
        var segmentRef=new ClassroomAnalysisEvidenceValidator.EvidenceRef(
                snapshot.input().transcriptSource().resultId(),"SEGMENT","seg-1");
        var windowRef=new ClassroomAnalysisEvidenceValidator.EvidenceRef(
                snapshot.input().headUpSource().resultId(),"WINDOW","win-1");
        ClassroomAnalysisEvidenceValidator.validateRefs(snapshot.input(),java.util.List.of(segmentRef,windowRef));
        ClassroomAnalysisEvidenceValidator.validateClaim(snapshot.input(),0,20_000,
                java.util.List.of(segmentRef,windowRef));
        assertThrows(ClassroomAnalysisEvidenceValidator.EvidenceViolation.class,
                ()->ClassroomAnalysisEvidenceValidator.validateRefs(snapshot.input(),java.util.List.of(
                        new ClassroomAnalysisEvidenceValidator.EvidenceRef(
                                snapshot.input().headUpSource().resultId(),"WINDOW","missing"))));
        assertThrows(ClassroomAnalysisEvidenceValidator.EvidenceViolation.class,
                ()->ClassroomAnalysisEvidenceValidator.validateClaim(snapshot.input(),20_000,30_000,
                        java.util.List.of(segmentRef)));
        assertThrows(ClassroomAnalysisEvidenceValidator.EvidenceViolation.class,
                ()->ClassroomAnalysisEvidenceValidator.validateClaim(snapshot.input(),0,20_000,
                        java.util.List.of(new ClassroomAnalysisEvidenceValidator.EvidenceRef(
                                snapshot.input().headUpSource().resultId(),"SEGMENT","seg-1"))));
        assertThrows(ClassroomAnalysisEvidenceValidator.EvidenceViolation.class,
                ()->ClassroomAnalysisEvidenceValidator.validateClaim(snapshot.input(),0,20_000,
                        java.util.List.of(new ClassroomAnalysisEvidenceValidator.EvidenceRef(
                                snapshot.input().transcriptSource().resultId(),"WINDOW","seg-1"))));
        assertThrows(ClassroomAnalysisEvidenceValidator.EvidenceViolation.class,
                ()->ClassroomAnalysisEvidenceValidator.validateClaim(snapshot.input(),0,20_000,
                        java.util.List.of(new ClassroomAnalysisEvidenceValidator.EvidenceRef(
                                snapshot.input().transcriptSource().resultId(),"SEGMENT","missing"))));
        assertThrows(ClassroomAnalysisEvidenceValidator.EvidenceViolation.class,
                ()->ClassroomAnalysisEvidenceValidator.validateClaim(snapshot.input(),-1,20_000,
                        java.util.List.of(segmentRef)));
        assertThrows(ClassroomAnalysisEvidenceValidator.EvidenceViolation.class,
                ()->ClassroomAnalysisEvidenceValidator.validateClaim(snapshot.input(),0,60_001,
                        java.util.List.of(segmentRef)));
        assertThrows(ClassroomAnalysisEvidenceValidator.EvidenceViolation.class,
                ()->ClassroomAnalysisEvidenceValidator.validateClaim(snapshot.input(),0,20_000,
                        java.util.List.of(segmentRef,segmentRef)));
        assertEquals(64,snapshot.input().transcriptSource().envelopeSha256().length());
        assertEquals(64,snapshot.input().headUpSource().envelopeSha256().length());
        var prepared=analysisRuns.prepare(mediaId,"analysis-input-0001");
        assertEquals("PREPARED",prepared.state());
        assertFalse(prepared.replayed());
        assertEquals(snapshot.sha256(),prepared.inputSha256());
        assertEquals(prepared.id(),analysisRuns.prepare(mediaId,"analysis-input-0001").id());
        assertTrue(analysisRuns.prepare(mediaId,"analysis-input-0001").replayed());
        var secondRun=analysisRuns.prepare(mediaId,"analysis-input-0002");
        assertNotEquals(prepared.id(),secondRun.id());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_analysis_run",Integer.class));
        assertEquals(snapshot.canonicalJson(),jdbc.queryForObject(
                "SELECT input_json FROM classroom_analysis_run WHERE id=?",String.class,prepared.id()));
        assertEquals(snapshot.input().transcriptSource().resultId(),((Number)com.jayway.jsonpath.JsonPath.read(
                snapshot.canonicalJson(),"$.evidenceIndex[0].resultId")).longValue());
        String draft="""
                {"reportVersion":"1.0","runId":%d,"inputSha256":"%s",
                 "topics":[{"startMs":0,"endMs":20000,"summary":"讲解递归",
                   "evidenceRefs":[{"resultId":%d,"itemType":"SEGMENT","itemId":"seg-1"}]}],
                 "changes":[],
                 "interpretations":[{"hypothesis":"可能与课堂活动有关","alternatives":["也可能受视角影响"],
                   "suggestion":"结合视频核查",
                   "evidenceRefs":[{"resultId":%d,"itemType":"SEGMENT","itemId":"seg-1"},
                                   {"resultId":%d,"itemType":"WINDOW","itemId":"win-1"}]}],
                 "limitations":["不推断学生理解程度"]}
                """.formatted(prepared.id(),snapshot.sha256(),snapshot.input().transcriptSource().resultId(),
                snapshot.input().transcriptSource().resultId(),snapshot.input().headUpSource().resultId());
        assertEquals(prepared.id(),analysisRuns.validateDraft(prepared.id(),draft).runId());
        var egress=analysisRuns.prepareEgress(prepared.id());
        assertEquals(prepared.id(),egress.runId());
        assertEquals(snapshot.sha256(),egress.inputSha256());
        assertEquals("seg-1",egress.transcript().get(0).id());
        assertEquals(snapshot.input().transcript().size(),
                egress.evidenceIndex().stream().filter(item->"SEGMENT".equals(item.itemType())).count());
        assertThrows(IllegalArgumentException.class,
                ()->analysisRuns.validateDraft(prepared.id(),draft.replace(snapshot.sha256(),"wrong-hash")));
        assertEquals("ANALYSIS_RUN_NOT_PREPARED",assertThrows(ClassroomAnalysisException.class,
                ()->analysisRuns.validateDraft(-1,draft)).code());
        assertNotEquals("analysis-input-0001",jdbc.queryForObject(
                "SELECT request_key_sha256 FROM classroom_analysis_run WHERE id=?",String.class,prepared.id()));
        String transcriptEnvelope=transcriptEnvelope(transcriptKey,classSessionId,mediaId);
        mvc.perform(post("/api/v1/class-sessions/module-jobs/"+transcriptId+"/result")
                        .contentType(MediaType.APPLICATION_JSON).content(transcriptEnvelope)
                        .with(csrf()).session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/class-sessions/module-jobs/"+transcriptId+"/result")
                        .contentType(MediaType.APPLICATION_JSON).content(transcriptEnvelope.replace("第一段","篡改"))
                        .with(csrf()).session(session))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MODULE_CONTRACT_VIOLATION"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_module_result",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_processed_range",Integer.class));
        long transcriptResultId=snapshot.input().transcriptSource().resultId();
        String originalEnvelope=jdbc.queryForObject("SELECT envelope_json FROM classroom_module_result WHERE id=?",
                String.class,transcriptResultId);
        jdbc.update("UPDATE classroom_module_result SET envelope_json='{}' WHERE id=?",transcriptResultId);
        assertEquals("INPUT_NOT_READY",assertThrows(ClassroomAnalysisException.class,
                ()->inputSnapshots.build(mediaId)).code());
        jdbc.update("UPDATE classroom_module_result SET envelope_json=? WHERE id=?",originalEnvelope,transcriptResultId);
        jdbc.update("UPDATE classroom_analysis_run SET input_sha256=? WHERE id=?","0".repeat(64),prepared.id());
        assertEquals("ANALYSIS_INPUT_CHANGED",assertThrows(ClassroomAnalysisException.class,
                ()->analysisRuns.prepare(mediaId,"analysis-input-0001")).code());
        jdbc.update("UPDATE classroom_analysis_run SET input_sha256=? WHERE id=?",snapshot.sha256(),prepared.id());
        jdbc.update("UPDATE classroom_transcript_segment SET text_content='不同的分析输入' WHERE job_id=? AND segment_key='seg-1'",
                transcriptId);
        assertEquals(prepared.id(),analysisRuns.validateDraft(prepared.id(),draft).runId());
        assertEquals(prepared.id(),analysisRuns.prepareEgress(prepared.id()).runId());
        assertEquals("INPUT_NOT_READY",assertThrows(ClassroomAnalysisException.class,
                ()->analysisRuns.prepare(mediaId,"analysis-input-0001")).code());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_analysis_run",Integer.class));
        assertEquals(snapshot.canonicalJson(),jdbc.queryForObject(
                "SELECT input_json FROM classroom_analysis_run WHERE id=?",String.class,prepared.id()));
        jdbc.update("UPDATE classroom_transcript_segment SET text_content='' WHERE job_id=? AND segment_key='seg-1'",
                transcriptId);
        assertEquals("INPUT_NOT_READY",assertThrows(ClassroomAnalysisException.class,
                ()->inputSnapshots.build(mediaId)).code());
        assertEquals(prepared.id(),analysisRuns.validateDraft(prepared.id(),draft).runId());
        jdbc.update("UPDATE classroom_analysis_run SET transcript_result_id=? WHERE id=?",
                snapshot.input().headUpSource().resultId(),prepared.id());
        assertThrows(IllegalStateException.class,()->analysisRuns.validateDraft(prepared.id(),draft));
        assertThrows(IllegalStateException.class,()->analysisRuns.prepareEgress(prepared.id()));
        jdbc.update("UPDATE classroom_analysis_run SET transcript_result_id=? WHERE id=?",
                snapshot.input().transcriptSource().resultId(),prepared.id());
        jdbc.update("UPDATE classroom_analysis_run SET state='INVALID' WHERE id=?",prepared.id());
        assertEquals("ANALYSIS_RUN_NOT_PREPARED",assertThrows(ClassroomAnalysisException.class,
                ()->analysisRuns.validateDraft(prepared.id(),draft)).code());
        assertEquals("ANALYSIS_RUN_NOT_PREPARED",assertThrows(ClassroomAnalysisException.class,
                ()->analysisRuns.prepareEgress(prepared.id())).code());
        jdbc.update("UPDATE classroom_analysis_run SET state='PREPARED' WHERE id=?",prepared.id());
        jdbc.update("UPDATE classroom_analysis_run SET input_json='{}' WHERE id=?",prepared.id());
        assertThrows(IllegalStateException.class,()->analysisRuns.validateDraft(prepared.id(),draft));
        assertThrows(IllegalStateException.class,()->analysisRuns.prepareEgress(prepared.id()));
        jdbc.update("UPDATE classroom_transcript_segment SET text_content='第一段' WHERE job_id=? AND segment_key='seg-1'",
                transcriptId);
        jdbc.update("UPDATE classroom_head_up_window SET quality='LOW_QUALITY' WHERE job_id=? AND window_key='win-1'",
                headUpId);
        assertEquals("INPUT_NOT_READY",assertThrows(ClassroomAnalysisException.class,
                ()->inputSnapshots.build(mediaId)).code());
    }

    @Test void validatedGeneratedReportIsPrivateImmutableAndBoundToPaidResponse() throws Exception {
        var prepared=preparedExternalRun("upload-generated-0001","analysis-generated-0001");
        String draft=generatedDraft(prepared);
        var reservation=paidBudget.reserve("REPORT",prepared.run().id(),"b".repeat(64),
                "qwen3.7-plus",100,2000);
        paidBudget.markSucceeded(reservation.row().id(),"provider-generated-1",40,20,60,hash(draft));
        var saved=generatedReports.persist(prepared.run().id(),reservation.row().id(),"qwen3.7-plus",draft);
        assertEquals(prepared.run().id(),saved.runId());
        assertEquals(prepared.snapshot().sha256(),saved.inputSha256());
        assertNotEquals(draft,saved.normalizedJson());
        assertEquals("讲解递归",com.jayway.jsonpath.JsonPath.read(saved.normalizedJson(),"$.topics[0].summary"));
        assertEquals("COMPLETED",jdbc.queryForObject(
                "SELECT state FROM classroom_analysis_run WHERE id=?",String.class,prepared.run().id()));
        assertEquals(saved.id(),generatedReports.persist(prepared.run().id(),reservation.row().id(),
                "qwen3.7-plus",draft).id());
        assertEquals(saved.id(),generatedReports.report(prepared.run().id()).id());
        assertEquals(prepared.run().id(),analysisRuns.prepareEgress(prepared.run().id()).runId());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_generated_report WHERE run_id=?",
                Integer.class,prepared.run().id()));
        assertThrows(IllegalStateException.class,()->generatedReports.persist(prepared.run().id(),
                reservation.row().id(),"qwen3.8-flash",draft));
        jdbc.update("UPDATE classroom_analysis_run SET state='PREPARED' WHERE id=?",prepared.run().id());
        assertThrows(IllegalStateException.class,()->generatedReports.report(prepared.run().id()));
        jdbc.update("UPDATE classroom_analysis_run SET state='COMPLETED' WHERE id=?",prepared.run().id());
        jdbc.update("UPDATE classroom_generated_report SET normalized_json='{}' WHERE id=?",saved.id());
        assertThrows(IllegalStateException.class,()->generatedReports.report(prepared.run().id()));
    }

    /** Publishes one report version for a fresh upload and returns its media id. */
    private long mediaWithGeneratedReport(String uploadKey,String requestKey,String providerRef) throws Exception {
        var prepared=preparedExternalRun(uploadKey,requestKey);
        String draft=generatedDraft(prepared);
        var reservation=paidBudget.reserve("REPORT",prepared.run().id(),"e".repeat(64),
                "qwen3.7-plus",100,2000);
        paidBudget.markSucceeded(reservation.row().id(),providerRef,40,20,60,hash(draft));
        generatedReports.persist(prepared.run().id(),reservation.row().id(),"qwen3.7-plus",draft);
        return prepared.snapshot().input().mediaId();
    }

    @Test void s2reviewAnnotationsAreRecordedAndConfirmationFreezesTheTeacherVersion() throws Exception {
        long mediaId=mediaWithGeneratedReport("upload-review-0001","analysis-review-0001","provider-review-1");
        var supervisor=login("supervisor-a-test");
        String reviewPath="/api/v1/class-sessions/media/"+mediaId+"/generated-report/review";

        mvc.perform(get(reviewPath).session(supervisor)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/class-sessions/media/"+mediaId+"/confirmed-report").session(supervisor))
                .andExpect(status().isNoContent());

        mvc.perform(put(reviewPath).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"annotations\":[{\"targetKind\":\"TOPIC\",\"targetIndex\":0,"
                                +"\"verdict\":\"ISSUE\",\"note\":\"环节划分过粗，建议按提问切分\"}]}")
                        .with(csrf()).session(supervisor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmed").value(false))
                .andExpect(jsonPath("$.annotations.length()").value(1))
                .andExpect(jsonPath("$.annotations[0].note").value("环节划分过粗，建议按提问切分"));

        // 复核草稿是督导的工作底稿：主任也读不到（夹具里 teacher-a-test 另持有督导范围，故选主任）
        mvc.perform(get(reviewPath).session(login("director-a-test"))).andExpect(status().isForbidden());

        mvc.perform(post(reviewPath+"/confirm").with(csrf()).session(supervisor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmed").value(true))
                .andExpect(jsonPath("$.confirmedAt").isNotEmpty())
                .andExpect(jsonPath("$.revision").value(2));

        // S2-04：教师读到的就是冻结版本
        mvc.perform(get("/api/v1/class-sessions/media/"+mediaId+"/confirmed-report")
                        .session(login("teacher-a-test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.annotations[0].note").value("环节划分过粗，建议按提问切分"))
                .andExpect(jsonPath("$.report.topics[0].summary").value("讲解递归"));

        // 已确认的复核不可再改；重复确认返回同一版本
        mvc.perform(put(reviewPath).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"annotations\":[{\"targetKind\":\"TOPIC\",\"targetIndex\":0,"
                                +"\"verdict\":\"ACKNOWLEDGED\",\"note\":\"改主意了\"}]}")
                        .with(csrf()).session(supervisor))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_ALREADY_CONFIRMED"));
        mvc.perform(post(reviewPath+"/confirm").with(csrf()).session(supervisor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));

        assertEquals("讲解递归",com.jayway.jsonpath.JsonPath.read(jdbc.queryForObject(
                "SELECT snapshot_json FROM classroom_report_review WHERE media_id=?",String.class,mediaId),
                "$.report.topics[0].summary"));

        // S2-05：确认即通知开课教师本人；重复确认不产生第二条
        long notificationId=((Number)com.jayway.jsonpath.JsonPath.read(
                mvc.perform(get("/api/v1/me/notifications").session(login("teacher-a-test")))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.length()").value(1))
                        .andExpect(jsonPath("$[0].kind").value("REPORT_PUBLISHED"))
                        .andExpect(jsonPath("$[0].state").value("UNREAD"))
                        .andExpect(jsonPath("$[0].mediaId").value(mediaId))
                        .andReturn().getResponse().getContentAsString(),
                "$[0].notificationId")).longValue();
        assertEquals(1,jdbc.queryForObject(
                "SELECT COUNT(*) FROM classroom_report_notification WHERE media_id=?",Integer.class,mediaId));
        mvc.perform(get("/api/v1/me/notifications").session(login("teacher-b-test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        mvc.perform(post("/api/v1/me/notifications/"+notificationId+"/read").with(csrf())
                        .session(login("teacher-a-test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("READ"))
                .andExpect(jsonPath("$.readAt").isNotEmpty());
        // 别人的通知按不存在处理
        mvc.perform(post("/api/v1/me/notifications/"+notificationId+"/read").with(csrf())
                        .session(login("teacher-b-test")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/me/notifications")).andExpect(status().isUnauthorized());
    }

    @Test void s2reviewRejectsUnannotatedUnattributedAndForeignRequests() throws Exception {
        long mediaId=mediaWithGeneratedReport("upload-review-0002","analysis-review-0002","provider-review-2");
        var supervisor=login("supervisor-a-test");
        String reviewPath="/api/v1/class-sessions/media/"+mediaId+"/generated-report/review";

        mvc.perform(post(reviewPath+"/confirm").with(csrf()).session(supervisor))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));

        mvc.perform(put(reviewPath).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"annotations\":[{\"targetKind\":\"TOPIC\",\"targetIndex\":0,"
                                +"\"verdict\":\"ISSUE\",\"note\":\"   \"}]}")
                        .with(csrf()).session(supervisor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REVIEW_NOTE_REQUIRED"));

        mvc.perform(put(reviewPath).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"annotations\":[{\"targetKind\":\"TOPIC\",\"targetIndex\":0,"
                                +"\"verdict\":\"MAYBE\",\"note\":\"结论不合法\"}]}")
                        .with(csrf()).session(supervisor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REVIEW_TARGET_INVALID"));

        mvc.perform(put(reviewPath).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"annotations\":["
                                +"{\"targetKind\":\"TOPIC\",\"targetIndex\":0,\"verdict\":\"ISSUE\",\"note\":\"甲\"},"
                                +"{\"targetKind\":\"TOPIC\",\"targetIndex\":0,\"verdict\":\"ISSUE\",\"note\":\"乙\"}]}")
                        .with(csrf()).session(supervisor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REVIEW_TARGET_DUPLICATE"));

        mvc.perform(put(reviewPath).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"annotations\":[]}").with(csrf()).session(login("director-a-test")))
                .andExpect(status().isForbidden());

        mvc.perform(post(reviewPath+"/confirm").with(csrf())).andExpect(status().isUnauthorized());
    }

    @Test void generatedReportReadIncludesGlobalAdminAndCourseRoles() throws Exception {
        var prepared=preparedExternalRun("upload-report-read-0001","analysis-report-read-0001");
        jdbc.update("UPDATE user_role_scope SET offering_id=104 WHERE id=5");
        long mediaId=prepared.snapshot().input().mediaId();
        String path="/api/v1/class-sessions/media/"+mediaId+"/generated-report";
        String progressPath=path+"/progress";
        mvc.perform(get(path).session(login("teacher-a-test")))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control","private, no-store"));
        mvc.perform(get(progressPath).session(login("teacher-a-test")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(jsonPath("$.state").value("PAUSED"))
                .andExpect(jsonPath("$.errorCode").doesNotExist());
        String draft=generatedDraft(prepared);
        var reservation=paidBudget.reserve("REPORT",prepared.run().id(),"d".repeat(64),
                "qwen3.7-plus",100,2000);
        paidBudget.markSucceeded(reservation.row().id(),"provider-read-1",40,20,60,hash(draft));
        generatedReports.persist(prepared.run().id(),reservation.row().id(),"qwen3.7-plus",draft);
        long dispatchId=jdbc.queryForObject("SELECT id FROM classroom_report_dispatch WHERE media_id=?",
                Long.class,mediaId);
        assertTrue(reportDispatch.claim(dispatchId,"saved-report-test",LocalDateTime.now(),
                LocalDateTime.now().plusMinutes(3)));
        assertTrue(reportDispatch.completeFromSavedReport(dispatchId,"saved-report-test",prepared.run().id()));
        assertEquals("COMPLETED",jdbc.queryForObject(
                "SELECT state FROM classroom_report_dispatch WHERE id=?",String.class,dispatchId));
        mvc.perform(get(progressPath).session(login("teacher-a-test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("COMPLETED"));
        for(String name:List.of("teacher-a-test","director-a-test","admin-test"))
            mvc.perform(get(path).session(login(name)))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control","private, no-store"))
                    .andExpect(jsonPath("$.topics[0].summary").value("讲解递归"))
                    .andExpect(jsonPath("$.limitations[0]").value("不能推断理解程度"))
                    .andExpect(jsonPath("$.inputSha256").doesNotExist())
                    .andExpect(jsonPath("$.runId").doesNotExist())
                    .andExpect(jsonPath("$.modelId").doesNotExist());
        for(String name:List.of("teacher-b-test","supervisor-a-test"))
            mvc.perform(get(path).session(login(name))).andExpect(status().isNotFound());
        for(String name:List.of("teacher-b-test","supervisor-a-test"))
            mvc.perform(get(progressPath).session(login(name))).andExpect(status().isNotFound());
        mvc.perform(get(progressPath).session(login("admin-test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("COMPLETED"));
        jdbc.update("UPDATE user_role_scope SET offering_id=101 WHERE id=5");
        mvc.perform(get(path).session(login("supervisor-a-test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.topics[0].summary").value("讲解递归"));
        mvc.perform(get(progressPath).session(login("supervisor-a-test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("COMPLETED"));
        mvc.perform(get("/api/v1/class-sessions/media/999999/generated-report")
                .session(login("director-a-test"))).andExpect(status().isNotFound());
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(progressPath)).andExpect(status().isUnauthorized());
    }

    @Test void delayedOlderRunCannotReplaceTheNewerGeneratedReport() throws Exception {
        var first=preparedExternalRun("upload-report-order-0001","analysis-report-order-0001");
        long mediaId=first.snapshot().input().mediaId();
        var second=new PreparedExternalRun(analysisRuns.prepare(mediaId,"analysis-report-order-0002"),
                first.snapshot());
        String newerDraft=generatedDraft(second).replace("讲解递归","新一轮课程报告");
        var newerReservation=paidBudget.reserve("REPORT",second.run().id(),"e".repeat(64),
                "qwen3.7-plus",100,2000);
        paidBudget.markSucceeded(newerReservation.row().id(),"provider-report-order-new",40,20,60,
                hash(newerDraft));
        var newerSaved=generatedReports.persist(second.run().id(),newerReservation.row().id(),
                "qwen3.7-plus",newerDraft);

        String olderDraft=generatedDraft(first);
        var olderReservation=paidBudget.reserve("REPORT",first.run().id(),"f".repeat(64),
                "qwen3.7-plus",100,2000);
        paidBudget.markSucceeded(olderReservation.row().id(),"provider-report-order-old",40,20,60,
                hash(olderDraft));
        var olderSaved=generatedReports.persist(first.run().id(),olderReservation.row().id(),
                "qwen3.7-plus",olderDraft);

        assertEquals(second.run().id(),generatedReports.latestForMedia(mediaId).runId());
        mvc.perform(get("/api/v1/class-sessions/media/"+mediaId+"/generated-report")
                        .session(login("teacher-a-test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topics[0].summary").value("新一轮课程报告"));
        String versions="/api/v1/class-sessions/media/"+mediaId+"/generated-report/versions";
        mvc.perform(get(versions).session(login("teacher-a-test")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].reportId").value(newerSaved.id()))
                .andExpect(jsonPath("$.items[0].current").value(true))
                .andExpect(jsonPath("$.items[1].reportId").value(olderSaved.id()))
                .andExpect(jsonPath("$.items[1].current").value(false))
                .andExpect(jsonPath("$.items[0].runId").doesNotExist())
                .andExpect(jsonPath("$.items[0].topics").doesNotExist())
                .andExpect(jsonPath("$.nextPage").doesNotExist());
        mvc.perform(get(versions).param("page","1").session(login("teacher-a-test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get(versions).param("page","-1").session(login("teacher-a-test")))
                .andExpect(status().isBadRequest());
        mvc.perform(get(versions).session(login("admin-test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(get(versions)).andExpect(status().isUnauthorized());
    }

    @Test void olderResultDoesNotStopPollingWhileNewerRunIsProcessing() throws Exception {
        var first=preparedExternalRun("upload-report-pending-0001","analysis-report-pending-0001");
        long mediaId=first.snapshot().input().mediaId();
        var second=new PreparedExternalRun(analysisRuns.prepare(mediaId,"analysis-report-pending-0002"),
                first.snapshot());
        long dispatchId=jdbc.queryForObject("SELECT id FROM classroom_report_dispatch WHERE media_id=?",
                Long.class,mediaId);
        String owner="newer-report-test";
        assertTrue(reportDispatch.claim(dispatchId,owner,LocalDateTime.now(),LocalDateTime.now().plusMinutes(3)));
        reportDispatch.attachRun(dispatchId,owner,second.run().id());

        String olderDraft=generatedDraft(first);
        var olderReservation=paidBudget.reserve("REPORT",first.run().id(),"a".repeat(64),
                "qwen3.7-plus",100,2000);
        paidBudget.markSucceeded(olderReservation.row().id(),"provider-report-pending-old",40,20,60,
                hash(olderDraft));
        generatedReports.persist(first.run().id(),olderReservation.row().id(),"qwen3.7-plus",olderDraft);
        String path="/api/v1/class-sessions/media/"+mediaId+"/generated-report";
        mvc.perform(get(path).session(login("teacher-a-test"))).andExpect(status().isNoContent());
        mvc.perform(get(path+"/progress").session(login("teacher-a-test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PROCESSING"));

        String newerDraft=generatedDraft(second).replace("讲解递归","新一轮课程报告");
        var newerReservation=paidBudget.reserve("REPORT",second.run().id(),"b".repeat(64),
                "qwen3.7-plus",100,2000);
        paidBudget.markSucceeded(newerReservation.row().id(),"provider-report-pending-new",40,20,60,
                hash(newerDraft));
        generatedReports.persist(second.run().id(),newerReservation.row().id(),"qwen3.7-plus",newerDraft);
        mvc.perform(get(path).session(login("teacher-a-test"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.topics[0].summary").value("新一轮课程报告"));
    }

    @Test void latePriorReportCompletesQueuedSupervisorRetryWithoutAnotherSubmission() throws Exception {
        var prepared=preparedExternalRun("upload-report-late-0001","analysis-report-late-0001");
        long mediaId=prepared.snapshot().input().mediaId();
        long dispatchId=jdbc.queryForObject("SELECT id FROM classroom_report_dispatch WHERE media_id=?",
                Long.class,mediaId);
        jdbc.update("UPDATE classroom_report_dispatch SET state='NEEDS_REVIEW',error_code='REPORT_SUBMISSION_UNKNOWN' WHERE id=?",
                dispatchId);
        String path="/api/v1/class-sessions/media/"+mediaId+"/generated-report";
        mvc.perform(post(path+"/retry").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmAdditionalCharge\":true}")
                        .header("Idempotency-Key","late-prior-report-retry-0001")
                        .with(csrf()).session(login("supervisor-a-test")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("PAUSED"));

        String draft=generatedDraft(prepared);
        var reservation=paidBudget.reserve("REPORT",prepared.run().id(),"c".repeat(64),
                "qwen3.7-plus",100,2000);
        paidBudget.markSucceeded(reservation.row().id(),"provider-report-late",40,20,60,hash(draft));
        generatedReports.persist(prepared.run().id(),reservation.row().id(),"qwen3.7-plus",draft);
        mvc.perform(get(path).session(login("teacher-a-test"))).andExpect(status().isNoContent());

        reportProperties.setBaseUrl("http://127.0.0.1:1/compatible-mode/v1");
        reportProperties.setApiKey("local-late-report-key");
        reportProperties.setAllowLoopbackHttp(true);
        reportProperties.setEnabled(true);
        reportWorker.runOnce();

        assertEquals("COMPLETED",jdbc.queryForObject(
                "SELECT state FROM classroom_report_dispatch WHERE id=?",String.class,dispatchId));
        assertEquals(prepared.run().id(),jdbc.queryForObject(
                "SELECT run_id FROM classroom_report_dispatch WHERE id=?",Long.class,dispatchId));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_paid_test_reservation WHERE category='REPORT'",
                Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_analysis_run WHERE media_id=?",
                Integer.class,mediaId));
        mvc.perform(get(path).session(login("teacher-a-test"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.topics[0].summary").value("讲解递归"));
    }

    @Test void generatedReportProgressShowsOnlyPublicDispatchState() throws Exception {
        var prepared=preparedExternalRun("upload-report-progress-0001","analysis-report-progress-0001");
        long mediaId=prepared.snapshot().input().mediaId();
        String path="/api/v1/class-sessions/media/"+mediaId+"/generated-report/progress";
        reportProperties.setApiKey("local-progress-only-key");
        reportProperties.setEnabled(true);
        mvc.perform(get(path).session(login("teacher-a-test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("QUEUED"));
        long dispatchId=jdbc.queryForObject("SELECT id FROM classroom_report_dispatch WHERE media_id=?",
                Long.class,mediaId);
        String owner="progress-test";
        assertTrue(reportDispatch.claim(dispatchId,owner,LocalDateTime.now(),LocalDateTime.now().plusMinutes(3)));
        reportProperties.setEnabled(false);
        mvc.perform(get(path).session(login("teacher-a-test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PROCESSING"));
        reportProperties.setEnabled(true);
        mvc.perform(get(path).session(login("teacher-a-test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PROCESSING"));
        reportDispatch.markNeedsReview(dispatchId,owner,"INTERNAL_ONLY_CODE");
        mvc.perform(get(path).session(login("teacher-a-test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.errorCode").doesNotExist())
                .andExpect(jsonPath("$.runId").doesNotExist());
    }

    @Test void automaticReportDispatchUsesOnlyAllowlistedEvidenceAndPersistsOnce() throws Exception {
        var prepared=preparedExternalRun("upload-auto-report-0001","manual-preparation-0001");
        long mediaId=prepared.snapshot().input().mediaId();
        assertEquals("QUEUED",jdbc.queryForObject(
                "SELECT state FROM classroom_report_dispatch WHERE media_id=?",String.class,mediaId));
        var calls=new AtomicInteger();
        reportServer=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        reportServer.createContext("/compatible-mode/v1/chat/completions",exchange->{
            try {
                calls.incrementAndGet();
                var request=mapper.readTree(exchange.getRequestBody().readAllBytes());
                var packet=mapper.readTree(request.path("messages").get(1).path("content").asText());
                assertEquals("classroom-report-egress-v1",packet.path("version").asText());
                assertFalse(packet.has("mediaSha256"));
                assertFalse(packet.has("inputJson"));
                var segment=packet.path("evidenceIndex").get(0);
                String draft="""
                        {"reportVersion":"1.0","runId":%d,"inputSha256":"%s",
                         "topics":[{"startMs":0,"endMs":20000,"summary":"讲解递归",
                           "evidenceRefs":[{"resultId":%d,"itemType":"SEGMENT","itemId":"seg-1"}]}],
                         "changes":[],"interpretations":[],"limitations":["不能推断理解程度"]}
                        """.formatted(packet.path("runId").asLong(),packet.path("inputSha256").asText(),
                        segment.path("resultId").asLong());
                byte[] bytes=mapper.writeValueAsBytes(Map.of("id","local-report-1","model","qwen3.7-plus",
                        "choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",draft))),
                        "usage",Map.of("prompt_tokens",20,"completion_tokens",30,"total_tokens",50)));
                exchange.sendResponseHeaders(200,bytes.length);
                try(var output=exchange.getResponseBody()) { output.write(bytes); }
            } catch(Exception failure) {
                byte[] bytes="local fixture failed".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500,bytes.length);
                try(var output=exchange.getResponseBody()) { output.write(bytes); }
            }
        });
        reportServer.start();
        reportProperties.setBaseUrl("http://127.0.0.1:"+reportServer.getAddress().getPort()
                +"/compatible-mode/v1");
        reportProperties.setApiKey("local-test-only-key");
        reportProperties.setAllowLoopbackHttp(true);
        reportProperties.setEnabled(true);

        reportWorker.runOnce();
        reportWorker.runOnce();

        assertEquals(1,calls.get());
        assertEquals("COMPLETED",jdbc.queryForObject(
                "SELECT state FROM classroom_report_dispatch WHERE media_id=?",String.class,mediaId));
        long runId=jdbc.queryForObject("SELECT run_id FROM classroom_report_dispatch WHERE media_id=?",
                Long.class,mediaId);
        assertEquals("COMPLETED",jdbc.queryForObject(
                "SELECT state FROM classroom_analysis_run WHERE id=?",String.class,runId));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_generated_report WHERE run_id=?",
                Integer.class,runId));
        assertEquals("SUCCEEDED",jdbc.queryForObject(
                "SELECT state FROM classroom_paid_test_reservation WHERE category='REPORT' AND subject_id=?",
                String.class,runId));
        assertNotNull(generatedReports.report(runId));
    }

    @Test void paidInvalidEvidenceNeedsSupervisorDecisionBeforeAnotherRun() throws Exception {
        var prepared=preparedExternalRun("upload-invalid-topic-0001","analysis-invalid-topic-0001");
        long mediaId=prepared.snapshot().input().mediaId();
        var calls=new AtomicInteger();
        reportServer=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        reportServer.createContext("/compatible-mode/v1/chat/completions",exchange->{
            int callNumber=calls.incrementAndGet();
            try {
                var request=mapper.readTree(exchange.getRequestBody().readAllBytes());
                var packet=mapper.readTree(request.path("messages").get(1).path("content").asText());
                var segment=packet.path("transcript").get(0);
                String draft="""
                        {"reportVersion":"1.0","runId":%d,"inputSha256":"%s",
                         "topics":[{"startMs":%d,"endMs":%d,"summary":"讲解课程内容",
                           "evidenceRefs":[{"resultId":%d,"itemType":"SEGMENT","itemId":"%s"}]}],
                         "changes":[],"interpretations":[],"limitations":["需结合课堂核查"]}
                        """.formatted(packet.path("runId").asLong(),packet.path("inputSha256").asText(),
                        segment.path("startMs").asLong(),segment.path("endMs").asLong(),
                        packet.path("transcriptResultId").asLong(),
                        callNumber==1?"missing-segment":segment.path("id").asText());
                byte[] bytes=mapper.writeValueAsBytes(Map.of("id","local-report-"+callNumber,"model","qwen3.7-plus",
                        "choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",draft))),
                        "usage",Map.of("prompt_tokens",20,"completion_tokens",30,"total_tokens",50)));
                exchange.sendResponseHeaders(200,bytes.length);
                try(var output=exchange.getResponseBody()) { output.write(bytes); }
            } catch(Exception failure) {
                byte[] bytes="local fixture failed".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500,bytes.length);
                try(var output=exchange.getResponseBody()) { output.write(bytes); }
            }
        });
        reportServer.start();
        reportProperties.setBaseUrl("http://127.0.0.1:"+reportServer.getAddress().getPort()
                +"/compatible-mode/v1");
        reportProperties.setApiKey("local-invalid-topic-key");
        reportProperties.setAllowLoopbackHttp(true);
        reportProperties.setEnabled(true);

        reportWorker.runOnce();
        reportWorker.runOnce();

        assertEquals(1,calls.get());
        long runId=jdbc.queryForObject("SELECT run_id FROM classroom_report_dispatch WHERE media_id=?",
                Long.class,mediaId);
        assertEquals("NEEDS_REVIEW",jdbc.queryForObject(
                "SELECT state FROM classroom_report_dispatch WHERE media_id=?",String.class,mediaId));
        assertEquals("REPORT_DRAFT_TOPIC_EVIDENCE_INVALID",jdbc.queryForObject(
                "SELECT error_code FROM classroom_report_dispatch WHERE media_id=?",String.class,mediaId));
        assertEquals("SUCCEEDED",jdbc.queryForObject(
                "SELECT state FROM classroom_paid_test_reservation WHERE category='REPORT' AND subject_id=?",
                String.class,runId));
        assertEquals("PREPARED",jdbc.queryForObject(
                "SELECT state FROM classroom_analysis_run WHERE id=?",String.class,runId));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_generated_report WHERE run_id=?",
                Integer.class,runId));
        mvc.perform(get("/api/v1/class-sessions/media/"+mediaId+"/generated-report/progress")
                        .session(login("supervisor-a-test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.errorCode").doesNotExist());

        String path="/api/v1/class-sessions/media/"+mediaId+"/generated-report/retry";
        var supervisor=login("supervisor-a-test");
        String key="supervisor-manual-report-0001";
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmAdditionalCharge\":true}")
                        .header("Idempotency-Key",key).session(supervisor))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmAdditionalCharge\":false}")
                        .header("Idempotency-Key",key).with(csrf()).session(supervisor))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmAdditionalCharge\":true}")
                        .header("Idempotency-Key",key).with(csrf()).session(login("admin-test")))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE user_role_scope SET offering_id=104 WHERE id=5");
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmAdditionalCharge\":true}")
                        .header("Idempotency-Key",key).with(csrf()).session(supervisor))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE user_role_scope SET offering_id=101 WHERE id=5");
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmAdditionalCharge\":true}")
                        .header("Idempotency-Key",key).with(csrf()).session(supervisor))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("QUEUED"))
                .andExpect(jsonPath("$.replayed").value(false));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmAdditionalCharge\":true}")
                        .header("Idempotency-Key",key).with(csrf()).session(supervisor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("QUEUED"))
                .andExpect(jsonPath("$.replayed").value(true));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmAdditionalCharge\":true}")
                        .header("Idempotency-Key","supervisor-manual-report-0002")
                        .with(csrf()).session(supervisor))
                .andExpect(status().isConflict());
        assertEquals(1,calls.get());
        assertEquals(1,jdbc.queryForObject("SELECT manual_generation FROM classroom_report_dispatch WHERE media_id=?",
                Integer.class,mediaId));
        assertEquals(runId,jdbc.queryForObject(
                "SELECT previous_run_id FROM classroom_report_retry_request",Long.class));
        assertEquals("REPORT_DRAFT_TOPIC_EVIDENCE_INVALID",jdbc.queryForObject(
                "SELECT previous_error_code FROM classroom_report_retry_request",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_report_retry_request",Integer.class));

        reportWorker.runOnce();
        assertEquals(2,calls.get());
        long newRunId=jdbc.queryForObject("SELECT run_id FROM classroom_report_dispatch WHERE media_id=?",
                Long.class,mediaId);
        assertNotEquals(runId,newRunId);
        assertEquals("COMPLETED",jdbc.queryForObject(
                "SELECT state FROM classroom_report_dispatch WHERE media_id=?",String.class,mediaId));
        assertEquals("PREPARED",jdbc.queryForObject(
                "SELECT state FROM classroom_analysis_run WHERE id=?",String.class,runId));
        assertEquals("COMPLETED",jdbc.queryForObject(
                "SELECT state FROM classroom_analysis_run WHERE id=?",String.class,newRunId));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_paid_test_reservation WHERE category='REPORT'",
                Integer.class));
        assertNotNull(generatedReports.report(newRunId));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmAdditionalCharge\":true}")
                        .header("Idempotency-Key",key).with(csrf()).session(supervisor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.replayed").value(true));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmAdditionalCharge\":true}")
                        .header("Idempotency-Key","supervisor-manual-report-0003")
                        .with(csrf()).session(supervisor))
                .andExpect(status().isConflict());
    }

    @Test void draftValidationStagesExposeOnlyFixedInternalCodes() throws Exception {
        var prepared=preparedExternalRun("upload-draft-stages-0001","analysis-draft-stages-0001");
        String valid=generatedDraft(prepared);
        assertEquals(prepared.run().id(),analysisRuns.validateDraft(prepared.run().id(),valid).runId());
        var envelope=assertThrows(ClassroomAnalysisOutputValidator.OutputViolation.class,
                ()->analysisRuns.validateDraft(prepared.run().id(),
                        valid.replace("\"reportVersion\":\"1.0\"","\"reportVersion\":\"9.9\"")));
        assertEquals("REPORT_DRAFT_ENVELOPE_INVALID",envelope.code());
        var topic=assertThrows(ClassroomAnalysisOutputValidator.OutputViolation.class,
                ()->analysisRuns.validateDraft(prepared.run().id(),
                        valid.replace("讲解递归","讲解课程 123")));
        assertEquals("REPORT_DRAFT_TOPIC_INVALID",topic.code());
        var limitation=assertThrows(ClassroomAnalysisOutputValidator.OutputViolation.class,
                ()->analysisRuns.validateDraft(prepared.run().id(),
                        valid.replace("不能推断理解程度","不能推断 50% 的理解程度")));
        assertEquals("REPORT_DRAFT_LIMITATION_INVALID",limitation.code());
        for(var violation:List.of(envelope,topic,limitation)) {
            assertFalse(violation.getMessage().contains("讲解"));
            assertFalse(violation.getMessage().contains("理解"));
        }
    }

    /** Rehearses the committed audit and teardown path without contacting a paid model. */
    @Test
    @EnabledIfEnvironmentVariable(named="CLASSROOM_REPORT_AUDIT_REHEARSAL",matches="true")
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void retainedSyntheticReportAuditRehearsal() throws Exception {
        if(!Boolean.getBoolean("mysqlMigration"))
            throw new IllegalStateException("Audit rehearsal requires the isolated MySQL migration runner");
        retainCommittedAudit=true;
        var prepared=preparedExternalRun("upload-audit-rehearsal-0001","analysis-audit-rehearsal-0001");
        long mediaId=prepared.snapshot().input().mediaId();
        var calls=new AtomicInteger();
        reportServer=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        reportServer.createContext("/compatible-mode/v1/chat/completions",exchange->{
            calls.incrementAndGet();
            try {
                var request=mapper.readTree(exchange.getRequestBody().readAllBytes());
                var packet=mapper.readTree(request.path("messages").get(1).path("content").asText());
                var segment=packet.path("evidenceIndex").get(0);
                String draft="""
                        {"reportVersion":"1.0","runId":%d,"inputSha256":"%s",
                         "topics":[{"startMs":0,"endMs":20000,"summary":"讲解递归",
                           "evidenceRefs":[{"resultId":%d,"itemType":"SEGMENT","itemId":"seg-1"}]}],
                         "changes":[],"interpretations":[],"limitations":["不能推断理解程度"]}
                        """.formatted(packet.path("runId").asLong(),packet.path("inputSha256").asText(),
                        segment.path("resultId").asLong());
                byte[] bytes=mapper.writeValueAsBytes(Map.of("id","local-audit-rehearsal","model","qwen3.7-plus",
                        "choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",draft))),
                        "usage",Map.of("prompt_tokens",20,"completion_tokens",30,"total_tokens",50)));
                exchange.sendResponseHeaders(200,bytes.length);
                try(var output=exchange.getResponseBody()) { output.write(bytes); }
            } catch(Exception failure) {
                byte[] bytes="local fixture failed".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500,bytes.length);
                try(var output=exchange.getResponseBody()) { output.write(bytes); }
            }
        });
        reportServer.start();
        reportProperties.setBaseUrl("http://127.0.0.1:"+reportServer.getAddress().getPort()
                +"/compatible-mode/v1");
        reportProperties.setApiKey("local-audit-rehearsal-key");
        reportProperties.setAllowLoopbackHttp(true);
        reportProperties.setEnabled(true);

        reportWorker.runOnce();

        long runId=jdbc.queryForObject("SELECT run_id FROM classroom_report_dispatch WHERE media_id=?",
                Long.class,mediaId);
        assertEquals(1,calls.get());
        assertEquals("COMPLETED",jdbc.queryForObject(
                "SELECT state FROM classroom_report_dispatch WHERE media_id=?",String.class,mediaId));
        assertEquals("SUCCEEDED",jdbc.queryForObject(
                "SELECT state FROM classroom_paid_test_reservation WHERE category='REPORT' AND subject_id=?",
                String.class,runId));
        assertNotNull(generatedReports.report(runId));
    }

    @Test void preSubmissionFailureIsDeferredButBudgetWaitRemainsRecoverable() throws Exception {
        var prepared=preparedExternalRun("upload-auto-retry-0001","manual-auto-retry-0001");
        long mediaId=prepared.snapshot().input().mediaId();
        long dispatchId=jdbc.queryForObject("SELECT id FROM classroom_report_dispatch WHERE media_id=?",
                Long.class,mediaId);
        LocalDateTime now=LocalDateTime.now();
        assertTrue(reportDispatch.claim(dispatchId,"retry-owner",now,now.plusSeconds(60)));
        reportDispatch.attachRun(dispatchId,"retry-owner",prepared.run().id());
        assertEquals("QUEUED",reportDispatch.deferPreSubmission(dispatchId,"retry-owner",0,
                "INPUT_NOT_READY",now.plusSeconds(30),3));
        assertFalse(reportDispatch.claim(dispatchId,"early-owner",now.plusSeconds(10),now.plusSeconds(70)));
        assertTrue(reportDispatch.claim(dispatchId,"retry-owner",now.plusSeconds(31),now.plusSeconds(91)));
        reportDispatch.waitForBudget(dispatchId,"retry-owner",now.plusMinutes(5));
        assertFalse(reportDispatch.claim(dispatchId,"early-owner",now.plusMinutes(1),now.plusMinutes(2)));
        assertTrue(reportDispatch.claim(dispatchId,"retry-owner",now.plusMinutes(6),now.plusMinutes(7)));
        assertEquals(1,reportDispatch.currentClaim(dispatchId,"retry-owner").preSubmitFailures());
        assertEquals("QUEUED",reportDispatch.deferPreSubmission(dispatchId,"retry-owner",1,
                "INPUT_NOT_READY",now.plusMinutes(7),3));
        assertTrue(reportDispatch.claim(dispatchId,"retry-owner",now.plusMinutes(8),now.plusMinutes(9)));
        assertEquals("NEEDS_REVIEW",reportDispatch.deferPreSubmission(dispatchId,"retry-owner",2,
                "INPUT_NOT_READY",now.plusMinutes(9),3));
        assertFalse(reportDispatch.claim(dispatchId,"retry-owner",now.plusMinutes(10),now.plusMinutes(11)));
    }

    @Test void completedReportReconcilesAfterLeaseOwnerMarkedItForReview() throws Exception {
        var prepared=preparedExternalRun("upload-lease-race-0001","manual-lease-race-0001");
        long mediaId=prepared.snapshot().input().mediaId();
        long dispatchId=jdbc.queryForObject("SELECT id FROM classroom_report_dispatch WHERE media_id=?",
                Long.class,mediaId);
        long runId=prepared.run().id();
        LocalDateTime now=LocalDateTime.now();
        assertTrue(reportDispatch.claim(dispatchId,"slow-owner",now,now.plusSeconds(1)));
        reportDispatch.attachRun(dispatchId,"slow-owner",runId);
        assertTrue(reportDispatch.claim(dispatchId,"new-owner",now.plusSeconds(2),now.plusMinutes(2)));
        reportDispatch.markNeedsReview(dispatchId,"new-owner","REPORT_SUBMISSION_ALREADY_RECORDED");
        assertEquals("NEEDS_REVIEW",jdbc.queryForObject(
                "SELECT state FROM classroom_report_dispatch WHERE id=?",String.class,dispatchId));

        String draft=generatedDraft(prepared);
        var reservation=paidBudget.reserve("REPORT",runId,"e".repeat(64),"qwen3.7-plus",100,2000);
        paidBudget.markSucceeded(reservation.row().id(),"provider-lease-race",20,30,50,hash(draft));
        generatedReports.persist(runId,reservation.row().id(),"qwen3.7-plus",draft);
        // The old owner crashed after persistence. A later scan can still reconcile completion.
        reportProperties.setBaseUrl("http://127.0.0.1:1/compatible-mode/v1");
        reportProperties.setApiKey("local-reconcile-only-key");
        reportProperties.setAllowLoopbackHttp(true);
        reportProperties.setEnabled(true);
        reportWorker.runOnce();
        assertEquals("COMPLETED",jdbc.queryForObject(
                "SELECT state FROM classroom_report_dispatch WHERE id=?",String.class,dispatchId));
        reportDispatch.markNeedsReview(dispatchId,"new-owner","REPORT_SUBMISSION_ALREADY_RECORDED");
        assertEquals("COMPLETED",jdbc.queryForObject(
                "SELECT state FROM classroom_report_dispatch WHERE id=?",String.class,dispatchId));
    }

    /** Explicit paid probe: one synthetic report, one submission, retained in an isolated MySQL schema. */
    @Test
    @EnabledIfEnvironmentVariable(named="CLASSROOM_REPORT_LIVE_TEST",matches="true")
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void liveSyntheticReportUsesAliyunOnce() throws Exception {
        if(!Boolean.getBoolean("mysqlMigration"))
            throw new IllegalStateException("Live report test requires the isolated MySQL migration runner");
        String key=System.getenv("CLASSROOM_REPORT_API_KEY");
        if(key==null||key.isBlank()) throw new IllegalStateException("Live report API key is missing");
        retainCommittedAudit=true;
        var prepared=preparedExternalRun("upload-live-report-0001","analysis-live-report-0001");
        long mediaId=prepared.snapshot().input().mediaId();
        long dispatchId=jdbc.queryForObject("SELECT id FROM classroom_report_dispatch WHERE media_id=?",
                Long.class,mediaId);
        reportProperties.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        reportProperties.setApiKey(key);
        reportProperties.setModel("qwen3.7-plus");
        reportProperties.setTimeoutSeconds(60);
        reportProperties.setMaxOutputTokens(1024);
        reportProperties.setCallCeilingCents(100);
        reportProperties.setTotalCapCents(2000);
        reportProperties.setBatchSize(1);
        reportProperties.setLeaseSeconds(120);
        reportProperties.setEnabled(true);
        assertTrue(reportProperties.ready());

        reportWorker.runOnce(); // No retry: a possibly charged failure stays in the retained ledger.

        String state=jdbc.queryForObject("SELECT state FROM classroom_report_dispatch WHERE id=?",
                String.class,dispatchId);
        Long runId=jdbc.queryForObject("SELECT run_id FROM classroom_report_dispatch WHERE id=?",
                Long.class,dispatchId);
        assertNotNull(runId);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_paid_test_reservation WHERE category='REPORT' AND subject_id=?",
                Integer.class,runId));
        assertEquals("COMPLETED",state,"Live model response was not accepted; inspect retained dispatch and ledger metadata");
        assertNotNull(generatedReports.report(runId));
        assertEquals(100,paidBudget.reservedCents());
    }

    @Test void invalidGeneratedDraftDoesNotCompleteRunOrCreateReport() throws Exception {
        var prepared=preparedExternalRun("upload-generated-invalid-0001","analysis-generated-invalid-0001");
        String invalid="{\"reportVersion\":\"1.0\"}";
        var reservation=paidBudget.reserve("REPORT",prepared.run().id(),"c".repeat(64),
                "qwen3.7-plus",100,2000);
        paidBudget.markSucceeded(reservation.row().id(),"provider-generated-2",40,20,60,hash(invalid));
        assertThrows(IllegalArgumentException.class,()->generatedReports.persist(prepared.run().id(),
                reservation.row().id(),"qwen3.7-plus",invalid));
        assertEquals("PREPARED",jdbc.queryForObject(
                "SELECT state FROM classroom_analysis_run WHERE id=?",String.class,prepared.run().id()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_generated_report WHERE run_id=?",
                Integer.class,prepared.run().id()));
    }

    private PreparedExternalRun preparedExternalRun(String uploadKey,String requestKey) throws Exception {
        var session=login("supervisor-a-test");
        var uploaded=mvc.perform(multipart("/api/v1/course-offerings/101/class-sessions/video")
                        .header("Idempotency-Key",uploadKey).file(mp4())
                        .param("scheduleId","1001").param("sessionDate","2026-09-14")
                        .param("durationMs","60000").with(csrf()).session(session))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long classSessionId=((Number)com.jayway.jsonpath.JsonPath.read(uploaded,"$.classSessionId")).longValue();
        long mediaId=((Number)com.jayway.jsonpath.JsonPath.read(uploaded,"$.media[0].mediaId")).longValue();
        long transcriptId=jdbc.queryForObject(
                "SELECT id FROM classroom_module_job WHERE media_id=? AND module_type='TRANSCRIPTION'",
                Long.class,mediaId);
        long headUpId=jdbc.queryForObject(
                "SELECT id FROM classroom_module_job WHERE media_id=? AND module_type='HEAD_UP'",
                Long.class,mediaId);
        String transcriptKey=jdbc.queryForObject("SELECT client_request_id FROM classroom_module_job WHERE id=?",
                String.class,transcriptId);
        String headUpKey=jdbc.queryForObject("SELECT client_request_id FROM classroom_module_job WHERE id=?",
                String.class,headUpId);
        mvc.perform(post("/api/v1/class-sessions/module-jobs/"+transcriptId+"/result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transcriptEnvelope(transcriptKey,classSessionId,mediaId))
                        .with(csrf()).session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/class-sessions/module-jobs/"+headUpId+"/result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(headUpEnvelope(headUpKey,classSessionId,mediaId))
                        .with(csrf()).session(session)).andExpect(status().isOk());
        var snapshot=inputSnapshots.build(mediaId);
        return new PreparedExternalRun(analysisRuns.prepare(mediaId,requestKey),snapshot);
    }

    private static String generatedDraft(PreparedExternalRun prepared) {
        return """
                {"reportVersion":"1.0","runId":%d,"inputSha256":"%s",
                 "topics":[{"startMs":0,"endMs":20000,"summary":"讲解递归",
                   "evidenceRefs":[{"resultId":%d,"itemType":"SEGMENT","itemId":"seg-1"}]}],
                 "changes":[],"interpretations":[],"limitations":["不能推断理解程度"]}
                """.formatted(prepared.run().id(),prepared.snapshot().sha256(),
                prepared.snapshot().input().transcriptSource().resultId());
    }

    private static String hash(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private record PreparedExternalRun(ClassroomAnalysisRunService.PreparedRun run,
                                       ClassroomAnalysisInputSnapshotService.Snapshot snapshot) {}

    @Test void partialModuleProcessingIsDistinctFromSilentAndUnprocessedTimeInSnapshot() throws Exception {
        var session=login("supervisor-a-test");
        var upload=mvc.perform(multipart("/api/v1/course-offerings/101/class-sessions/video")
                        .header("Idempotency-Key","upload-partial-ranges-0001")
                        .file(mp4()).param("scheduleId","1001").param("sessionDate","2026-09-14")
                        .param("durationMs","60000").with(csrf()).session(session))
                .andExpect(status().isCreated()).andReturn();
        String body=upload.getResponse().getContentAsString();
        long classSessionId=((Number)com.jayway.jsonpath.JsonPath.read(body,"$.classSessionId")).longValue();
        long mediaId=((Number)com.jayway.jsonpath.JsonPath.read(body,"$.media[0].mediaId")).longValue();
        long transcriptId=jdbc.queryForObject("SELECT id FROM classroom_module_job WHERE media_id=? AND module_type='TRANSCRIPTION'",
                Long.class,mediaId);
        long headUpId=jdbc.queryForObject("SELECT id FROM classroom_module_job WHERE media_id=? AND module_type='HEAD_UP'",
                Long.class,mediaId);
        String transcriptKey=jdbc.queryForObject("SELECT client_request_id FROM classroom_module_job WHERE id=?",
                String.class,transcriptId);
        String headUpKey=jdbc.queryForObject("SELECT client_request_id FROM classroom_module_job WHERE id=?",
                String.class,headUpId);
        String partialTranscript=transcriptEnvelope(transcriptKey,classSessionId,mediaId)
                .replace("\"processedRanges\":[{\"startMs\":0,\"endMs\":60000}]",
                        "\"processedRanges\":[{\"startMs\":0,\"endMs\":10000},{\"startMs\":20000,\"endMs\":30000}]")
                .replace("\"endMs\":20000,\"text\":\"第一段\"", "\"endMs\":10000,\"text\":\"第一段\"")
                .replace("\"startMs\":20000,\"endMs\":60000,\"text\":\"第二段\"",
                        "\"startMs\":20000,\"endMs\":25000,\"text\":\"第二段\"");
        mvc.perform(post("/api/v1/class-sessions/module-jobs/"+transcriptId+"/result")
                        .contentType(MediaType.APPLICATION_JSON).content(partialTranscript)
                        .with(csrf()).session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/class-sessions/module-jobs/"+headUpId+"/result")
                        .contentType(MediaType.APPLICATION_JSON).content(headUpEnvelope(headUpKey,classSessionId,mediaId))
                        .with(csrf()).session(session)).andExpect(status().isOk());
        var snapshot=inputSnapshots.build(mediaId);
        assertEquals(java.util.List.of(new ClassroomAnalysisRepository.ProcessedRange(0,10_000),
                        new ClassroomAnalysisRepository.ProcessedRange(20_000,30_000)),
                snapshot.input().transcriptProcessedRanges());
        assertEquals(java.util.List.of(new ClassroomAnalysisRepository.ProcessedRange(0,60_000)),
                snapshot.input().headUpProcessedRanges());
        assertEquals(10_000,snapshot.input().transcript().get(0).endMs());
        assertEquals(20_000,snapshot.input().transcript().get(1).startMs());
        assertEquals(25_000,snapshot.input().transcript().get(1).endMs());
        var limitationRef=new ClassroomAnalysisEvidenceValidator.EvidenceRef(
                snapshot.input().headUpSource().resultId(),"WINDOW","win-2");
        ClassroomAnalysisEvidenceValidator.validateClaim(snapshot.input(),30_000,60_000,
                java.util.List.of(limitationRef));
        assertEquals(20_000,snapshot.input().completeness().transcriptProcessed().coveredMs());
        assertEquals(1.0/3,snapshot.input().completeness().transcriptProcessed().rate());
        assertEquals(15_000,snapshot.input().completeness().transcriptSegments().coveredMs());
        assertEquals(0.25,snapshot.input().completeness().transcriptSegments().rate());
        assertEquals(60_000,snapshot.input().completeness().headUpProcessed().coveredMs());
        assertEquals(30_000,snapshot.input().completeness().headUpValidWindows().coveredMs());
        var prepared=analysisRuns.prepare(mediaId,"analysis-partial-0001");
        String stored=jdbc.queryForObject("SELECT input_json FROM classroom_analysis_run WHERE id=?",
                String.class,prepared.id());
        assertEquals(10_000,((Number)com.jayway.jsonpath.JsonPath.read(stored,
                "$.transcriptProcessedRanges[0].endMs")).longValue());
        assertEquals(20_000,((Number)com.jayway.jsonpath.JsonPath.read(stored,
                "$.transcriptProcessedRanges[1].startMs")).longValue());
        assertEquals(60_000,((Number)com.jayway.jsonpath.JsonPath.read(stored,
                "$.headUpProcessedRanges[0].endMs")).longValue());
        assertEquals(15_000,((Number)com.jayway.jsonpath.JsonPath.read(stored,
                "$.completeness.transcriptSegments.coveredMs")).longValue());
        long resultId=snapshot.input().transcriptSource().resultId();
        jdbc.update("UPDATE classroom_processed_range SET end_ms=21000 WHERE result_id=? AND range_index=1",resultId);
        assertEquals("INPUT_NOT_READY",assertThrows(ClassroomAnalysisException.class,
                ()->inputSnapshots.build(mediaId)).code());
    }

    @Test void rejectsMismatchedModuleEnvelopeWithoutPersistingIt() throws Exception {
        var session=login("supervisor-a-test");
        var upload=mvc.perform(multipart("/api/v1/course-offerings/101/class-sessions/video")
                        .header("Idempotency-Key","upload-mismatch-0001")
                        .file(mp4()).param("scheduleId","1001").param("sessionDate","2026-09-14")
                        .param("durationMs","60000").with(csrf()).session(session)).andReturn();
        String body=upload.getResponse().getContentAsString();
        long classSessionId=((Number)com.jayway.jsonpath.JsonPath.read(body,"$.classSessionId")).longValue();
        long mediaId=((Number)com.jayway.jsonpath.JsonPath.read(body,"$.media[0].mediaId")).longValue();
        long transcriptId=jdbc.queryForObject("SELECT id FROM classroom_module_job WHERE media_id=? AND module_type='TRANSCRIPTION'",
                Long.class,mediaId);
        mvc.perform(post("/api/v1/class-sessions/module-jobs/"+transcriptId+"/result")
                        .contentType(MediaType.APPLICATION_JSON).content(transcriptEnvelope("wrong-request",classSessionId,mediaId))
                        .with(csrf()).session(session))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MODULE_CONTRACT_VIOLATION"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM classroom_module_result",Integer.class));
    }

    private MockMultipartFile mp4() {
        return new MockMultipartFile("video","lesson.mp4","video/mp4",
                new byte[]{0,0,0,24,'f','t','y','p','i','s','o','m',0,0,0,0});
    }
    private String transcriptEnvelope(String requestId,long classSessionId,long mediaId) {
        return """
                {"schemaVersion":"1.0","clientRequestId":"%s","jobId":"asr-job-1",
                 "moduleType":"TRANSCRIPTION","providerId":"transcription-module","moduleVersion":"0.1.0",
                 "classSessionId":"%d","mediaId":"%d","mediaSha256":"%s","durationMs":60000,
                 "timeBase":"MEDIA_START_MS","analysisRange":{"startMs":0,"endMs":60000},
                 "status":"SUCCEEDED","revision":1,"createdAt":"2026-09-22T00:00:00Z",
                 "updatedAt":"2026-09-22T00:00:01Z","payload":{"language":"zh-CN","engineVersion":"test",
                 "processedRanges":[{"startMs":0,"endMs":60000}],"segments":[
                 {"id":"seg-1","startMs":0,"endMs":20000,"text":"第一段","confidence":null,"speakerLabel":null},
                 {"id":"seg-2","startMs":20000,"endMs":60000,"text":"第二段","confidence":0.9,"speakerLabel":null}],
                 "warnings":[]},"error":null}
                """.formatted(requestId,classSessionId,mediaId,sha());
    }
    private String headUpEnvelope(String requestId,long classSessionId,long mediaId) {
        return """
                {"schemaVersion":"1.0","clientRequestId":"%s","jobId":"head-job-1",
                 "moduleType":"HEAD_UP","providerId":"head-up-module","moduleVersion":"0.1.0",
                 "classSessionId":"%d","mediaId":"%d","mediaSha256":"%s","durationMs":60000,
                 "timeBase":"MEDIA_START_MS","analysisRange":{"startMs":0,"endMs":60000},
                 "status":"SUCCEEDED","revision":1,"createdAt":"2026-09-22T00:00:00Z",
                 "updatedAt":"2026-09-22T00:00:01Z","payload":{"definitionId":"head-up-qwen-vl-v1",
                 "denominatorDefinition":"test","sampleMode":"REPRESENTATIVE_FRAME","valueMode":"COUNTS",
                 "windowMs":30000,"processedRanges":[{"startMs":0,"endMs":60000}],"windows":[
                 {"id":"win-1","startMs":0,"endMs":30000,"sampleAtMs":15000,"quality":"VALID",
                  "qualityReason":null,"headUpCount":30,"validObservedCount":40,"headUpRatio":null},
                 {"id":"win-2","startMs":30000,"endMs":60000,"sampleAtMs":45000,"quality":"NO_SUBJECTS",
                  "qualityReason":"NO_OBSERVABLE_SUBJECTS","headUpCount":0,"validObservedCount":0,"headUpRatio":null}],
                 "warnings":[]},"error":null}
                """.formatted(requestId,classSessionId,mediaId,sha());
    }
    private String sha() {
        return jdbc.queryForObject("SELECT sha256 FROM classroom_media ORDER BY id DESC LIMIT 1",String.class);
    }
    private MockHttpSession login(String username) throws Exception {
        return (MockHttpSession)mvc.perform(post("/api/v1/auth/login").with(csrf())
                        .param("username",username).param("password",TEST_PASSWORD))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    static final class StubClassroomMediaProbe implements ClassroomMediaProbe {
        private ProbeResult result;
        private volatile CountDownLatch bothAtProbe;
        private volatile CountDownLatch releaseProbe;
        void reset() { result=new ProbeResult(60_000,true,true); bothAtProbe=null; releaseProbe=null; }
        @Override public ProbeResult probe(Path mediaFile) {
            assertTrue(Files.isRegularFile(mediaFile));
            if (bothAtProbe!=null) {
                bothAtProbe.countDown();
                try {
                    if (!releaseProbe.await(10,TimeUnit.SECONDS)) throw new AssertionError("Probe barrier timed out");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Probe barrier interrupted",failure);
                }
            }
            return result;
        }
    }
}
