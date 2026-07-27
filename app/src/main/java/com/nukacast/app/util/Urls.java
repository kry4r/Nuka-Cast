package com.nukacast.app.util;

import java.net.URI;

public final class Urls {
    private Urls() {}

    public static String resolve(String base, String candidate) {
        if (candidate == null || candidate.trim().isEmpty()) {
            return "";
        }
        String value = candidate.trim();
        int digestSeparator = value.indexOf(';');
        String suffix = "";
        if (digestSeparator > 0) {
            suffix = value.substring(digestSeparator);
            value = value.substring(0, digestSeparator);
        }
        if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("file://")
                || value.startsWith("assets://")) {
            return value + suffix;
        }
        try {
            return URI.create(base).resolve(value).toString() + suffix;
        } catch (RuntimeException ignored) {
            return candidate;
        }
    }
}
