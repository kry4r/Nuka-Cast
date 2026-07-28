package com.nukacast.app.util;

import java.security.MessageDigest;
import java.util.Locale;

public final class Digests {
    private Digests() {}

    public static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(content));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    public static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            value.append(String.format(Locale.US, "%02x", item & 0xff));
        }
        return value.toString();
    }
}
