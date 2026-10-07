package com.nukacast.app.drama.model;

public final class DramaProviderConfig {
    /** Metadata-only catalog (vote style: /api/search + /api/drama). */
    public static final String KIND_VOTE_CATALOG = "vote.catalog";
    /** MACCMS JSON API (api.php/provide/vod) that yields short-drama episodes with direct m3u8. */
    public static final String KIND_CMS_DRAMA = "cms.drama";
    /** Site scraping for sites that publish player_aaaa on their play pages. */
    public static final String KIND_WEB_DRAMA = "web.drama";

    public String id = "";
    public String name = "";
    public String baseUrl = "";
    public String kind = KIND_VOTE_CATALOG;
    public boolean builtin;
    public boolean enabled = true;
    public String error = "";
    public long updatedAt;
    /** CMS class id that holds short dramas; empty means the whole site. */
    public String categoryId = "";
    /** Referer sent with direct playback requests; empty means none. */
    public String referer = "";
    /** Note shown in the source picker (kept short, never a promise). */
    public String note = "";

    public String host() {
        if (baseUrl == null) return "";
        String value = baseUrl;
        int scheme = value.indexOf("://");
        if (scheme >= 0) value = value.substring(scheme + 3);
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        return value;
    }

    public boolean canPlayDirectly() {
        return KIND_CMS_DRAMA.equals(kind) || KIND_WEB_DRAMA.equals(kind);
    }

    public String kindLabel() {
        if (KIND_CMS_DRAMA.equals(kind)) return "CMS 直连";
        if (KIND_WEB_DRAMA.equals(kind)) return "网页解析";
        return "资料目录";
    }
}
