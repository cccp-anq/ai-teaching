package com.aijiaoxue.classroom;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
final class ClassroomMediaSigner {
    private final ClassroomAnalysisProperties properties;

    ClassroomMediaSigner(ClassroomAnalysisProperties properties) {
        this.properties = properties;
    }

    SignedMediaUrl create(ClassroomAnalysisRepository.ModuleJobRow job, Instant now) {
        long expires = now.plusSeconds(properties.getMediaUrlTtlSeconds()).getEpochSecond();
        String signature = sign(job.mediaId(), job.id(), job.moduleType(), job.attempt(), expires);
        String base = properties.getPublicBaseUrl().replaceAll("/+$", "");
        URI uri = URI.create(base + "/api/v1/classroom-module-media/" + job.mediaId()
                + "?jobId=" + job.id() + "&moduleType=" + job.moduleType() + "&attempt=" + job.attempt()
                + "&expires=" + expires + "&signature=" + signature);
        return new SignedMediaUrl(uri, Instant.ofEpochSecond(expires));
    }

    boolean verify(long mediaId, long jobId, String moduleType, int attempt, long expires, String signature, Instant now) {
        if (properties.getMediaSigningKey().length() < 32 || signature == null || signature.length() != 64
                || expires < now.getEpochSecond()
                || expires > now.plusSeconds(properties.getMediaUrlTtlSeconds() + 30).getEpochSecond()) return false;
        byte[] expected = HexFormat.of().parseHex(sign(mediaId, jobId, moduleType, attempt, expires));
        byte[] supplied;
        try { supplied = HexFormat.of().parseHex(signature); }
        catch (IllegalArgumentException failure) { return false; }
        return MessageDigest.isEqual(expected, supplied);
    }

    private String sign(long mediaId, long jobId, String moduleType, int attempt, long expires) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getMediaSigningKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String value = mediaId + "\n" + jobId + "\n" + moduleType + "\n" + attempt + "\n" + expires;
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot sign classroom media URL", failure);
        }
    }

    record SignedMediaUrl(URI uri, Instant expiresAt) {}
}
