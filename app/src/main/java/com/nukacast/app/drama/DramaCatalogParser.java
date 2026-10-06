package com.nukacast.app.drama;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nukacast.app.drama.model.DramaDetail;
import com.nukacast.app.drama.model.DramaItem;
import com.nukacast.app.drama.model.DramaSearchResult;

import java.util.List;

/**
 * Pure JSON mapping for the vote-style short-drama catalog contract:
 *
 * <pre>
 * GET /api/search?q=关键词  -> { "items": [...], "total": 213, "warning": "..." }
 * GET /api/drama?id=...     -> { "ok": true, "item": {...} }
 * GET /api/related?id=&title= -> { "items": [...] }
 * </pre>
 *
 * <p>Ids are read through {@link JsonElement#getAsString()} so Gson's lazily parsed number keeps
 * the original 19-digit literal instead of a double. The parser never fabricates episodes or
 * playback urls: this contract has none.
 */
public final class DramaCatalogParser {
    static final int MAX_ITEMS = 200;
    private static final String[] ITEM_ARRAYS = {"items", "data", "list"};

    private DramaCatalogParser() {}

    public static DramaSearchResult parseSearch(String json, String providerId, String keyword)
            throws DramaException {
        JsonObject root = object(json);
        requireOk(root);
        DramaSearchResult result = new DramaSearchResult();
        result.providerId = safe(providerId);
        result.keyword = safe(keyword);
        result.total = integer(root, "total", -1);
        result.warning = text(root, "warning");
        JsonArray items = array(root, ITEM_ARRAYS);
        if (items == null) {
            throw new DramaException("parse_error", "目录响应缺少 items 数组");
        }
        for (JsonElement element : items) {
            DramaItem parsed = item(element, providerId);
            if (parsed != null) result.items.add(parsed);
            if (result.items.size() >= MAX_ITEMS) {
                result.partial = true;
                break;
            }
        }
        return result;
    }

    public static DramaDetail parseDetail(String json, String providerId, String dramaId)
            throws DramaException {
        JsonObject root = object(json);
        requireOk(root);
        JsonElement element = root.get("item");
        DramaItem parsed = element != null && element.isJsonObject()
                ? item(element, providerId) : null;
        if (parsed == null) {
            // Tolerate providers that return the bare object or a single-element array.
            if (root.has("id") || root.has("title")) parsed = item(root, providerId);
        }
        if (parsed == null) throw new DramaException("parse_error", "详情响应缺少 item");
        if (parsed.dramaId.isEmpty()) parsed.dramaId = safe(dramaId);
        DramaDetail detail = new DramaDetail();
        detail.item = parsed;
        return detail;
    }

    public static int parseRelated(String json, String providerId, List<DramaItem> output)
            throws DramaException {
        JsonObject root = object(json);
        requireOk(root);
        JsonArray items = array(root, ITEM_ARRAYS);
        if (items == null) return 0;
        int count = 0;
        for (JsonElement element : items) {
            DramaItem parsed = item(element, providerId);
            if (parsed == null) continue;
            output.add(parsed);
            count++;
            if (count >= MAX_ITEMS) break;
        }
        return count;
    }

    static DramaItem item(JsonElement element, String providerId) {
        if (element == null || !element.isJsonObject()) return null;
        JsonObject object = element.getAsJsonObject();
        DramaItem item = new DramaItem();
        item.providerId = safe(providerId);
        item.dramaId = text(object, "id");
        item.title = text(object, "title");
        if (item.title.isEmpty()) item.title = text(object, "name");
        item.cover = text(object, "cover");
        if (item.cover.isEmpty()) item.cover = text(object, "pic");
        item.intro = text(object, "intro");
        if (item.intro.isEmpty()) item.intro = text(object, "desc");
        item.remark = text(object, "remark");
        if (item.remark.isEmpty()) item.remark = text(object, "remarks");
        item.category = text(object, "category");
        item.heat = text(object, "heat");
        item.status = text(object, "status");
        item.episodeCount = integer(object, "episodeCount", 0);
        JsonArray tags = array(object, new String[] {"tags", "category_names"});
        if (tags != null) {
            for (JsonElement tag : tags) {
                if (tag == null || tag.isJsonNull()) continue;
                String value = tag.isJsonPrimitive() ? tag.getAsString() : "";
                if (!value.isEmpty() && !item.tags.contains(value)) item.tags.add(value);
            }
        }
        if (item.dramaId.isEmpty() && item.title.isEmpty()) return null;
        return item;
    }

    private static void requireOk(JsonObject root) throws DramaException {
        JsonElement ok = root.get("ok");
        if (ok != null && ok.isJsonPrimitive() && ok.getAsJsonPrimitive().isBoolean()
                && !ok.getAsBoolean()) {
            String message = text(root, "error");
            if (message.isEmpty()) message = text(root, "message");
            if (message.isEmpty()) message = "目录接口返回 ok=false";
            throw new DramaException("catalog_error", message);
        }
    }

    private static JsonObject object(String json) throws DramaException {
        if (json == null || json.trim().isEmpty()) {
            throw new DramaException("parse_error", "目录响应为空");
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (RuntimeException error) {
            throw new DramaException("parse_error", "目录响应不是有效 JSON", error);
        }
        if (parsed == null || !parsed.isJsonObject()) {
            throw new DramaException("parse_error", "目录响应不是 JSON 对象");
        }
        return parsed.getAsJsonObject();
    }

    private static JsonArray array(JsonObject object, String[] names) {
        for (String name : names) {
            JsonElement element = object.get(name);
            if (element != null && element.isJsonArray()) return element.getAsJsonArray();
        }
        return null;
    }

    static String text(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return "";
        String value = element.getAsString();
        return value == null ? "" : value.trim();
    }

    static int integer(JsonObject object, String key, int fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return fallback;
        try {
            return element.getAsInt();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
