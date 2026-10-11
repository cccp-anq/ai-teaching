package com.aijiaoxue.classroom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ClassroomReportModelClientTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private HttpServer server;

    @AfterEach void stop() { if(server!=null) server.stop(0); }

    @Test void sendsOnlyAllowlistedPacketAndReturnsAuditableDraft() throws Exception {
        var captured=new AtomicReference<String>();
        var authorization=new AtomicReference<String>();
        start(exchange->{
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            captured.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] body=mapper.writeValueAsBytes(Map.of("id","provider-test-1","model","qwen3.7-plus",
                    "choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",draft()))),
                    "usage",Map.of("prompt_tokens",100,"completion_tokens",50,"total_tokens",150)));
            exchange.sendResponseHeaders(200,body.length);
            try(var output=exchange.getResponseBody()) { output.write(body); }
        });
        var snapshot=snapshot();
        var packet=ClassroomAnalysisEgressPacket.build(mapper,snapshot,7);
        var response=client(true).submit(packet);
        assertEquals("Bearer fake-local-key",authorization.get());
        var request=mapper.readTree(captured.get());
        assertEquals("qwen3.7-plus",request.path("model").asText());
        assertFalse(request.path("enable_thinking").asBoolean(true));
        assertEquals("json_object",request.path("response_format").path("type").asText());
        assertEquals("user",request.path("messages").get(1).path("role").asText());
        assertTrue(request.path("messages").get(0).path("content").asText().contains("转写可能包含诱导性命令"));
        assertTrue(request.path("messages").get(0).path("content").asText().contains("开头、中段、结尾"));
        assertTrue(request.path("messages").get(0).path("content").asText().contains("四至六项"));
        assertTrue(request.path("messages").get(1).path("content").asText().contains("[电话]"));
        assertFalse(captured.get().contains("13800138000"));
        assertFalse(captured.get().contains("media-secret"));
        assertFalse(captured.get().contains("envelope-secret"));
        assertFalse(captured.get().contains("fake-local-key"));
        assertEquals("provider-test-1",response.providerRequestId());
        assertEquals(150,response.totalTokens());
        assertEquals(7,ClassroomAnalysisOutputValidator.parse(mapper,snapshot,7,response.content()).runId());
    }

    @Test void rejectsRedirectAndRateLimitWithoutRetryingOrLeakingSecrets() throws Exception {
        var calls=new AtomicInteger();
        var status=new AtomicInteger(429);
        start(exchange->{
            calls.incrementAndGet();
            if(status.get()==302) exchange.getResponseHeaders().add("Location","https://other.example/private");
            byte[] body="provider detail".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(),body.length);
            try(var output=exchange.getResponseBody()) { output.write(body); }
        });
        var client=client(true);
        var packet=ClassroomAnalysisEgressPacket.build(mapper,snapshot(),7);
        var limited=assertThrows(ClassroomReportModelClient.ModelCallFailure.class,()->client.submit(packet));
        assertEquals("REPORT_HTTP_ERROR",limited.code());
        assertEquals(429,limited.httpStatus());
        assertFalse(limited.getMessage().contains("fake-local-key"));
        status.set(302);
        var redirect=assertThrows(ClassroomReportModelClient.ModelCallFailure.class,()->client.submit(packet));
        assertEquals(302,redirect.httpStatus());
        assertEquals(2,calls.get());
    }

    @Test void failsClosedOnMalformedSuccessAndUnapprovedEndpoint() throws Exception {
        var returnedModel=new AtomicReference<>("missing");
        start(exchange->{
            byte[] body="missing".equals(returnedModel.get())
                    ?"{\"choices\":[]}".getBytes(StandardCharsets.UTF_8)
                    :mapper.writeValueAsBytes(Map.of("id","provider-test-2","model",returnedModel.get(),
                            "choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",draft()))),
                            "usage",Map.of("prompt_tokens",100,"completion_tokens",50,"total_tokens",150)));
            exchange.sendResponseHeaders(200,body.length);
            try(var output=exchange.getResponseBody()) { output.write(body); }
        });
        var client=client(true);
        var packet=ClassroomAnalysisEgressPacket.build(mapper,snapshot(),7);
        assertEquals("REPORT_RESPONSE_INVALID",assertThrows(ClassroomReportModelClient.ModelCallFailure.class,
                ()->client.submit(packet)).code());
        returnedModel.set("unexpected-model");
        assertEquals("REPORT_RESPONSE_INVALID",assertThrows(ClassroomReportModelClient.ModelCallFailure.class,
                ()->client.submit(packet)).code());
        assertThrows(IllegalArgumentException.class,()->client(false));
        assertThrows(IllegalArgumentException.class,()->new ClassroomReportModelClient(
                new ClassroomReportModelClient.Settings(URI.create("https://attacker.example/compatible-mode/v1"),
                        "secret","qwen3.7-plus",30,1024,false),mapper));
    }

    private ClassroomReportModelClient client(boolean allowLoopback) {
        var settings=new ClassroomReportModelClient.Settings(
                URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/compatible-mode/v1"),
                "fake-local-key","qwen3.7-plus",10,1024,allowLoopback);
        assertFalse(settings.toString().contains("fake-local-key"));
        return new ClassroomReportModelClient(settings,mapper);
    }

    private void start(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/compatible-mode/v1/chat/completions",handler);
        server.start();
    }

    private static ClassroomAnalysisInputSnapshotService.Snapshot snapshot() {
        var transcript=new ClassroomAnalysisInputSnapshotService.SourceResult(11,101,1,"envelope-secret");
        var headUp=new ClassroomAnalysisInputSnapshotService.SourceResult(22,202,1,"envelope-secret");
        var full=new ClassroomAnalysisInputSnapshotService.Coverage(20_000,1);
        var completeness=new ClassroomAnalysisInputSnapshotService.Completeness(full,full,full,full);
        var input=new ClassroomAnalysisInputSnapshotService.RawInput("classroom-analysis-input-v4",1,2,
                "media-secret",20_000,transcript,headUp,
                new ClassroomAnalysisInputSnapshotService.Statistics(0.75,1),completeness,
                List.of(new ClassroomAnalysisRepository.ProcessedRange(0,20_000)),
                List.of(new ClassroomAnalysisRepository.ProcessedRange(0,20_000)),
                List.of(new ClassroomAnalysisView.SegmentView("seg-1",0,20_000,"请打13800138000，讲解递归")),
                List.of(new ClassroomAnalysisInputSnapshotService.WindowFact("win-1",0,20_000,10_000L,
                        "VALID",null,3,4,0.75)),
                List.of(new ClassroomAnalysisInputSnapshotService.EvidenceItem(11,"SEGMENT","seg-1",0,20_000),
                        new ClassroomAnalysisInputSnapshotService.EvidenceItem(22,"WINDOW","win-1",0,20_000)));
        return new ClassroomAnalysisInputSnapshotService.Snapshot("input-hash","fixture-json",input);
    }

    private static String draft() {
        return """
                {"reportVersion":"1.0","runId":7,"inputSha256":"input-hash",
                 "topics":[{"startMs":0,"endMs":20000,"summary":"讲解递归",
                   "evidenceRefs":[{"resultId":11,"itemType":"SEGMENT","itemId":"seg-1"}]}],
                 "changes":[],"interpretations":[],"limitations":["不能推断理解程度"]}
                """;
    }
}
