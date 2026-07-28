package com.nukacast.app.tvbox.search;

import android.util.Xml;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nukacast.app.net.HttpStack;
import com.nukacast.app.net.ResponseBodies;
import com.nukacast.app.tvbox.model.SearchItem;
import com.nukacast.app.tvbox.model.SearchQuery;
import com.nukacast.app.tvbox.model.TvBoxConfig;

import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

public final class CmsSiteSearcher implements SiteSearcher {
    private static final int MAX_CMS_BYTES = 4 * 1024 * 1024;
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private final Gson gson = new Gson();

    @Override
    public List<SearchItem> search(TvBoxConfig.Site site, SearchQuery query) throws Exception {
        HttpUrl api = HttpUrl.parse(site.api);
        if (api == null) {
            throw new IllegalArgumentException("无效 CMS 地址");
        }
        HttpUrl url = api.newBuilder()
                .setQueryParameter("ac", site.type == 0 ? "videolist" : "detail")
                .setQueryParameter("wd", query.keyword)
                .setQueryParameter("pg", String.valueOf(Math.max(1, query.page)))
                .build();
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 4.2.2; NukaCast) AppleWebKit/537.36")
                .header("Accept", "application/json,application/xml,text/xml,text/plain,*/*")
                .build();
        String body;
        try (Response response = HttpStack.client().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("HTTP " + response.code());
            }
            body = ResponseBodies.string(response.body(), MAX_CMS_BYTES, UTF_8);
        }
        List<SearchItem> items = body.trim().startsWith("<") ? parseXml(body) : parseJson(body);
        for (SearchItem item : items) {
            item.siteKey = safe(site.key);
            item.siteName = safe(site.name);
            item.sourceId = safe(site.sourceId);
        }
        return filter(items, query);
    }

    private List<SearchItem> parseJson(String body) {
        JsonElement root = gson.fromJson(body, JsonElement.class);
        if (root == null || !root.isJsonObject()) {
            return Collections.emptyList();
        }
        JsonObject object = root.getAsJsonObject();
        JsonArray list = array(object, "list", "data", "vod_list");
        if (list == null) {
            return Collections.emptyList();
        }
        List<SearchItem> items = new ArrayList<SearchItem>();
        for (JsonElement element : list) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject value = element.getAsJsonObject();
            SearchItem item = new SearchItem();
            item.vodId = string(value, "vod_id", "id");
            item.name = string(value, "vod_name", "name", "title");
            item.poster = string(value, "vod_pic", "pic", "cover");
            item.remarks = string(value, "vod_remarks", "note", "remarks");
            item.year = string(value, "vod_year", "year");
            item.area = string(value, "vod_area", "area");
            item.typeName = string(value, "type_name", "vod_class", "type");
            item.actor = string(value, "vod_actor", "actor");
            item.director = string(value, "vod_director", "director");
            item.score = string(value, "vod_score", "score");
            item.plot = string(value, "vod_content", "content", "desc");
            if (!item.name.isEmpty()) {
                items.add(item);
            }
        }
        return items;
    }

    private List<SearchItem> parseXml(String body) throws Exception {
        List<SearchItem> items = new ArrayList<SearchItem>();
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new StringReader(body));
        SearchItem current = null;
        String tag = "";
        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                tag = parser.getName().toLowerCase(Locale.ROOT);
                if ("video".equals(tag)) {
                    current = new SearchItem();
                }
            } else if (event == XmlPullParser.TEXT && current != null) {
                String value = parser.getText() == null ? "" : parser.getText().trim();
                if (!value.isEmpty()) {
                    assign(current, tag, value);
                }
            } else if (event == XmlPullParser.END_TAG) {
                if ("video".equalsIgnoreCase(parser.getName()) && current != null) {
                    if (!current.name.isEmpty()) {
                        items.add(current);
                    }
                    current = null;
                }
                tag = "";
            }
        }
        return items;
    }

    private static void assign(SearchItem item, String tag, String value) {
        if ("id".equals(tag) || "vod_id".equals(tag)) item.vodId = value;
        else if ("name".equals(tag) || "vod_name".equals(tag)) item.name = value;
        else if ("pic".equals(tag) || "vod_pic".equals(tag)) item.poster = value;
        else if ("note".equals(tag) || "vod_remarks".equals(tag)) item.remarks = value;
        else if ("year".equals(tag) || "vod_year".equals(tag)) item.year = value;
        else if ("area".equals(tag) || "vod_area".equals(tag)) item.area = value;
        else if ("type".equals(tag) || "type_name".equals(tag)) item.typeName = value;
        else if ("actor".equals(tag) || "vod_actor".equals(tag)) item.actor = value;
        else if ("director".equals(tag) || "vod_director".equals(tag)) item.director = value;
        else if ("des".equals(tag) || "vod_content".equals(tag)) item.plot = value;
    }

    private static List<SearchItem> filter(List<SearchItem> items, SearchQuery query) {
        List<SearchItem> result = new ArrayList<SearchItem>();
        for (SearchItem item : items) {
            if (!matches(item.typeName, query.contentType)
                    || !matches(item.year, query.year)
                    || !matches(item.area, query.region)) {
                continue;
            }
            result.add(item);
        }
        return result;
    }

    private static boolean matches(String actual, String expected) {
        return expected == null || expected.isEmpty()
                || (actual != null && actual.toLowerCase(Locale.ROOT)
                .contains(expected.toLowerCase(Locale.ROOT)));
    }

    private static JsonArray array(JsonObject object, String... keys) {
        for (String key : keys) {
            JsonElement value = object.get(key);
            if (value != null && value.isJsonArray()) {
                return value.getAsJsonArray();
            }
            if (value != null && value.isJsonObject()) {
                JsonArray nested = array(value.getAsJsonObject(), "list", "data");
                if (nested != null) {
                    return nested;
                }
            }
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

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
