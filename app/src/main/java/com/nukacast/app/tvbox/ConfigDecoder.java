package com.nukacast.app.tvbox;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.nukacast.app.tvbox.model.TvBoxConfig;

import java.io.StringReader;
import java.nio.charset.Charset;

import okio.ByteString;

public final class ConfigDecoder {
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private final Gson gson;

    public ConfigDecoder(Gson gson) {
        this.gson = gson;
    }

    public TvBoxConfig decode(String content) {
        String json = unwrap(content);
        JsonReader reader = new JsonReader(new StringReader(json));
        reader.setLenient(true);
        TvBoxConfig config = gson.fromJson(reader, TvBoxConfig.class);
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
        return config;
    }

    public JsonObject decodeTree(String content) {
        return gson.fromJson(unwrap(content), JsonObject.class);
    }

    String unwrap(String content) {
        if (content == null) {
            throw new IllegalArgumentException("配置响应为空");
        }
        String trimmed = content.trim();
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
            return decoded.string(UTF_8).trim();
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("TVBox Base64 配置解码失败", error);
        }
    }
}
