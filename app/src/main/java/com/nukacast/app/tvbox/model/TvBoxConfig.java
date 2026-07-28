package com.nukacast.app.tvbox.model;

import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

public final class TvBoxConfig {
    public transient String sourceId;
    public transient String baseUrl;
    public String spider;
    public List<Site> sites = new ArrayList<Site>();
    public List<LiveSource> lives = new ArrayList<LiveSource>();
    public List<ParseEndpoint> parses = new ArrayList<ParseEndpoint>();
    public List<String> flags = new ArrayList<String>();
    public JsonElement ijk;
    public JsonElement ads;

    public static final class Site {
        public String key;
        public String name;
        public int type;
        public String api;
        public int searchable;
        public int quickSearch;
        public int filterable;
        public JsonElement ext;
        public String jar;
        public String playerUrl;
        public int playerType = -1;
        public JsonElement categories;
        public transient String sourceId;
        public transient String sourceName;
        public transient String configBaseUrl;
        public transient String globalSpider;

        public boolean canSearch() {
            return searchable == 1;
        }

        public boolean canFilter() {
            return filterable == 1;
        }

        public String extension() {
            if (ext == null || ext.isJsonNull()) return "";
            return ext.isJsonPrimitive() ? ext.getAsString() : ext.toString();
        }
    }

    public static final class LiveSource {
        public String name;
        public String url;
        public String ua;
        public String epg;
        public String logo;
        public int type;
        public JsonElement channels;
        @SerializedName("playerType") public int playerType = -1;
        public transient String sourceId;
    }

    public static final class ParseEndpoint {
        public String name;
        public String url;
        public int type;
        public JsonElement ext;
        public JsonElement header;
    }
}
