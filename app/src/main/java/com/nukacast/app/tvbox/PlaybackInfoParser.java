package com.nukacast.app.tvbox;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.nukacast.app.tvbox.model.PlaybackInfo;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

final class PlaybackInfoParser {
    private static final Type HEADER_MAP = new TypeToken<Map<String, String>>() {}.getType();

    private PlaybackInfoParser() {}

    static PlaybackInfo parse(String response, String fallbackUrl) {
        PlaybackInfo info = new PlaybackInfo();
        info.url = fallbackUrl == null ? "" : fallbackUrl;
        if (response != null && !response.trim().isEmpty()) {
            JsonElement root = new JsonParser().parse(response);
            if (root.isJsonPrimitive()) {
                info.url = root.getAsString();
            } else if (root.isJsonObject()) {
                JsonObject object = root.getAsJsonObject();
                info.url = string(object, "url", "playUrl", "data", info.url);
                info.parse = integer(object, "parse", 0);
                JsonElement header = object.get("header");
                if (header == null) header = object.get("headers");
                if (header != null) {
                    try {
                        if (header.isJsonObject()) info.headers = new Gson().fromJson(header, HEADER_MAP);
                        else if (header.isJsonPrimitive() && header.getAsString().trim().startsWith("{")) {
                            info.headers = new Gson().fromJson(header.getAsString(), HEADER_MAP);
                        }
                    } catch (RuntimeException ignored) {
                        info.headers = new LinkedHashMap<String, String>();
                    }
                }
            }
        }
        info.direct = info.parse == 0 || isDirectMedia(info.url);
        return info;
    }

    static boolean isDirectMedia(String url) {
        if (url == null) return false;
        String value = url.toLowerCase(Locale.US);
        return value.startsWith("file://") || value.contains(".m3u8") || value.contains(".mp4")
                || value.contains(".mkv") || value.contains(".ts") || value.contains(".flv")
                || value.contains(".mpd") || value.contains(".webm") || value.contains(".m4a");
    }

    private static String string(JsonObject object, String first, String second, String third,
                                 String fallback) {
        for (String key : new String[] { first, second, third }) {
            JsonElement value = object.get(key);
            if (value != null && value.isJsonPrimitive()) return value.getAsString();
        }
        return fallback;
    }

    private static int integer(JsonObject object, String key, int fallback) {
        try {
            JsonElement value = object.get(key);
            return value == null ? fallback : value.getAsInt();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
