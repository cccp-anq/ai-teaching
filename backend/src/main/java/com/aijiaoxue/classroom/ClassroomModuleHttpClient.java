package com.aijiaoxue.classroom;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
final class ClassroomModuleHttpClient {
    private final ClassroomAnalysisProperties properties;
    private final ClassroomMediaSigner mediaSigner;
    private final ObjectMapper mapper;
    private final HttpClient client;

    ClassroomModuleHttpClient(ClassroomAnalysisProperties properties,ClassroomMediaSigner mediaSigner,ObjectMapper mapper) {
        this.properties=properties; this.mediaSigner=mediaSigner; this.mapper=mapper;
        this.client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    JsonNode submit(ClassroomAnalysisRepository.ModuleJobRow job) {
        preflight(job);
        var signed=mediaSigner.create(job,java.time.Instant.now());
        Map<String,Object> media=new LinkedHashMap<>();
        media.put("sha256",job.sha256()); media.put("durationMs",job.durationMs());
        media.put("mimeType",job.contentType()); media.put("sourceUrl",signed.uri().toString());
        media.put("urlExpiresAt",signed.expiresAt().toString());
        Map<String,Object> request=new LinkedHashMap<>();
        request.put("schemaVersion","1.0"); request.put("clientRequestId",job.clientRequestId());
        request.put("moduleType",job.moduleType()); request.put("classSessionId",Long.toString(job.classSessionId()));
        request.put("mediaId",Long.toString(job.mediaId())); request.put("media",media);
        request.put("analysisRange",Map.of("startMs",0,"endMs",job.durationMs()));
        if("TRANSCRIPTION".equals(job.moduleType()))
            request.put("parameters",Map.of("language","zh-CN","enableSpeakerLabels",false));
        else request.put("parameters",Map.of("windowMs",properties.getHeadUp().getWindowMs(),
                "outputMode","COUNTS","definitionId",properties.getHeadUp().getDefinitionId()));
        return call(job,"/api/v1/jobs",request,false);
    }

    JsonNode poll(ClassroomAnalysisRepository.ModuleJobRow job) {
        String encoded=URLEncoder.encode(job.providerJobId(),StandardCharsets.UTF_8).replace("+","%20");
        return call(job,"/api/v1/jobs/"+encoded,null,true);
    }

    private void preflight(ClassroomAnalysisRepository.ModuleJobRow job) {
        var module=properties.module(job.moduleType());
        JsonNode health=health(module);
        requireHealthValue(health,"status","UP");
        requireHealthValue(health,"moduleType",job.moduleType());
        requireHealthValue(health,"providerId",module.getProviderId());
        requireHealthValue(health,"moduleVersion",module.getModuleVersion());
        if("TRANSCRIPTION".equals(job.moduleType())) {
            requireHealthValue(health,"schemaVersion","1.0");
            requireHealthValue(health,"timeBase","MEDIA_START_MS");
            if(!contains(health.path("capabilities").path("languages"),"zh-CN"))
                throw preflightFailure("转写模块不支持zh-CN");
            requireLimit(health.path("capabilities").path("maxDurationMs"),job.durationMs());
        } else {
            requireHealthValue(health,"apiVersion","1.0");
            requireHealthValue(health,"definitionId",properties.getHeadUp().getDefinitionId());
            if(!contains(health.path("outputModes"),"COUNTS"))
                throw preflightFailure("抬头模块不支持COUNTS输出");
            if(!health.path("auth").path("bearerRequired").asBoolean(false)
                    ||!health.path("auth").path("tokensConfigured").asBoolean(false))
                throw preflightFailure("抬头模块机器鉴权未就绪");
            requireLimit(health.path("limits").path("maxDurationMs"),job.durationMs());
        }
    }

    private JsonNode call(ClassroomAnalysisRepository.ModuleJobRow job,String path,Object body,boolean polling) {
        var module=properties.module(job.moduleType());
        try {
            var builder=HttpRequest.newBuilder(URI.create(module.getBaseUrl()+path))
                    .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                    .header("Authorization","Bearer "+module.getBearerToken())
                    .header("Accept","application/json");
            if(polling) builder.header("X-Client-Request-Id",job.clientRequestId()).GET();
            else builder.header("Content-Type","application/json")
                    .header("Idempotency-Key",job.clientRequestId())
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body),StandardCharsets.UTF_8));
            return send(builder.build(),polling);
        } catch(ModuleCallException failure) {
            throw failure;
        } catch(InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new ModuleCallException(true,false,null,null,"模块请求被中断");
        } catch(IOException|IllegalArgumentException failure) {
            throw new ModuleCallException(true,false,null,null,"模块连接失败");
        }
    }

    private JsonNode health(ClassroomAnalysisProperties.Module module) {
        try {
            var request=HttpRequest.newBuilder(URI.create(module.getBaseUrl()+"/api/v1/health"))
                    .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                    .header("Authorization","Bearer "+module.getBearerToken())
                    .header("Accept","application/json").GET().build();
            return send(request,false);
        } catch(ModuleCallException failure) {
            throw failure;
        } catch(InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new ModuleCallException(true,false,null,null,"模块请求被中断");
        } catch(IOException|IllegalArgumentException failure) {
            throw new ModuleCallException(true,false,null,null,"模块连接失败");
        }
    }

    private JsonNode send(HttpRequest request,boolean notFoundIsJob)
            throws IOException,InterruptedException {
        var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
        byte[] bytes=bounded(response.body());
        int status=response.statusCode();
        if(status<200||status>=300) throw new ModuleCallException(status==429||status>=500,
                notFoundIsJob&&status==404,retryAfter(response),null,"模块HTTP请求失败（"+status+"）");
        try { return mapper.readTree(bytes); }
        catch(Exception invalid) { throw new ModuleCallException(false,false,null,null,"模块返回了无效JSON"); }
    }

    private static void requireHealthValue(JsonNode health,String field,String expected) {
        if(!expected.equals(health.path(field).asText()))
            throw preflightFailure("模块健康信息与登记值不一致");
    }

    private static void requireLimit(JsonNode value,long durationMs) {
        try {
            if(value.isMissingNode()||Long.parseLong(value.asText())<durationMs)
                throw preflightFailure("媒体时长超过模块能力上限");
        } catch(NumberFormatException invalid) {
            throw preflightFailure("模块健康信息缺少有效时长上限");
        }
    }

    private static boolean contains(JsonNode array,String expected) {
        if(!array.isArray()) return false;
        for(JsonNode value:array) if(expected.equals(value.asText())) return true;
        return false;
    }

    private static ModuleCallException preflightFailure(String message) {
        return new ModuleCallException(false,false,null,"MODULE_PREFLIGHT_MISMATCH",message);
    }

    private byte[] bounded(InputStream source) throws IOException {
        long max=properties.getResponseMaxBytes();
        try(source;var output=new ByteArrayOutputStream((int)Math.min(max,65_536))) {
            byte[] buffer=new byte[8_192]; long total=0; int count;
            while((count=source.read(buffer))!=-1) {
                total+=count;
                if(total>max) throw new ModuleCallException(false,false,null,null,"模块响应超过大小限制");
                output.write(buffer,0,count);
            }
            return output.toByteArray();
        }
    }

    private static Duration retryAfter(HttpResponse<?> response) {
        try {
            long seconds=Long.parseLong(response.headers().firstValue("Retry-After").orElse(""));
            return seconds>0&&seconds<=300?Duration.ofSeconds(seconds):null;
        } catch(NumberFormatException ignored) { return null; }
    }

    static final class ModuleCallException extends RuntimeException {
        private final boolean retryable;
        private final boolean notFound;
        private final Duration retryAfter;
        private final String errorCode;
        ModuleCallException(boolean retryable,boolean notFound,Duration retryAfter,String errorCode,String message) {
            super(message); this.retryable=retryable; this.notFound=notFound; this.retryAfter=retryAfter;
            this.errorCode=errorCode;
        }
        boolean retryable() { return retryable; }
        boolean notFound() { return notFound; }
        Duration retryAfter() { return retryAfter; }
        String errorCode() { return errorCode; }
    }
}
