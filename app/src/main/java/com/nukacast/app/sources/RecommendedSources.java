package com.nukacast.app.sources;

import android.content.Context;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.nukacast.app.diagnostics.AppLog;
import com.nukacast.app.diagnostics.ErrorCodes;
import com.nukacast.app.drama.DramaCatalogRegistry;
import com.nukacast.app.drama.model.DramaProviderConfig;
import com.nukacast.app.live.LivePlaylistParser;
import com.nukacast.app.live.model.LiveCatalog;
import com.nukacast.app.net.HttpStack;
import com.nukacast.app.tvbox.LiveSourceStore;
import com.nukacast.app.tvbox.SourceStore;
import com.nukacast.app.tvbox.model.ConfigSource;
import com.nukacast.app.tvbox.model.LivePlaylist;

import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Curated source list shipped with the app.
 *
 * <p>The bundled JSON is the authority: every entry was probed from a real network before release
 * and is re-probed on demand with {@link #verify} so a user can see whether <em>their</em> network
 * can still reach it. Adding never silently succeeds: a source is only stored after the store
 * accepts it, and failures are reported with a reason instead of being swallowed.
 */
public final class RecommendedSources {
    private static final String ASSET = "sources/recommended.json";
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final long MAX_BYTES = 2L * 1024L * 1024L;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36";
    private static final Type ITEM_LIST = new TypeToken<List<RecommendedSource>>() {}.getType();

    private final Context context;
    private final SourceStore sourceStore;
    private final LiveSourceStore liveSourceStore;
    private final DramaCatalogRegistry dramaRegistry;
    private final Map<String, RecommendedSource.Probe> probes =
            new LinkedHashMap<String, RecommendedSource.Probe>();

    public RecommendedSources(Context context, SourceStore sourceStore,
                              LiveSourceStore liveSourceStore,
                              DramaCatalogRegistry dramaRegistry) {
        this.context = context.getApplicationContext();
        this.sourceStore = sourceStore;
        this.liveSourceStore = liveSourceStore;
        this.dramaRegistry = dramaRegistry;
    }

    /** Bundled entries with {@code added} resolved against the current stores. */
    public List<RecommendedSource> list() {
        List<RecommendedSource> result = new ArrayList<RecommendedSource>();
        for (RecommendedSource item : bundled()) {
            item.probe = probes.get(item.id);
            result.add(item);
        }
        return result;
    }

    public String verifiedAt() {
        JsonObject root = root();
        return root == null ? "" : text(root, "verifiedAt");
    }

    public String note() {
        JsonObject root = root();
        return root == null ? "" : text(root, "note");
    }

    public RecommendedSource find(String id) {
        for (RecommendedSource item : list()) {
            if (item.id.equals(id)) return item;
        }
        return null;
    }

    /** Adds one bundled source through the store that owns its kind. */
    public RecommendedSource add(String id) {
        RecommendedSource item = find(id);
        if (item == null) throw new IllegalArgumentException("推荐源不存在：" + id);
        if (item.added) return item;
        if (RecommendedSource.KIND_LIVE.equals(item.kind)) {
            liveSourceStore.add(item.name, item.url);
        } else if (RecommendedSource.KIND_DRAMA.equals(item.kind)) {
            dramaRegistry.add(item.name, item.url, kindOf(item), item.categoryId, item.note);
        } else {
            sourceStore.add(item.name, item.url);
        }
        RecommendedSource refreshed = find(id);
        if (refreshed != null) refreshed.probe = item.probe;
        return refreshed == null ? item : refreshed;
    }

    /** Adds every bundled entry of one kind; returns how many were newly stored. */
    public int addAll(String kind) {
        int added = 0;
        for (RecommendedSource item : bundled()) {
            if (!item.added && (kind == null || kind.isEmpty() || kind.equals(item.kind))) {
                try {
                    add(item.id);
                    added++;
                } catch (RuntimeException error) {
                    AppLog.w("推荐源", "添加失败 [" + item.name + "]：" + error.getMessage());
                }
            }
        }
        return added;
    }

    /**
     * Probes one entry with the app's own HTTP stack and records the result. The probe validates
     * the payload shape per kind instead of trusting a 200 status.
     */
    public RecommendedSource.Probe verify(String id) {
        RecommendedSource item = find(id);
        if (item == null) throw new IllegalArgumentException("推荐源不存在：" + id);
        RecommendedSource.Probe probe = new RecommendedSource.Probe();
        probe.id = item.id;
        probe.checkedAt = System.currentTimeMillis();
        long startedAt = System.currentTimeMillis();
        if (com.nukacast.app.net.BundledAssets.isBundled(item.url)) {
            // Bundled source: nothing to reach over the network, verify that it decodes and that the
            // sites it declares are actually there.
            try {
                byte[] bytes = com.nukacast.app.net.BundledAssets.read(context, item.url);
                probe.httpStatus = 200;
                probe.bytes = bytes.length;
                describe(item, new String(bytes, UTF_8), probe);
            } catch (Exception error) {
                probe.errorCode = ErrorCodes.of(error);
                probe.error = ErrorCodes.message(error);
            }
            probe.latencyMs = System.currentTimeMillis() - startedAt;
            synchronized (probes) {
                probes.put(item.id, probe);
            }
            return probe;
        }
        try (Response response = call(item)) {
            probe.httpStatus = response.code();
            ResponseBody body = response.body();
            String text = body == null ? "" : bounded(body);
            probe.bytes = text.getBytes(UTF_8).length;
            probe.latencyMs = System.currentTimeMillis() - startedAt;
            if (!response.isSuccessful()) {
                probe.errorCode = "http_error";
                probe.error = "HTTP " + response.code();
            } else {
                describe(item, text, probe);
            }
        } catch (Throwable error) {
            probe.latencyMs = System.currentTimeMillis() - startedAt;
            probe.errorCode = ErrorCodes.of(error);
            probe.error = ErrorCodes.message(error);
        }
        synchronized (probes) {
            probes.put(item.id, probe);
        }
        return probe;
    }

    public void clearProbes() {
        synchronized (probes) {
            probes.clear();
        }
    }

    private void describe(RecommendedSource item, String text, RecommendedSource.Probe probe) {
        String body = text == null ? "" : text.trim();
        if (body.isEmpty()) {
            probe.errorCode = "empty_body";
            probe.error = "响应为空";
            return;
        }
        if (RecommendedSource.KIND_LIVE.equals(item.kind)) {
            int channels = channelCount(body);
            if (channels > 0) {
                probe.ok = true;
                probe.detail = channels + " 个频道";
            } else {
                probe.errorCode = "not_a_playlist";
                probe.error = "不是 m3u/txt 直播清单";
            }
            return;
        }
        if (RecommendedSource.KIND_DRAMA.equals(item.kind)
                && DramaProviderConfig.KIND_CMS_DRAMA.equals(kindOf(item))) {
            describeCms(body, probe);
            return;
        }
        describeConfig(item, body, probe);
    }

    /** True when this entry ships inside the APK rather than pointing at a network location. */
    public boolean isBundled(String id) {
        RecommendedSource item = find(id);
        return item != null && com.nukacast.app.net.BundledAssets.isBundled(item.url);
    }

    private void describeCms(String body, RecommendedSource.Probe probe) {
        JsonObject root = object(body);
        if (root == null) {
            probe.errorCode = "parse_error";
            probe.error = "资源站没有返回 JSON";
            return;
        }
        JsonArray list = root.has("list") && root.get("list").isJsonArray()
                ? root.getAsJsonArray("list") : null;
        if (list == null) {
            probe.errorCode = "catalog_error";
            probe.error = "响应缺少 list 字段";
            return;
        }
        probe.ok = true;
        probe.detail = root.has("total") ? "共 " + root.get("total").getAsInt() + " 部"
                : list.size() + " 部";
    }

    private void describeConfig(RecommendedSource item, String body,
                                RecommendedSource.Probe probe) {
        JsonObject root = object(body);
        if (root == null) {
            probe.errorCode = "parse_error";
            probe.error = "不是 JSON 配置";
            return;
        }
        int sites = arraySize(root, "sites");
        int warehouses = arraySize(root, "urls");
        int storeHouse = arraySize(root, "storeHouse");
        int lives = arraySize(root, "lives");
        int items = arraySize(root, "items");
        if (sites > 0 || storeHouse > 0 || warehouses > 0 || lives > 0) {
            probe.ok = true;
            StringBuilder detail = new StringBuilder();
            if (sites > 0) detail.append(sites).append(" 个站点");
            if (warehouses > 0) detail.append(detail.length() > 0 ? "，" : "")
                    .append(warehouses).append(" 个子仓");
            if (storeHouse > 0) detail.append(detail.length() > 0 ? "，" : "")
                    .append(storeHouse).append(" 个仓库");
            if (lives > 0) detail.append(detail.length() > 0 ? "，" : "")
                    .append(lives).append(" 个直播源");
            probe.detail = detail.toString();
            return;
        }
        if (RecommendedSource.KIND_DRAMA.equals(item.kind)) {
            int total = root.has("total") && root.get("total").isJsonPrimitive()
                    ? root.get("total").getAsInt() : items;
            if (total > 0) {
                probe.ok = true;
                probe.detail = "共 " + total + " 条剧目";
                return;
            }
            probe.errorCode = "catalog_error";
            probe.error = "目录没有返回剧目列表";
            return;
        }
        if (root.has("ok") || root.has("total")) {
            probe.ok = true;
            probe.detail = root.has("total") ? "共 " + root.get("total").getAsInt() + " 条" : "";
            return;
        }
        probe.errorCode = "unexpected_shape";
        probe.error = "JSON 结构不是 TVBox 配置或目录响应";
    }

    private Response call(RecommendedSource item) throws Exception {
        if (com.nukacast.app.net.BundledAssets.isBundled(item.url)) return null;
        String url = item.url;
        if (RecommendedSource.KIND_DRAMA.equals(item.kind)
                && DramaProviderConfig.KIND_CMS_DRAMA.equals(kindOf(item))) {
            url = cmsListUrl(item);
        } else if (RecommendedSource.KIND_DRAMA.equals(item.kind)) {
            // A metadata catalog has no listing endpoint, so only a real search proves it still
            // answers with the JSON contract the app relies on.
            url = catalogSearchUrl(item, "重生");
        }
        Request request = new Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json,text/plain,*/*")
                .build();
        return HttpStack.client().newBuilder()
                .callTimeout(25, TimeUnit.SECONDS)
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .build()
                .newCall(request).execute();
    }

    /** Uses the real playlist parser so validation matches what playback will see. */
    static int channelCount(String body) {
        try {
            LiveCatalog catalog = LivePlaylistParser.parse(body);
            int channels = 0;
            for (LiveCatalog.Group group : catalog.groups) channels += group.channels.size();
            return channels;
        } catch (RuntimeException error) {
            return 0;
        }
    }

    static String catalogSearchUrl(RecommendedSource item, String keyword) {
        String base = item.url;
        if (base.contains("?")) base = base.substring(0, base.indexOf('?'));
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        try {
            return base + "/api/search?q=" + java.net.URLEncoder.encode(keyword, "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            return base + "/api/search?q=" + keyword;
        }
    }

    private static String cmsListUrl(RecommendedSource item) {
        String base = item.url;
        if (base.contains("?")) base = base.substring(0, base.indexOf('?'));
        if (!base.endsWith("/")) base = base + "/";
        String url = base + "?ac=detail&pg=1";
        if (item.categoryId != null && !item.categoryId.isEmpty()) {
            url += "&t=" + item.categoryId;
        }
        return url;
    }

    /** Kind this URL behaves as; a CMS API is detected from its path, everything else is a catalog. */
    static String kindOf(RecommendedSource item) {
        if (RecommendedSource.KIND_DRAMA.equals(item.kind)) {
            String url = item.url == null ? "" : item.url;
            if (url.contains("api.php/provide/vod")) return DramaProviderConfig.KIND_CMS_DRAMA;
            return DramaProviderConfig.KIND_VOTE_CATALOG;
        }
        return item.kind;
    }

    private static String bounded(ResponseBody body) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (InputStreamReader reader = new InputStreamReader(body.byteStream(), UTF_8)) {
            char[] buffer = new char[8192];
            long total = 0;
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                builder.append(buffer, 0, read);
                total += read;
                if (total >= MAX_BYTES) break;
            }
        }
        return builder.toString();
    }

    private List<RecommendedSource> bundled() {
        List<RecommendedSource> result = new ArrayList<RecommendedSource>();
        JsonObject root = root();
        if (root == null || !root.has("items")) return result;
        JsonElement items = root.get("items");
        List<RecommendedSource> parsed;
        try {
            parsed = new com.google.gson.Gson().fromJson(items, ITEM_LIST);
        } catch (RuntimeException error) {
            return result;
        }
        if (parsed == null) return result;
        String verifiedAt = text(root, "verifiedAt");
        for (RecommendedSource item : parsed) {
            if (item == null || item.id == null || item.id.isEmpty()) continue;
            if (item.verifiedAt == null || item.verifiedAt.isEmpty()) item.verifiedAt = verifiedAt;
            item.added = isAdded(item);
            result.add(item);
        }
        return result;
    }

    private boolean isAdded(RecommendedSource item) {
        if (RecommendedSource.KIND_LIVE.equals(item.kind)) {
            return liveSourceStore.contains(item.url);
        }
        if (RecommendedSource.KIND_DRAMA.equals(item.kind)) {
            return dramaRegistry.contains(item.url);
        }
        for (ConfigSource source : sourceStore.getSources()) {
            if (item.url.equalsIgnoreCase(source.url)) return true;
        }
        return false;
    }

    private JsonObject root() {
        try (java.io.InputStream stream = context.getAssets().open(ASSET)) {
            JsonElement parsed = JsonParser.parseReader(
                    new InputStreamReader(stream, UTF_8));
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception error) {
            AppLog.w("推荐源", "内置推荐源读取失败：" + error.getMessage());
            return null;
        }
    }

    private static JsonObject object(String body) {
        try {
            JsonElement parsed = JsonParser.parseString(body.replace("\uFEFF", ""));
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static int arraySize(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray().size() : 0;
    }

    private static String text(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return "";
        String value = element.getAsString();
        return value == null ? "" : value.trim();
    }
}
