package com.aijiaoxue.common.net;

import java.net.URI;

/** Strict validation for externally exposed HTTPS locators. */
public final class SafeLocator {
    private SafeLocator() {}

    public static boolean isSafeHttps(String value) {
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl)) return false;
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && !uri.getHost().isBlank()
                    && uri.getRawUserInfo() == null;
        } catch (RuntimeException | java.net.URISyntaxException invalid) {
            return false;
        }
    }
}
