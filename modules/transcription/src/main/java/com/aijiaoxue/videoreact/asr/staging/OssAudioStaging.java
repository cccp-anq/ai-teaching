package com.aijiaoxue.videoreact.asr.staging;
import com.aliyun.oss.*;
import com.aliyun.oss.model.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
public class OssAudioStaging implements AudioStaging  {
    private final StagingProperties p;
    public OssAudioStaging(StagingProperties p) {
        this.p=p;
    }
    public boolean urlStagingAvailable() {
        return p.isOssConfigured();
    }
    public String description() {
        return "OSS 私有音频暂存（对象键持久化，终态及恢复后清理）";
    }
    private String normalizedPrefix() { return p.prefix()==null?"classroom/staging/":p.prefix().replaceAll("/+$","")+"/"; }
    private OSS client(String endpoint) {
        ClientBuilderConfiguration c=new ClientBuilderConfiguration();
        c.setConnectionTimeout(Math.max(1,p.timeoutSeconds())*1000);
        c.setSocketTimeout(Math.max(1,p.timeoutSeconds())*1000);
        c.setMaxErrorRetry(Math.max(0,p.maxRetries()));
        return new OSSClientBuilder().build(endpoint,p.accessKeyId(),p.accessKeySecret(),c);
    }
    public StagedAudio stage(Path file,String contentType,String fileName,Duration lifetime) {
        if(!urlStagingAvailable())throw new AudioStagingException("OSS_NOT_CONFIGURED","OSS 必填配置缺失",false);
        String endpoint=p.resolvedPublicEndpoint();
        if(endpoint==null||!endpoint.startsWith("https://"))throw new AudioStagingException("OSS_ENDPOINT_INVALID","OSS 下载端点需要 HTTPS",false);
        String key=normalizedPrefix()+UUID.randomUUID()+".wav";
        Instant expires=Instant.now().plusSeconds(Math.max(Math.max(1,lifetime.toSeconds()),p.urlExpirySeconds()));
        OSS uploader=client(p.resolvedUploadEndpoint());
        try {
            ObjectMetadata metadata=new ObjectMetadata();
            metadata.setContentType(contentType);
            metadata.setContentLength(Files.size(file));
            var request=new PutObjectRequest(p.bucket(),key,file.toFile());
            request.setMetadata(metadata);
            uploader.putObject(request);
        }
        catch(Exception e) {
            Throwable root=e;while(root.getCause()!=null&&root.getCause()!=root)root=root.getCause();
            String reason=String.valueOf(root.getMessage()).replaceAll("https?://[^\\s]+","[OSS端点]");
            for(String secret:List.of(p.accessKeyId(),p.accessKeySecret(),p.bucket()))if(!secret.isBlank())reason=reason.replace(secret,"[已隐藏]");
            if(reason.length()>200)reason=reason.substring(0,200);
            throw new AudioStagingException("OSS_UPLOAD_FAILED","音频上传失败: "+root.getClass().getSimpleName()+" "+reason,true,e);
        }
        finally {
            uploader.shutdown();
        }
        OSS signer=client(endpoint);
        String url;
        try {
            url=signer.generatePresignedUrl(p.bucket(),key,Date.from(expires)).toString();
        }
        finally {
            signer.shutdown();
        }
        final String signed=url;
        return new StagedAudio() {
            private boolean closed=false;
            public String url() {
                return signed;
            }
            public Instant expiresAt() {
                return expires;
            }
            public String objectKey() { return key; }
            public synchronized void close() {
                if(closed)return;
                closed=true;
                delete(key);
            }
        }
        ;
    }
    @Override public boolean delete(String key) {
        if (key == null || !key.startsWith(normalizedPrefix()) || key.contains("..")) {
            org.slf4j.LoggerFactory.getLogger(OssAudioStaging.class).error("拒绝清理不属于配置前缀的 OSS 对象键");
            return false;
        }
        OSS cleaner=client(p.resolvedUploadEndpoint());
        try { cleaner.deleteObject(p.bucket(),key); return true; }
        catch(Exception e) {
            org.slf4j.LoggerFactory.getLogger(OssAudioStaging.class).warn("OSS 清理未完成，将保留对象键供下次恢复清理: {}",e.getClass().getSimpleName());
            return false;
        } finally { cleaner.shutdown(); }
    }
}
