package com.nukacast.app.tvbox.search;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nukacast.app.spider.SpiderManager;
import com.nukacast.app.tvbox.model.SearchItem;
import com.nukacast.app.tvbox.model.SearchQuery;
import com.nukacast.app.tvbox.model.TvBoxConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class SpiderSiteSearcher implements SiteSearcher {
    private final SpiderManager spiderManager;
    private final Gson gson = new Gson();

    public SpiderSiteSearcher(SpiderManager spiderManager) {
        this.spiderManager = spiderManager;
    }

    @Override
    public List<SearchItem> search(TvBoxConfig.Site site, SearchQuery query) throws Exception {
        String response = spiderManager.search(site, query.keyword, query.page);
        JsonElement root = gson.fromJson(response, JsonElement.class);
        if (root == null || !root.isJsonObject()) {
            return Collections.emptyList();
        }
        JsonArray list = root.getAsJsonObject().getAsJsonArray("list");
        if (list == null) {
            return Collections.emptyList();
        }
        List<SearchItem> result = new ArrayList<SearchItem>();
        for (JsonElement element : list) {
            if (!element.isJsonObject()) continue;
            JsonObject value = element.getAsJsonObject();
            SearchItem item = new SearchItem();
            item.siteKey = safe(site.key);
            item.siteName = safe(site.name);
            item.sourceId = safe(site.sourceId);
            item.vodId = string(value, "vod_id", "id");
            item.name = string(value, "vod_name", "name");
            item.poster = string(value, "vod_pic", "pic");
            item.remarks = string(value, "vod_remarks", "remarks", "note");
            item.year = string(value, "vod_year", "year");
            item.area = string(value, "vod_area", "area");
            item.typeName = string(value, "type_name", "vod_class");
            item.actor = string(value, "vod_actor", "actor");
            item.director = string(value, "vod_director", "director");
            item.score = string(value, "vod_score", "score");
            item.plot = string(value, "vod_content", "content");
            if (!item.name.isEmpty() && matches(item, query)) {
                result.add(item);
            }
        }
        return result;
    }

    private static boolean matches(SearchItem item, SearchQuery query) {
        return contains(item.typeName, query.contentType)
                && contains(item.year, query.year)
                && contains(item.area, query.region);
    }

    private static boolean contains(String value, String query) {
        return query == null || query.isEmpty()
                || (value != null && value.toLowerCase(Locale.ROOT)
                .contains(query.toLowerCase(Locale.ROOT)));
    }

    private static String string(JsonObject object, String... keys) {
        for (String key : keys) {
            JsonElement value = object.get(key);
            if (value != null && !value.isJsonNull() && value.isJsonPrimitive()) return value.getAsString();
        }
        return "";
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
