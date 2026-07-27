package com.nukacast.app.storage.model;

public final class StorageMount {
    public static final String TYPE_LOCAL = "local";
    public static final String TYPE_WEBDAV = "webdav";
    public static final String TYPE_SMB = "smb";

    public String id = "";
    public String name = "";
    public String type = TYPE_LOCAL;
    public String uri = "";
    public String username = "";
    public String password = "";
    public boolean enabled = true;
    public long lastScanAt;
    public int fileCount;
    public String error = "";

    public StorageMount publicView() {
        StorageMount value = new StorageMount();
        value.id = safe(id);
        value.name = safe(name);
        value.type = safe(type);
        value.uri = safe(uri);
        value.username = safe(username);
        value.password = "";
        value.enabled = enabled;
        value.lastScanAt = lastScanAt;
        value.fileCount = fileCount;
        value.error = safe(error);
        return value;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
