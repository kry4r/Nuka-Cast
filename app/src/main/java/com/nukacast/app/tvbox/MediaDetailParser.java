package com.nukacast.app.tvbox;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nukacast.app.tvbox.model.MediaDetail;

public final class MediaDetailParser {
    private MediaDetailParser() {}

    public static MediaDetail parse(String response, String siteKey, String siteName) {
        JsonElement root = new JsonParser().parse(response);
        JsonObject value = firstItem(root);
        if (value == null) throw new IllegalArgumentException("详情响应没有影片数据");
        MediaDetail detail = new MediaDetail();
        detail.siteKey = safe(siteKey);
        detail.siteName = safe(siteName);
        detail.vodId = string(value, "vod_id", "id");
        detail.name = string(value, "vod_name", "name", "title");
        detail.poster = string(value, "vod_pic", "pic", "cover");
        detail.remarks = string(value, "vod_remarks", "remarks", "note");
        detail.year = string(value, "vod_year", "year");
        detail.area = string(value, "vod_area", "area");
        detail.typeName = string(value, "type_name", "vod_class", "type");
        detail.actor = string(value, "vod_actor", "actor");
        detail.director = string(value, "vod_director", "director");
        detail.score = string(value, "vod_score", "score");
        detail.plot = string(value, "vod_content", "content", "desc");
        parsePlaySources(detail, string(value, "vod_play_from", "playFrom"),
                string(value, "vod_play_url", "playUrl"));
        return detail;
    }

    private static void parsePlaySources(MediaDetail detail, String from, String urls) {
        String[] names = from.isEmpty() ? new String[0] : from.split("\\$\\$\\$", -1);
        String[] lines = urls.isEmpty() ? new String[0] : urls.split("\\$\\$\\$", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().isEmpty()) continue;
            MediaDetail.PlaySource source = new MediaDetail.PlaySource();
            source.name = i < names.length && !names[i].trim().isEmpty()
                    ? names[i].trim() : "线路 " + (i + 1);
            String[] episodes = lines[i].split("#");
            for (int episodeIndex = 0; episodeIndex < episodes.length; episodeIndex++) {
                String encoded = episodes[episodeIndex].trim();
                if (encoded.isEmpty()) continue;
                int separator = encoded.indexOf('$');
                MediaDetail.Episode episode = new MediaDetail.Episode();
                if (separator > 0) {
                    episode.name = encoded.substring(0, separator).trim();
                    episode.id = encoded.substring(separator + 1).trim();
                } else {
                    episode.name = "第 " + (episodeIndex + 1) + " 集";
                    episode.id = encoded;
                }
                if (!episode.id.isEmpty()) source.episodes.add(episode);
            }
            if (!source.episodes.isEmpty()) detail.playSources.add(source);
        }
    }

    private static JsonObject firstItem(JsonElement root) {
        if (root == null || root.isJsonNull()) return null;
        if (root.isJsonArray()) {
            JsonArray array = root.getAsJsonArray();
            return array.size() > 0 && array.get(0).isJsonObject() ? array.get(0).getAsJsonObject() : null;
        }
        if (!root.isJsonObject()) return null;
        JsonObject object = root.getAsJsonObject();
        for (String key : new String[] { "list", "data", "vod_list" }) {
            JsonElement nested = object.get(key);
            if (nested != null && nested.isJsonArray() && nested.getAsJsonArray().size() > 0
                    && nested.getAsJsonArray().get(0).isJsonObject()) {
                return nested.getAsJsonArray().get(0).getAsJsonObject();
            }
            if (nested != null && nested.isJsonObject()) return firstItem(nested);
        }
        return object;
    }

    private static String string(JsonObject object, String... keys) {
        for (String key : keys) {
            JsonElement value = object.get(key);
            if (value != null && value.isJsonPrimitive()) return value.getAsString();
        }
        return "";
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
