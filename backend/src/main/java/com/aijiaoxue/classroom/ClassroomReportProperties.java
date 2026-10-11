package com.aijiaoxue.classroom;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Explicit opt-in; no key or paid submission is present in the default deployment. */
@Component
@ConfigurationProperties(prefix="app.classroom-analysis.report")
class ClassroomReportProperties {
    private boolean enabled;
    private String baseUrl="https://dashscope.aliyuncs.com/compatible-mode/v1";
    private String apiKey="";
    private String model="qwen3.7-plus";
    private int timeoutSeconds=30;
    private int maxOutputTokens=1024;
    private boolean allowLoopbackHttp;
    private int callCeilingCents=100;
    private int totalCapCents=2000;
    private int batchSize=2;
    private int leaseSeconds=120;

    boolean ready() {
        if(!enabled||batchSize<1||batchSize>20||leaseSeconds<timeoutSeconds+30
                ||callCeilingCents<1||callCeilingCents>totalCapCents
                ||totalCapCents>ClassroomPaidTestBudgetService.MAX_CONFIGURED_CAP_CENTS) return false;
        try { settings(); return true; }
        catch(RuntimeException failure) { return false; }
    }

    ClassroomReportModelClient.Settings settings() {
        var settings=new ClassroomReportModelClient.Settings(URI.create(baseUrl),apiKey,model,
                timeoutSeconds,maxOutputTokens,allowLoopbackHttp);
        settings.validate();
        return settings;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled=value; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String value) { baseUrl=value; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String value) { apiKey=value; }
    public String getModel() { return model; }
    public void setModel(String value) { model=value; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int value) { timeoutSeconds=value; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int value) { maxOutputTokens=value; }
    public boolean isAllowLoopbackHttp() { return allowLoopbackHttp; }
    public void setAllowLoopbackHttp(boolean value) { allowLoopbackHttp=value; }
    public int getCallCeilingCents() { return callCeilingCents; }
    public void setCallCeilingCents(int value) { callCeilingCents=value; }
    public int getTotalCapCents() { return totalCapCents; }
    public void setTotalCapCents(int value) { totalCapCents=value; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int value) { batchSize=value; }
    public int getLeaseSeconds() { return leaseSeconds; }
    public void setLeaseSeconds(int value) { leaseSeconds=value; }
}
