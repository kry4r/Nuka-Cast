package com.nukacast.app.tvbox.model;

import java.util.Locale;
import java.util.UUID;

/** A user-managed live playlist (m3u / txt) that is independent of any TVBox config. */
public final class LivePlaylist {
    public String id = "";
    public String name = "";
    public String url = "";
    public String epg = "";
    public String logo = "";
    public boolean enabled = true;
    public String error = "";
    public long updatedAt;

    public static LivePlaylist of(String name, String url) {
        LivePlaylist playlist = new LivePlaylist();
        playlist.id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        playlist.name = name == null || name.trim().isEmpty() ? host(url) : name.trim();
        playlist.url = url == null ? "" : url.trim();
        playlist.updatedAt = System.currentTimeMillis();
        return playlist;
    }

    public String host() { return host(url); }

    public boolean looksLikePlaylist() {
        String value = url == null ? "" : url.toLowerCase(Locale.US);
        return value.startsWith("http://") || value.startsWith("https://");
    }

    private static String host(String url) {
        if (url == null || url.isEmpty()) return "直播源";
        String value = url;
        int scheme = value.indexOf("://");
        if (scheme >= 0) value = value.substring(scheme + 3);
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        return value.isEmpty() ? "直播源" : value;
    }
}
