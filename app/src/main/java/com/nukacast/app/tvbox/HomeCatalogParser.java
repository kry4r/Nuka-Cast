package com.nukacast.app.tvbox;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nukacast.app.tvbox.model.SearchItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class HomeCatalogParser {
    private static final Gson GSON = new Gson();

    private HomeCatalogParser() {}

    public static List<SearchItem> parse(String body, String sourceId, String siteKey, String siteName) {
        if (body == null || body.trim().isEmpty()) return Collections.emptyList();
        JsonElement root = GSON.fromJson(body, JsonElement.class);
        JsonArray list = findList(root);
        if (list == null) return Collections.emptyList();

        List<SearchItem> result = new ArrayList<SearchItem>();
        for (JsonElement element : list) {
            if (!element.isJsonObject()) continue;
            JsonObject value = element.getAsJsonObject();
            SearchItem item = new SearchItem();
            item.sourceId = safe(sourceId);
            item.siteKey = safe(siteKey);
            item.siteName = safe(siteName);
            item.vodId = string(value, "vod_id", "id");
            item.name = string(value, "vod_name", "name", "title");
            item.poster = string(value, "vod_pic", "pic", "cover");
            item.remarks = string(value, "vod_remarks", "remarks", "note");
            item.year = string(value, "vod_year", "year");
            item.area = string(value, "vod_area", "area");
            item.typeName = string(value, "type_name", "vod_class", "type");
            item.actor = string(value, "vod_actor", "actor");
            item.director = string(value, "vod_director", "director");
            item.score = string(value, "vod_score", "score");
            item.plot = string(value, "vod_content", "content", "desc");
            if (!item.name.isEmpty() && !item.vodId.isEmpty()) result.add(item);
        }
        return result;
    }

    private static JsonArray findList(JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonArray()) return element.getAsJsonArray();
        if (!element.isJsonObject()) return null;
        JsonObject object = element.getAsJsonObject();
        for (String key : new String[] {"list", "data", "vod_list"}) {
            JsonElement value = object.get(key);
            if (value == null || value.isJsonNull()) continue;
            if (value.isJsonArray()) return value.getAsJsonArray();
            JsonArray nested = findList(value);
            if (nested != null) return nested;
        }
        return null;
    }

    private static String string(JsonObject object, String... keys) {
        for (String key : keys) {
            JsonElement value = object.get(key);
            if (value != null && !value.isJsonNull() && value.isJsonPrimitive()) {
                return value.getAsString();
            }
        }
        return "";
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
