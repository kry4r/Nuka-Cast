package com.nukacast.app.tvbox.model;

public final class SearchItem {
    public String siteKey = "";
    public String siteName = "";
    public String sourceId = "";
    public String vodId = "";
    public String name = "";
    public String poster = "";
    public String remarks = "";
    public String year = "";
    public String area = "";
    public String typeName = "";
    public String actor = "";
    public String director = "";
    public String score = "";
    public String plot = "";

    public String dedupeKey() {
        String normalized = name == null ? "" : name.toLowerCase().replaceAll("[^\\p{L}\\p{N}]", "");
        return normalized + "|" + (year == null ? "" : year);
    }
}
