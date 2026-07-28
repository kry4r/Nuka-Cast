package com.nukacast.app.tvbox.model;

import java.util.UUID;

public final class ConfigSource {
    public static final String KIND_SINGLE = "single";
    public static final String KIND_WAREHOUSE = "warehouse";

    public String id;
    public String name;
    public String url;
    public String kind = KIND_SINGLE;
    public String parentId = "";
    public boolean enabled = true;
    public String contentHash = "";
    public long updatedAt;
    public int siteCount;
    public int searchableSiteCount;
    public int liveCount;
    public long latencyMs;
    public String error = "";
    public String searchError = "";

    public ConfigSource() {}

    public ConfigSource(String name, String url) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.url = url;
    }

    public boolean isWarehouse() {
        return KIND_WAREHOUSE.equals(kind);
    }

    public boolean isChild() {
        return parentId != null && !parentId.isEmpty();
    }
}
