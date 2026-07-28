package com.nukacast.app.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nukacast.app.live.model.LiveCatalog;
import com.nukacast.app.live.model.EpgSchedule;
import com.nukacast.app.live.model.LiveSourceInfo;
import com.nukacast.app.net.HttpStack;
import com.nukacast.app.net.ResponseBodies;
import com.nukacast.app.tvbox.TvBoxRepository;
import com.nukacast.app.tvbox.model.TvBoxConfig;
import com.nukacast.app.util.Digests;

import java.nio.charset.Charset;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

import okhttp3.Request;
import okhttp3.Response;

public final class LiveService {
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final int MAX_LIVE_BYTES = 4 * 1024 * 1024;
    private static final long CACHE_MS = 10L * 60L * 1000L;
    private final TvBoxRepository repository;
    private final Map<String, CacheEntry> cache = new HashMap<String, CacheEntry>();

    public LiveService(TvBoxRepository repository) {
        this.repository = repository;
    }

    public List<LiveSourceInfo> sources() {
        List<LiveSourceInfo> result = new ArrayList<LiveSourceInfo>();
        for (TvBoxConfig.LiveSource source : repository.getLiveSources()) {
            result.add(info(source));
        }
        return result;
    }

    public synchronized LiveCatalog catalog(String id) throws Exception {
        CacheEntry cached = cache.get(id);
        if (cached != null && System.currentTimeMillis() - cached.createdAt < CACHE_MS) {
            return cached.catalog;
        }
        TvBoxConfig.LiveSource source = findSource(id);
        LiveCatalog catalog = source.channels != null && !source.channels.isJsonNull()
                ? embedded(source.channels, source.name) : download(source);
        LiveSourceInfo sourceInfo = info(source);
        catalog.sourceId = sourceInfo.id;
        catalog.sourceName = source.name == null ? "直播" : source.name;
        finish(source, catalog);
        cache.put(id, new CacheEntry(catalog));
        return catalog;
    }

    public LiveCatalog.Channel channel(String sourceId, String channelId) throws Exception {
        LiveCatalog catalog = catalog(sourceId);
        for (LiveCatalog.Group group : catalog.groups) {
            for (LiveCatalog.Channel channel : group.channels) {
                if (channelId.equals(channel.id)) return channel;
            }
        }
        throw new IllegalArgumentException("找不到直播频道");
    }

    public EpgSchedule epg(String sourceId, String channelId, String requestedDate) throws Exception {
        TvBoxConfig.LiveSource source = findSource(sourceId);
        LiveCatalog.Channel channel = channel(sourceId, channelId);
        String date = requestedDate == null || requestedDate.trim().isEmpty()
                ? new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date())
                : requestedDate.trim();
        if (source.epg == null || source.epg.trim().isEmpty()) {
            EpgSchedule empty = new EpgSchedule();
            empty.channel = channel.name;
            empty.date = date;
            return empty;
        }
        String url = source.epg
                .replace("{name}", URLEncoder.encode(channel.epgId, "UTF-8"))
                .replace("{date}", URLEncoder.encode(date, "UTF-8"));
        Request request = new Request.Builder().url(url)
                .header("User-Agent", source.ua == null || source.ua.isEmpty()
                        ? "NukaCast/0.1 EPG" : source.ua)
                .build();
        try (Response response = HttpStack.client().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("节目单 HTTP " + response.code());
            }
            return EpgParser.parse(ResponseBodies.string(
                    response.body(), MAX_LIVE_BYTES, UTF_8), channel.epgId, date);
        }
    }

    public synchronized void clearCache() {
        cache.clear();
    }

    private LiveCatalog download(TvBoxConfig.LiveSource source) throws Exception {
        if (source.url == null || (!source.url.startsWith("http://") && !source.url.startsWith("https://"))) {
            throw new IllegalArgumentException("直播源没有可加载的清单地址");
        }
        Request.Builder request = new Request.Builder().url(source.url)
                .header("User-Agent", source.ua == null || source.ua.isEmpty()
                        ? "NukaCast/0.1 Live" : source.ua);
        try (Response response = HttpStack.client().newCall(request.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("直播清单 HTTP " + response.code());
            }
            return LivePlaylistParser.parse(ResponseBodies.string(
                    response.body(), MAX_LIVE_BYTES, UTF_8));
        }
    }

    private static LiveCatalog embedded(JsonElement channels, String fallbackGroup) {
        LiveCatalog catalog = new LiveCatalog();
        Map<String, LiveCatalog.Group> groups = new LinkedHashMap<String, LiveCatalog.Group>();
        if (channels.isJsonArray()) {
            for (JsonElement element : channels.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject object = element.getAsJsonObject();
                if (object.has("channels") && object.get("channels").isJsonArray()) {
                    String groupName = string(object, "name", fallbackGroup);
                    addChannels(groups, groupName, object.getAsJsonArray("channels"));
                } else {
                    String groupName = string(object, "group", fallbackGroup);
                    addChannel(groups, groupName, object);
                }
            }
        } else if (channels.isJsonObject()) {
            JsonObject object = channels.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (entry.getValue().isJsonArray()) addChannels(groups, entry.getKey(), entry.getValue().getAsJsonArray());
            }
        }
        catalog.groups.addAll(groups.values());
        return catalog;
    }

    private static void addChannels(Map<String, LiveCatalog.Group> groups, String groupName, JsonArray channels) {
        for (JsonElement item : channels) {
            if (item.isJsonObject()) addChannel(groups, groupName, item.getAsJsonObject());
        }
    }

    private static void addChannel(Map<String, LiveCatalog.Group> groups, String groupName, JsonObject object) {
        LiveCatalog.Channel channel = new LiveCatalog.Channel();
        channel.name = string(object, "name", "频道");
        channel.epgId = string(object, "tvg-id", channel.name);
        channel.logo = string(object, "logo", "");
        channel.group = groupName == null || groupName.isEmpty() ? "未分组" : groupName;
        JsonElement urls = object.get("urls");
        if (urls == null) urls = object.get("url");
        if (urls != null && urls.isJsonArray()) {
            for (JsonElement url : urls.getAsJsonArray()) if (url.isJsonPrimitive()) channel.urls.add(url.getAsString());
        } else if (urls != null && urls.isJsonPrimitive()) {
            for (String url : urls.getAsString().split("#")) if (!url.trim().isEmpty()) channel.urls.add(url.trim());
        }
        if (!channel.urls.isEmpty()) group(groups, channel.group).channels.add(channel);
    }

    private static LiveCatalog.Group group(Map<String, LiveCatalog.Group> groups, String name) {
        LiveCatalog.Group group = groups.get(name);
        if (group == null) {
            group = new LiveCatalog.Group();
            group.name = name;
            groups.put(name, group);
        }
        return group;
    }

    private static void finish(TvBoxConfig.LiveSource source, LiveCatalog catalog) {
        int index = 0;
        for (LiveCatalog.Group group : catalog.groups) {
            for (LiveCatalog.Channel channel : group.channels) {
                channel.id = Digests.sha256((catalog.sourceId + "|" + channel.name + "|" + index++)
                        .getBytes(UTF_8)).substring(0, 16);
                if (channel.logo.isEmpty() && source.logo != null) {
                    channel.logo = source.logo.replace("{name}", channel.epgId);
                }
                if (source.ua != null && !source.ua.isEmpty() && !channel.headers.containsKey("User-Agent")) {
                    channel.headers.put("User-Agent", source.ua);
                }
            }
        }
    }

    private TvBoxConfig.LiveSource findSource(String id) {
        for (TvBoxConfig.LiveSource source : repository.getLiveSources()) {
            if (info(source).id.equals(id)) return source;
        }
        throw new IllegalArgumentException("找不到直播源");
    }

    private static LiveSourceInfo info(TvBoxConfig.LiveSource source) {
        LiveSourceInfo info = new LiveSourceInfo();
        info.sourceId = source.sourceId == null ? "" : source.sourceId;
        info.name = source.name == null ? "直播" : source.name;
        info.url = source.url == null ? "" : source.url;
        info.epg = source.epg == null ? "" : source.epg;
        info.logo = source.logo == null ? "" : source.logo;
        info.id = Digests.sha256((info.sourceId + "|" + info.name + "|" + info.url)
                .getBytes(UTF_8)).substring(0, 16);
        return info;
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    private static final class CacheEntry {
        final LiveCatalog catalog;
        final long createdAt = System.currentTimeMillis();

        CacheEntry(LiveCatalog catalog) { this.catalog = catalog; }
    }
}
