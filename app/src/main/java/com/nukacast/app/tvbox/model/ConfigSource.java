package com.nukacast.app.tvbox.model;

import java.util.UUID;

public final class ConfigSource {
    public String id;
    public String name;
    public String url;
    public boolean enabled = true;
    public String contentHash = "";
    public long updatedAt;
    public String error = "";

    public ConfigSource() {}

    public ConfigSource(String name, String url) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.url = url;
    }
}
