package com.aijiaoxue.classroom;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.classroom-analysis")
public class ClassroomAnalysisProperties {
    private long maxVideoBytes = 1_073_741_824L;
    private String ffprobePath = "ffprobe";
    private int mediaProbeTimeoutSeconds = 30;
    private long durationToleranceMs = 2_000;
    private boolean mockModulesEnabled;
    private boolean integrationEnabled;
    private String publicBaseUrl = "";
    private String mediaSigningKey = "";
    private long mediaUrlTtlSeconds = 900;
    private long workerDelayMs = 2_000;
    private long workerInitialDelayMs = 60_000;
    private int batchSize = 10;
    private long leaseSeconds = 60;
    private long pollIntervalMs = 2_000;
    private int requestTimeoutSeconds = 10;
    private long responseMaxBytes = 8_388_608;
    private int maxConsecutiveFailures = 20;
    private int maxModuleAttempts = 4;
    private long jobDeadlineSeconds = 7_200;
    private final Module transcription = new Module();
    private final HeadUpModule headUp = new HeadUpModule();

    public long getMaxVideoBytes() { return maxVideoBytes; }
    public void setMaxVideoBytes(long value) { maxVideoBytes = value; }
    public String getFfprobePath() { return ffprobePath; }
    public void setFfprobePath(String value) { ffprobePath = value == null ? "" : value.trim(); }
    public int getMediaProbeTimeoutSeconds() { return mediaProbeTimeoutSeconds; }
    public void setMediaProbeTimeoutSeconds(int value) { mediaProbeTimeoutSeconds = value; }
    public long getDurationToleranceMs() { return durationToleranceMs; }
    public void setDurationToleranceMs(long value) { durationToleranceMs = value; }
    public boolean isMockModulesEnabled() { return mockModulesEnabled; }
    public void setMockModulesEnabled(boolean value) { mockModulesEnabled = value; }
    public boolean isIntegrationEnabled() { return integrationEnabled; }
    public void setIntegrationEnabled(boolean value) { integrationEnabled = value; }
    public String getPublicBaseUrl() { return publicBaseUrl; }
    public void setPublicBaseUrl(String value) { publicBaseUrl = value == null ? "" : value.trim(); }
    public String getMediaSigningKey() { return mediaSigningKey; }
    public void setMediaSigningKey(String value) { mediaSigningKey = value == null ? "" : value; }
    public long getMediaUrlTtlSeconds() { return mediaUrlTtlSeconds; }
    public void setMediaUrlTtlSeconds(long value) { mediaUrlTtlSeconds = value; }
    public long getWorkerDelayMs() { return workerDelayMs; }
    public void setWorkerDelayMs(long value) { workerDelayMs = value; }
    public long getWorkerInitialDelayMs() { return workerInitialDelayMs; }
    public void setWorkerInitialDelayMs(long value) { workerInitialDelayMs = value; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int value) { batchSize = value; }
    public long getLeaseSeconds() { return leaseSeconds; }
    public void setLeaseSeconds(long value) { leaseSeconds = value; }
    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long value) { pollIntervalMs = value; }
    public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
    public void setRequestTimeoutSeconds(int value) { requestTimeoutSeconds = value; }
    public long getResponseMaxBytes() { return responseMaxBytes; }
    public void setResponseMaxBytes(long value) { responseMaxBytes = value; }
    public int getMaxConsecutiveFailures() { return maxConsecutiveFailures; }
    public void setMaxConsecutiveFailures(int value) { maxConsecutiveFailures = value; }
    public int getMaxModuleAttempts() { return maxModuleAttempts; }
    public void setMaxModuleAttempts(int value) { maxModuleAttempts = value; }
    public long getJobDeadlineSeconds() { return jobDeadlineSeconds; }
    public void setJobDeadlineSeconds(long value) { jobDeadlineSeconds = value; }
    public Module getTranscription() { return transcription; }
    public HeadUpModule getHeadUp() { return headUp; }

    boolean integrationReady() {
        return integrationEnabled
                && validEndpoint(publicBaseUrl)
                && mediaSigningKey.length() >= 32
                && mediaUrlTtlSeconds >= 60 && mediaUrlTtlSeconds <= 3_600
                && batchSize > 0 && leaseSeconds > 0 && pollIntervalMs > 0
                && requestTimeoutSeconds > 0 && responseMaxBytes > 0
                && maxConsecutiveFailures > 0 && maxModuleAttempts > 0 && jobDeadlineSeconds > 0
                && transcription.ready() && headUp.ready()
                && headUp.windowMs > 0 && !headUp.definitionId.isBlank();
    }

    Module module(String moduleType) {
        if("TRANSCRIPTION".equals(moduleType)) return transcription;
        if("HEAD_UP".equals(moduleType)) return headUp;
        throw new IllegalArgumentException("Unsupported classroom module type");
    }

    private static boolean validEndpoint(String raw) {
        try {
            URI uri = URI.create(raw);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                return false;
            if ("https".equalsIgnoreCase(uri.getScheme())) return true;
            return "http".equalsIgnoreCase(uri.getScheme())
                    && ("127.0.0.1".equals(uri.getHost()) || "localhost".equalsIgnoreCase(uri.getHost()));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static class Module {
        private String baseUrl = "";
        private String bearerToken = "";
        private String providerId = "";
        private String moduleVersion = "";
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String value) { baseUrl = value == null ? "" : value.replaceAll("/+$", ""); }
        public String getBearerToken() { return bearerToken; }
        public void setBearerToken(String value) { bearerToken = value == null ? "" : value; }
        public String getProviderId() { return providerId; }
        public void setProviderId(String value) { providerId = value == null ? "" : value.trim(); }
        public String getModuleVersion() { return moduleVersion; }
        public void setModuleVersion(String value) { moduleVersion = value == null ? "" : value.trim(); }
        boolean ready() { return validEndpoint(baseUrl) && !bearerToken.isBlank() && !providerId.isBlank()
                && !moduleVersion.isBlank(); }
    }

    public static final class HeadUpModule extends Module {
        private long windowMs = 10_000;
        private String definitionId = "head-up-attendance-qwen-vl-v2";
        public long getWindowMs() { return windowMs; }
        public void setWindowMs(long value) { windowMs = value; }
        public String getDefinitionId() { return definitionId; }
        public void setDefinitionId(String value) { definitionId = value == null ? "" : value.trim(); }
    }
}
