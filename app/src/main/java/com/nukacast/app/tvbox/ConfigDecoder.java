package com.nukacast.app.tvbox;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.nukacast.app.tvbox.model.TvBoxConfig;

import java.io.StringReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import okio.ByteString;

public final class ConfigDecoder {
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private final Gson gson;

    public ConfigDecoder(Gson gson) {
        this.gson = gson;
    }

    public TvBoxConfig decode(String content) {
        Document document = decodeDocument(content);
        if (document.isWarehouse()) {
            throw new IllegalArgumentException("当前地址是多仓目录，需要先展开子仓");
        }
        return document.config;
    }

    public Document decodeDocument(String content) {
        JsonObject root = parseObject(unwrap(content));
        if (root.has("urls") && root.get("urls").isJsonArray()) {
            List<WarehouseEntry> warehouses = warehouseEntries(root.getAsJsonArray("urls"));
            if (warehouses.isEmpty()) throw new IllegalArgumentException("多仓目录没有有效子仓");
            return new Document(null, warehouses);
        }
        if (!root.has("sites") && !root.has("lives") && !root.has("parses")
                && !root.has("spider")) {
            throw new IllegalArgumentException("配置缺少 sites、lives、parses 或 spider");
        }
        TvBoxConfig config = gson.fromJson(root, TvBoxConfig.class);
        initialize(config);
        return new Document(config, Collections.<WarehouseEntry>emptyList());
    }

    private JsonObject parseObject(String json) {
        JsonReader reader = new JsonReader(new StringReader(json));
        reader.setLenient(true);
        JsonElement parsed = gson.fromJson(reader, JsonElement.class);
        if (parsed == null || !parsed.isJsonObject()) {
            throw new IllegalArgumentException("TVBox 配置必须是 JSON 对象");
        }
        return parsed.getAsJsonObject();
    }

    private static void initialize(TvBoxConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("配置内容为空");
        }
        if (config.sites == null) {
            config.sites = new java.util.ArrayList<TvBoxConfig.Site>();
        }
        if (config.lives == null) {
            config.lives = new java.util.ArrayList<TvBoxConfig.LiveSource>();
        }
        if (config.parses == null) {
            config.parses = new java.util.ArrayList<TvBoxConfig.ParseEndpoint>();
        }
        if (config.flags == null) {
            config.flags = new java.util.ArrayList<String>();
        }
    }

    public JsonObject decodeTree(String content) {
        return parseObject(unwrap(content));
    }

    String unwrap(String content) {
        if (content == null) {
            throw new IllegalArgumentException("配置响应为空");
        }
        String trimmed = stripPrefix(content);
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }
        int marker = trimmed.indexOf("**");
        if (marker < 0 || marker + 2 >= trimmed.length()) {
            throw new IllegalArgumentException("无法识别的 TVBox 配置格式");
        }
        try {
            ByteString decoded = ByteString.decodeBase64(trimmed.substring(marker + 2).trim());
            if (decoded == null) {
                throw new IllegalArgumentException("无效 Base64");
            }
            return stripPrefix(decoded.string(UTF_8));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("TVBox Base64 配置解码失败", error);
        }
    }

    private static List<WarehouseEntry> warehouseEntries(JsonArray values) {
        List<WarehouseEntry> entries = new ArrayList<WarehouseEntry>();
        for (JsonElement value : values) {
            if (value == null || !value.isJsonObject()) continue;
            JsonObject object = value.getAsJsonObject();
            String url = string(object, "url");
            if (url.isEmpty()) continue;
            String name = string(object, "name");
            entries.add(new WarehouseEntry(name.isEmpty() ? "TVBox" : name, url));
        }
        return entries;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString().trim();
    }

    private static String stripPrefix(String value) {
        String current = value == null ? "" : value.trim();
        if (!current.isEmpty() && current.charAt(0) == '\ufeff') {
            current = current.substring(1).trim();
        }
        boolean removed;
        do {
            removed = false;
            if (current.startsWith("//")) {
                int newline = current.indexOf('\n');
                current = newline < 0 ? "" : current.substring(newline + 1).trim();
                removed = true;
            } else if (current.startsWith("/*")) {
                int end = current.indexOf("*/", 2);
                if (end < 0) throw new IllegalArgumentException("配置注释未结束");
                current = current.substring(end + 2).trim();
                removed = true;
            }
        } while (removed);
        return current;
    }

    public static final class Document {
        public final TvBoxConfig config;
        public final List<WarehouseEntry> warehouses;

        Document(TvBoxConfig config, List<WarehouseEntry> warehouses) {
            this.config = config;
            this.warehouses = warehouses;
        }

        public boolean isWarehouse() {
            return !warehouses.isEmpty();
        }
    }

    public static final class WarehouseEntry {
        public final String name;
        public final String url;

        WarehouseEntry(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }
}
