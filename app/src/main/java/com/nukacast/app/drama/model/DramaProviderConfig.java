package com.nukacast.app.drama.model;

public final class DramaProviderConfig {
    public static final String KIND_VOTE_CATALOG = "vote.catalog";

    public String id = "";
    public String name = "";
    public String baseUrl = "";
    public String kind = KIND_VOTE_CATALOG;
    public boolean builtin;
    public boolean enabled = true;
    public String error = "";
    public long updatedAt;

    public String host() {
        if (baseUrl == null) return "";
        String value = baseUrl;
        int scheme = value.indexOf("://");
        if (scheme >= 0) value = value.substring(scheme + 3);
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        return value;
    }
}
