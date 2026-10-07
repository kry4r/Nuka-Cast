package com.nukacast.app.drama;

import java.util.Locale;

/**
 * One rule for "is this URL actually a media file", shared by the CMS parser, the episode model and
 * the play result: {@code /api.php?url=…} style wrapper links look like HTTP URLs but are解析页
 * addresses, and playing them yields a blank screen instead of an error.
 */
public final class DramaPlaybackUrls {
    private DramaPlaybackUrls() {}

    public static boolean isDirectMediaUrl(String url) {
        String value = url == null ? "" : url.trim();
        if (value.isEmpty()) return false;
        int query = value.indexOf('?');
        String base = query >= 0 ? value.substring(0, query) : value;
        int fragment = base.indexOf('#');
        if (fragment >= 0) base = base.substring(0, fragment);
        String lower = base.toLowerCase(Locale.ROOT);
        while (lower.endsWith("/")) lower = lower.substring(0, lower.length() - 1);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false;
        return lower.endsWith(".m3u8") || lower.endsWith(".mp4") || lower.endsWith(".flv");
    }
}
