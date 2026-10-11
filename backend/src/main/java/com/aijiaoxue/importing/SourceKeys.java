package com.aijiaoxue.importing;

import com.aijiaoxue.course.WeekMask;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Versioned source identities, never official course/offering numbers. */
public final class SourceKeys {
    private SourceKeys() {}

    public static String offering(String namespace, String semesterCode, String scopeCode,
                                  String name, String type, String classText) {
        if (!"本".equals(type) && !"实".equals(type)) {
            throw new IllegalArgumentException("Source activity type must be 本 or 实");
        }
        return hash("offering-v1", trim(namespace), trim(semesterCode), trim(scopeCode),
                trim(name), type, trim(classText));
    }

    public static String schedule(String offeringKey, String mask, int weekday,
                                  int startSection, int endSection, String location) {
        if (offeringKey == null || !offeringKey.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid source offering key");
        }
        WeekMask.weeks(mask);
        if (weekday < 1 || weekday > 7 || startSection < 1 || endSection < startSection) {
            throw new IllegalArgumentException("Invalid weekday/section range");
        }
        // Teachers are deliberately absent. Changing a teacher does not change time/place identity.
        return hash("schedule-v1", offeringKey, mask, Integer.toString(weekday),
                Integer.toString(startSection), Integer.toString(endSection), trim(location));
    }

    /** Source-side teacher identity only; it never implies an employee number or account binding. */
    public static String teacher(String namespace, String scopeCode, String displayName) {
        return hash("teacher-v1", trim(namespace), trim(scopeCode), trim(displayName));
    }

    /** SHA-256 of the exact supplied bytes, used for files and retained source-row JSON. */
    public static String contentSha256(byte[] content) {
        Objects.requireNonNull(content, "content");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String trim(String value) { return Objects.requireNonNull(value).trim(); }

    private static String hash(String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                // Length framing prevents ambiguous concatenations, including delimiter characters.
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
