package com.nukacast.app.tvbox;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.nukacast.app.core.NukaRuntime;
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
            try {
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
            } catch (RuntimeException ignored) {
                // HTML parser pages are handled by the WebView sniffing fallback.
            }
        }
        info.url = normalizeProxyUrl(info.url);
        info.direct = info.parse == 0 && isHttpOrFile(info.url) || isDirectMedia(info.url);
        return info;
    }

    static PlaybackInfo episode(String url) {
        PlaybackInfo info = new PlaybackInfo();
        info.url = url == null ? "" : url.trim();
        info.direct = isDirectMedia(info.url) || info.url.startsWith("file://");
        info.parse = info.direct ? 0 : 1;
        return info;
    }

    static boolean isDirectMedia(String url) {
        if (url == null) return false;
        String value = url.toLowerCase(Locale.US);
        if (value.startsWith("file://")) return true;
        if (!isHttpOrFile(value)) return false;
        int query = value.indexOf('?');
        String path = query < 0 ? value : value.substring(0, query);
        int fragment = path.indexOf('#');
        if (fragment >= 0) path = path.substring(0, fragment);
        return path.endsWith(".m3u8") || path.endsWith(".mp4") || path.endsWith(".mkv")
                || path.endsWith(".ts") || path.endsWith(".flv") || path.endsWith(".mpd")
                || path.endsWith(".webm") || path.endsWith(".m4a") || path.endsWith(".aac");
    }

    static boolean isSpiderProxy(String url) {
        return url != null && url.startsWith(
                "http://127.0.0.1:" + NukaRuntime.CONTROL_PORT + "/proxy?");
    }

    private static String normalizeProxyUrl(String url) {
        if (url == null || !url.startsWith("proxy://")) return url == null ? "" : url;
        String query = url.substring("proxy://".length());
        return "http://127.0.0.1:" + NukaRuntime.CONTROL_PORT + "/proxy?" + query;
    }

    private static boolean isHttpOrFile(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://")
                || url.startsWith("file://"));
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
