package com.nukacast.app.drama;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nukacast.app.drama.model.DramaDetail;
import com.nukacast.app.drama.model.DramaEpisode;
import com.nukacast.app.drama.model.DramaItem;
import com.nukacast.app.drama.model.DramaProviderConfig;
import com.nukacast.app.drama.model.DramaSearchResult;
import com.nukacast.app.net.HttpStack;
import com.nukacast.app.net.ResponseBodies;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Short-drama adapter for MACCMS (苹果 CMS) style JSON APIs, the interface used by most resource
 * sites:
 *
 * <pre>
 * GET api.php/provide/vod/?ac=detail&amp;t=36&amp;pg=1   (listing; t = short-drama class)
 * GET api.php/provide/vod/?ac=detail&amp;wd=剧名      (search)
 * GET api.php/provide/vod/?ac=detail&amp;ids=123     (detail, includes vod_play_url)
 * </pre>
 *
 * <p>{@code vod_play_url} is {@code 第01集$https://…/index.m3u8#第02集$https://…} and
 * {@code vod_play_from} lists the parallel play lines separated by {@code $$$}. Only lines whose
 * entries are direct media URLs are exposed; anything that needs an external parser is dropped
 * instead of being handed to the player as a broken link.
 */
public final class CmsDramaCatalog implements DramaCatalog {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36";
    private static final Pattern EPISODE_NUMBER = Pattern.compile("(\\d{1,4})");

    private final DramaProviderConfig config;

    public CmsDramaCatalog(DramaProviderConfig config) {
        this.config = config;
    }

    @Override public DramaProviderConfig config() { return config; }

    @Override public DramaSearchResult search(String keyword) throws Exception {
        HttpUrl.Builder url = endpoint().addQueryParameter("ac", "detail")
                .addQueryParameter("wd", safe(keyword))
                .addQueryParameter("pg", "1");
        if (!safe(config.categoryId).isEmpty()) {
            url.addQueryParameter("t", config.categoryId);
        }
        DramaSearchResult result = parseItems(get(url.build()), keyword);
        result.providerName = config.name;
        return result;
    }

    @Override public DramaSearchResult browse(String categoryId, int page) throws Exception {
        String type = safe(categoryId).isEmpty() ? safe(config.categoryId) : categoryId;
        if (type.isEmpty()) {
            return DramaSearchResult.failure(config.id, "", "category_missing", "",
                    "该 CMS 目录没有配置短剧分类");
        }
        HttpUrl.Builder url = endpoint().addQueryParameter("ac", "detail")
                .addQueryParameter("t", type)
                .addQueryParameter("pg", String.valueOf(Math.max(1, page)));
        DramaSearchResult result = parseItems(get(url.build()), "");
        result.providerName = config.name;
        return result;
    }

    @Override public DramaDetail detail(String dramaId) throws Exception {
        JsonObject root = object(get(endpoint()
                .addQueryParameter("ac", "detail")
                .addQueryParameter("ids", safe(dramaId))
                .build()));
        JsonArray list = array(root, "list");
        JsonObject item = list != null && list.size() > 0 && list.get(0).isJsonObject()
                ? list.get(0).getAsJsonObject() : null;
        if (item == null) throw new DramaException("parse_error", "详情响应缺少剧目");
        DramaDetail detail = new DramaDetail();
        detail.item = item(item);
        detail.episodes.addAll(parseEpisodes(item, dramaId));
        applyHeaders(detail.episodes);
        detail.directPlayable = !detail.episodes.isEmpty();
        if (!detail.directPlayable) {
            detail.note = "该资源的播放地址需要外部解析，已保留资料并改用播放线路";
        }
        return detail;
    }

    @Override public DramaEpisode episode(String dramaId, int index) throws Exception {
        DramaDetail detail = detail(dramaId);
        for (DramaEpisode episode : detail.episodes) {
            if (episode.index == index) return episode;
        }
        throw new DramaException("episode_not_found", "该剧没有第 " + index + " 集");
    }

    /** Parses one CMS item list into drama entries without touching episode URLs. */
    private DramaSearchResult parseItems(String body, String keyword) throws DramaException {
        JsonObject root = object(body);
        JsonArray list = array(root, "list");
        if (list == null) throw new DramaException("parse_error", "响应缺少 list 数组");
        DramaSearchResult result = new DramaSearchResult();
        result.providerId = safe(config.id);
        result.keyword = safe(keyword);
        result.total = root.has("total") ? root.get("total").getAsInt() : -1;
        for (JsonElement element : list) {
            if (!element.isJsonObject()) continue;
            DramaItem item = item(element.getAsJsonObject());
            if (item.dramaId.isEmpty() && item.title.isEmpty()) continue;
            result.items.add(item);
            if (result.items.size() >= DramaCatalogParser.MAX_ITEMS) {
                result.partial = true;
                break;
            }
        }
        return result;
    }

    static DramaItem item(JsonObject object) {
        DramaItem item = new DramaItem();
        item.dramaId = first(object, "vod_id", "id");
        item.title = first(object, "vod_name", "vod_sub", "name");
        item.cover = first(object, "vod_pic", "vod_pic_thumb", "pic");
        item.intro = first(object, "vod_content", "vod_blurb", "intro");
        item.remark = first(object, "vod_remarks", "vod_state", "remark");
        item.category = first(object, "type_name", "vod_class", "category");
        item.heat = first(object, "vod_hits", "vod_score", "heat");
        item.status = first(object, "vod_state", "status");
        item.episodeCount = parseEpisodeCount(object);
        String typeId = first(object, "type_id", "categoryId");
        if (!typeId.isEmpty()) item.tags.add("分类 " + typeId);
        return item;
    }

    /**
     * Extracts playable episodes from {@code vod_play_url}. Only entries whose own path is a media
     * file are kept, and the longest such play line wins: parse-only links (a {@code ?url=} wrapper)
     * must never reach the player as if they were ready to play.
     */
    static List<DramaEpisode> parseEpisodes(JsonObject item, String dramaId) {
        List<DramaEpisode> best = new java.util.ArrayList<DramaEpisode>();
        String playUrl = first(item, "vod_play_url", "vod_playurl");
        if (playUrl.isEmpty()) return best;
        for (String line : playUrl.split("\\$\\$\\$")) {
            List<DramaEpisode> candidate = parseLine(line, dramaId);
            if (candidate.size() > best.size()) best = candidate;
        }
        return best;
    }

    private static List<DramaEpisode> parseLine(String line, String dramaId) {
        List<DramaEpisode> episodes = new java.util.ArrayList<DramaEpisode>();
        if (line == null || line.isEmpty()) return episodes;
        int position = 0;
        for (String part : line.split("#")) {
            String entry = part.trim();
            if (entry.isEmpty()) continue;
            int separator = entry.indexOf('$');
            String name = separator > 0 ? entry.substring(0, separator).trim() : "";
            String url = separator > 0 ? entry.substring(separator + 1).trim() : entry;
            if (!isDirectMediaUrl(url)) continue;
            position++;
            DramaEpisode episode = new DramaEpisode();
            episode.index = episodeIndex(name, position);
            episode.name = name.isEmpty() ? "第" + position + "集" : name;
            episode.playUrl = url;
            episode.direct = true;
            episodes.add(episode);
        }
        return episodes;
    }

    /** Delegates to the shared playback rule so parsing and playing agree on what is playable. */
    static boolean isDirectMediaUrl(String url) {
        return DramaPlaybackUrls.isDirectMediaUrl(url);
    }

    private static int episodeIndex(String name, int fallback) {
        Matcher matcher = EPISODE_NUMBER.matcher(name == null ? "" : name);
        if (matcher.find()) {
            try {
                int value = Integer.parseInt(matcher.group(1));
                if (value > 0 && value < 10000) return value;
            } catch (NumberFormatException ignored) {
                // fall through to the positional index
            }
        }
        return fallback;
    }

    private static int parseEpisodeCount(JsonObject object) {
        String raw = first(object, "vod_total", "vod_episodes", "episodeCount");
        if (raw.isEmpty()) return 0;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** Playback headers for direct URLs: a browser-ish UA plus the site's referer when needed. */
    Map<String, String> playbackHeaders() {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("User-Agent", USER_AGENT);
        if (!safe(config.referer).isEmpty()) headers.put("Referer", config.referer);
        return headers;
    }

    private void applyHeaders(List<DramaEpisode> episodes) {
        Map<String, String> headers = playbackHeaders();
        for (DramaEpisode episode : episodes) {
            episode.headers.clear();
            episode.headers.putAll(headers);
        }
    }

    private HttpUrl.Builder endpoint() {
        HttpUrl base = HttpUrl.parse(config.baseUrl);
        if (base == null) throw new IllegalArgumentException("CMS 目录地址无效");
        // Drop any query the user pasted (…?ac=list) and build the API parameters explicitly.
        return base.newBuilder().query(null);
    }

    private String get(HttpUrl url) throws IOException, DramaException {
        Request request = new Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .build();
        try (Response response = HttpStack.client().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new DramaException("http_error", "目录 HTTP " + response.code()
                        + "（" + url.host() + "）");
            }
            String body = ResponseBodies.string(response.body(), MAX_BYTES, UTF_8);
            if (body.contains("\"code\":0") || body.startsWith("<")) {
                throw new DramaException("catalog_error", "资源站返回了非 JSON 内容");
            }
            return body;
        }
    }

    private static JsonObject object(String body) throws DramaException {
        if (body == null || body.trim().isEmpty()) {
            throw new DramaException("parse_error", "响应为空");
        }
        JsonElement parsed;
        try {
            parsed = com.google.gson.JsonParser.parseString(body.trim()
                    .replace("\uFEFF", ""));
        } catch (RuntimeException error) {
            throw new DramaException("parse_error", "响应不是有效 JSON", error);
        }
        if (parsed == null || !parsed.isJsonObject()) {
            throw new DramaException("parse_error", "响应不是 JSON 对象");
        }
        return parsed.getAsJsonObject();
    }

    private static JsonArray array(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    private static String first(JsonObject object, String... keys) {
        for (String key : keys) {
            JsonElement element = object.get(key);
            if (element == null || element.isJsonNull()) continue;
            String value = element.isJsonPrimitive() ? element.getAsString() : "";
            if (value != null && !value.trim().isEmpty() && !"null".equals(value.trim())) {
                return value.trim();
            }
        }
        return "";
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
