package com.nukacast.app.tvbox;

import android.util.Xml;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nukacast.app.net.HttpStack;
import com.nukacast.app.net.ResponseBodies;
import com.nukacast.app.diagnostics.AppLog;
import com.nukacast.app.tvbox.SiteHealthStore;
import com.nukacast.app.spider.SpiderManager;
import com.nukacast.app.storage.StorageLibrary;
import com.nukacast.app.tvbox.model.MediaDetail;
import com.nukacast.app.tvbox.model.PlaybackInfo;
import com.nukacast.app.tvbox.model.SearchItem;
import com.nukacast.app.tvbox.model.SearchQuery;
import com.nukacast.app.tvbox.model.TvBoxConfig;
import com.nukacast.app.tvbox.search.CmsSiteSearcher;

import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

public final class TvBoxContentService {
    private static final int MAX_CMS_BYTES = 4 * 1024 * 1024;
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private final TvBoxRepository repository;
    private final SpiderManager spiders;
    private final StorageLibrary storageLibrary;
    private SiteHealthStore healthStore;
    private com.nukacast.app.tvbox.TitleIndex titleIndex;

    /** Shares the search engine's title index, so an initials query can find home titles. */
    public void useTitleIndex(com.nukacast.app.tvbox.TitleIndex index) {
        this.titleIndex = index;
    }

    /** Sites a measured sweep found working get priority on the home screen. */
    public void useHealthStore(SiteHealthStore store) {
        this.healthStore = store;
    }
    private final CmsSiteSearcher cmsSearcher = new CmsSiteSearcher();
    /**
     * Two threads, not four: each home site can start a QuickJS runtime or a dex-loaded spider, and
     * four of those in parallel is what peaks native memory right after startup on a small TV.
     */
    private final ExecutorService homeExecutor = Executors.newFixedThreadPool(2);
    private final SiteFailureStore homeFailures = new SiteFailureStore();

    public TvBoxContentService(TvBoxRepository repository, SpiderManager spiders) {
        this(repository, spiders, null);
    }

    public TvBoxContentService(TvBoxRepository repository, SpiderManager spiders,
                               StorageLibrary storageLibrary) {
        this.repository = repository;
        this.spiders = spiders;
        this.storageLibrary = storageLibrary;
    }

    public MediaDetail detail(String sourceId, String siteKey, String vodId) throws Exception {
        if (isStorage(sourceId)) return requireStorage().detail(vodId);
        TvBoxConfig.Site site = requireSite(sourceId, siteKey);
        String body = site.type == 3
                ? spiders.detail(site, Collections.singletonList(vodId))
                : requestCmsDetail(site, vodId);
        if (body.trim().startsWith("<")) body = xmlToJson(body);
        MediaDetail detail = MediaDetailParser.parse(body, site.key, site.name);
        detail.sourceId = site.sourceId;
        return detail;
    }

    /**
     * Plugin sites are only a small part of the home batch: each one costs a QuickJS runtime or a
     * DexClassLoader spider instance in native memory, which is what makes a low-end TV kill the
     * process a minute after startup. Plain CMS sites (type 0/1) are cheap and come first.
     */
    public static final int MAX_PLUGIN_HOME_SITES = 2;
    /** How long the first screen waits for sites before rendering what has arrived. */
    private static final long HOME_BUDGET_MS = 5_000L;

    public List<SearchItem> home(int maxSites, int maxItems) throws InterruptedException {
        List<TvBoxConfig.Site> pluginSites = new ArrayList<TvBoxConfig.Site>();
        List<TvBoxConfig.Site> selected = new ArrayList<TvBoxConfig.Site>();
        int skipped = 0;
        int quota = Math.max(1, maxSites);
        List<TvBoxConfig.Site> unknown = new ArrayList<TvBoxConfig.Site>();
        List<TvBoxConfig.Site> cmsSites = new ArrayList<TvBoxConfig.Site>();
        for (TvBoxConfig.Site site : repository.getEnabledSites()) {
            if (site.type != 0 && site.type != 1 && site.type != 3) continue;
            if (site.type == 3) {
                if (spiders.compatibility().isUnsupported(site)
                        || !com.nukacast.app.spider.SpiderManager.jarSpidersSupported()) {
                    skipped++;
                    continue;
                }
                pluginSites.add(site);
                continue;
            }
            if (healthStore != null && healthStore.isKnownBad(site)) {
                skipped++;
                continue;
            }
            // CMS sites that a sweep already saw answering come first: on a 1.5 GB TV the home
            // screen only has room for a handful, and an unmeasured site is a coin flip.
            if (healthStore != null && healthStore.isKnownGood(site)) cmsSites.add(site);
            else unknown.add(site);
        }
        selected.addAll(cmsSites);
        for (TvBoxConfig.Site site : unknown) {
            if (selected.size() >= quota) break;
            selected.add(site);
        }
        // Only fill the remaining slots with plugins, and never more than a couple of them.
        int plugins = 0;
        for (TvBoxConfig.Site site : pluginSites) {
            if (selected.size() >= quota || plugins >= MAX_PLUGIN_HOME_SITES) break;
            // While the app is shedding memory, plugin sites stay out of the home batch entirely.
            if (spiders.pausedForMemory()) break;
            selected.add(site);
            plugins++;
        }
        if (plugins == 0 && !pluginSites.isEmpty() && spiders.pausedForMemory()) {
            AppLog.i("片源", "首页本次不加载插件站点（内存已收紧）");
        }
        if (skipped > 0) {
            AppLog.i("片源", "首页跳过 " + skipped + " 个本机不支持的站点（详见设备页诊断）");
        }

        List<Callable<List<SearchItem>>> calls = new ArrayList<Callable<List<SearchItem>>>();
        for (final TvBoxConfig.Site site : selected) {
            calls.add(new Callable<List<SearchItem>>() {
                @Override public List<SearchItem> call() {
                    try {
                        if (site.type == 3) {
                            List<SearchItem> result = HomeCatalogParser.parse(spiders.home(site, true),
                                    site.sourceId, site.key, site.name);
                            homeFailures.success(site);
                            return result;
                        }
                        SearchQuery query = new SearchQuery();
                        query.keyword = "";
                        query.page = 1;
                        List<SearchItem> result = cmsSearcher.search(site, query);
                        homeFailures.success(site);
                        return result;
                    } catch (Throwable error) {
                        if (SearchEngine.isCancellation(error)) {
                            homeFailures.failure(site, "首页请求超时");
                            AppLog.d("片源", "首页站点超时 [" + safe(site.name) + "]");
                            return Collections.emptyList();
                        }
                        homeFailures.failure(site, error);
                        AppLog.d("片源", "首页站点失败 [" + safe(site.name) + "]："
                                + message(error));
                        return Collections.emptyList();
                    }
                }
            });
        }

        List<List<SearchItem>> groups = new ArrayList<List<SearchItem>>();
        int timedOut = 0;
        int failed = 0;
        // Bounded, first-come-first-served: the home screen renders whatever answered inside the
        // budget instead of waiting for the slowest site. On the affected TV a single unresponsive
        // CMS host used to hold the whole first screen for ten seconds.
        java.util.concurrent.ExecutorCompletionService<List<SearchItem>> completion =
                new java.util.concurrent.ExecutorCompletionService<List<SearchItem>>(homeExecutor);
        List<Future<List<SearchItem>>> submitted = new ArrayList<Future<List<SearchItem>>>();
        for (Callable<List<SearchItem>> call : calls) submitted.add(completion.submit(call));
        long deadline = System.currentTimeMillis() + HOME_BUDGET_MS;
        int answered = 0;
        while (answered < submitted.size()) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0L) break;
            Future<List<SearchItem>> done;
            try {
                done = completion.poll(remaining, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            if (done == null) break;
            answered++;
            try {
                groups.add(done.get());
            } catch (Exception error) {
                failed++;
            }
        }
        for (Future<List<SearchItem>> future : submitted) {
            if (!future.isDone()) {
                future.cancel(true);
                timedOut++;
            }
        }
        // Sites that never answered inside the budget are recorded as timeouts so their health
        // verdicts (and the diagnostics page) reflect reality.
        for (int i = 0; i < selected.size() && i < timedOut; i++) {
            homeFailures.failure(selected.get(i), "首页请求超时");
            AppLog.d("片源", "首页站点超时 [" + safe(selected.get(i).name) + "]");
        }
        if (!selected.isEmpty() && (timedOut > 0 || failed > 0)) {
            AppLog.i("片源", "首页加载：" + selected.size() + " 个站点 → "
                    + (selected.size() - timedOut - failed) + " 成功 / " + timedOut
                    + " 超时 / " + failed + " 失败");
        }
        List<SearchItem> merged = SearchResultMerger.merge(groups, Math.max(1, maxItems));
        if (titleIndex != null) {
            for (SearchItem item : merged) titleIndex.add(item.name);
        }
        return merged;
    }

    public void shutdown() {
        homeExecutor.shutdownNow();
    }

    public List<SiteFailureStore.Failure> homeFailures() {
        return homeFailures.snapshot();
    }

    public void retainHomeFailures(List<TvBoxConfig.Site> sites) {
        homeFailures.retainSites(sites);
    }

    public PlaybackInfo resolve(String sourceId, String siteKey, String flag, String episodeId,
                                String title) throws Exception {
        if (isStorage(sourceId)) return requireStorage().resolve(episodeId, title);
        String cached = cachedResolution(episodeId);
        if (cached != null) {
            PlaybackInfo hit = new PlaybackInfo();
            hit.url = cached;
            hit.direct = true;
            hit.title = title == null ? "" : title;
            AppLog.i("解析", "使用已缓存的播放地址");
            return hit;
        }
        TvBoxConfig.Site site = requireSite(sourceId, siteKey);
        TvBoxConfig config = repository.getConfig(site.sourceId);
        PlaybackInfo info = site.type == 3
                ? PlaybackInfoParser.parse(spiders.play(site, safe(flag), episodeId,
                        config == null || config.flags == null
                                ? Collections.<String>emptyList() : config.flags), episodeId)
                : PlaybackInfoParser.episode(episodeId);
        info.siteKey = site.key;
        info.title = safe(title);
        if (!info.direct && !info.url.isEmpty()) {
            if (PlaybackInfoParser.isSpiderProxy(info.url)) {
                info.sniffUrl = info.url;
                info.error = "播放地址需要通过 Spider 代理页嗅探";
            } else {
                resolveWithConfiguredParsers(site, info);
            }
        }
        if (!info.url.isEmpty()) {
            // Several CMS back ends publish an HTML player page instead of media; playing it gives a
            // black screen. Resolve it to the real media URL so ordinary playback works.
            String media = MaccmsShareResolver.resolve(info.url, info.headers.get("Referer"));
            if (media != null && !media.equals(info.url)) {
                info.url = media;
                info.direct = true;
                info.error = "";
            } else if (MaccmsShareResolver.looksLikeSharePage(info.url)) {
                info.error = "该线路返回的是播放页，未解析出可直接播放的地址";
            }
        }
        if (info.direct && !info.url.isEmpty()) cacheResolution(episodeId, info.url);
        return info;
    }

    private static final int MAX_PARSER_ATTEMPTS = 3;

    /**
     * Parse endpoints are raced on a small pool with one shared deadline.
     *
     * <p>Sequentially, three dead parsers cost three full socket timeouts — on the affected TV that
     * was 8 seconds each, so starting an episode took half a minute and often never finished. Racing
     * them bounds the wait to a single deadline and lets the fastest working parser win.
     */
    private static final long PARSER_DEADLINE_MS = 4000L;

    /** Resolved addresses stay valid for a while; replaying or resuming should not re-resolve. */
    private static final int MAX_RESOLVE_CACHE = 64;
    private static final java.util.Map<String, String> RESOLVE_CACHE =
            new java.util.LinkedHashMap<String, String>() {
                @Override protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, String> eldest) {
                    return size() > MAX_RESOLVE_CACHE;
                }
            };

    private static String cachedResolution(String episodeId) {
        if (episodeId == null || episodeId.isEmpty()) return null;
        synchronized (RESOLVE_CACHE) {
            return RESOLVE_CACHE.get(episodeId);
        }
    }

    private static void cacheResolution(String episodeId, String url) {
        if (episodeId == null || episodeId.isEmpty() || url == null || url.isEmpty()) return;
        synchronized (RESOLVE_CACHE) {
            RESOLVE_CACHE.put(episodeId, url);
        }
    }

    private String requestCmsDetail(TvBoxConfig.Site site, String vodId) throws Exception {
        HttpUrl api = HttpUrl.parse(site.api);
        if (api == null) throw new IllegalArgumentException("无效 CMS 地址");
        HttpUrl url = api.newBuilder()
                .setQueryParameter("ac", site.type == 0 ? "videolist" : "detail")
                .setQueryParameter("ids", vodId)
                .build();
        Request request = new Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 4.2.2; NukaCast)")
                .build();
        try (Response response = HttpStack.client().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("CMS 详情 HTTP " + response.code());
            }
            return ResponseBodies.string(response.body(), MAX_CMS_BYTES, UTF_8);
        }
    }

    private void resolveWithConfiguredParsers(TvBoxConfig.Site site, PlaybackInfo info) {
        String directSniff = info.url.startsWith("http://") || info.url.startsWith("https://")
                ? info.url : "";
        TvBoxConfig config = repository.getConfig(site.sourceId);
        List<TvBoxConfig.ParseEndpoint> parsers = new ArrayList<TvBoxConfig.ParseEndpoint>();
        if (site.playerUrl != null && !site.playerUrl.trim().isEmpty()) {
            TvBoxConfig.ParseEndpoint siteParser = new TvBoxConfig.ParseEndpoint();
            siteParser.name = site.name;
            siteParser.url = site.playerUrl.trim();
            siteParser.type = site.playerType;
            parsers.add(siteParser);
        }
        if (config != null && config.parses != null) parsers.addAll(config.parses);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(Math.max(1,
                        Math.min(MAX_PARSER_ATTEMPTS, parsers.size())));
        try {
            List<java.util.concurrent.Future<String>> futures =
                    new ArrayList<java.util.concurrent.Future<String>>();
            int started = 0;
            for (final TvBoxConfig.ParseEndpoint parser : parsers) {
                if (started >= MAX_PARSER_ATTEMPTS) break;
                if (parser == null || parser.url == null || parser.url.trim().isEmpty()) continue;
                started++;
                final String requestUrl = parserRequest(parser.url.trim(), info.url);
                if (info.sniffUrl.isEmpty()) info.sniffUrl = requestUrl;
                futures.add(pool.submit(new java.util.concurrent.Callable<String>() {
                    @Override public String call() {
                        return tryParser(parser, requestUrl);
                    }
                }));
            }
            long deadline = System.currentTimeMillis() + PARSER_DEADLINE_MS;
            for (java.util.concurrent.Future<String> future : futures) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) break;
                try {
                    String resolved = future.get(remaining,
                            java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (resolved != null && !resolved.isEmpty()) {
                        info.url = resolved;
                        info.direct = true;
                        info.error = "";
                        for (java.util.concurrent.Future<String> other : futures) other.cancel(true);
                        AppLog.i("解析", "解析接口返回直链（并发 " + started + " 个）");
                        return;
                    }
                } catch (Exception ignored) {
                    // Each parser reports its own failure inside tryParser.
                }
            }
        } finally {
            pool.shutdownNow();
        }
        if (info.sniffUrl.isEmpty()) info.sniffUrl = directSniff;
        if (PlaybackInfoParser.isDirectMedia(info.url)) {
            // The address itself is already media: play it rather than sending the user to the
            // sniffer. An unreachable parse endpoint used to look exactly like a broken source.
            info.direct = true;
            info.error = "";
            AppLog.i("解析", "解析接口不可用，改用原始直链播放");
            return;
        }
        info.error = info.sniffUrl.isEmpty()
                ? "播放地址需要解析，但配置没有可用解析器"
                : "解析接口未返回直链，需要嗅探解析页";
    }

    /** Runs one parse endpoint on a pool thread; returns a direct media URL or null. */
    private String tryParser(TvBoxConfig.ParseEndpoint parser, String requestUrl) {
        try {
            Request.Builder request = new Request.Builder().url(requestUrl)
                    .header("User-Agent",
                            "Mozilla/5.0 (Linux; Android 4.4; NukaCast) AppleWebKit/537.36");
            applyHeaders(request, parser.header);
            try (Response response = HttpStack.client().newCall(request.build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) return null;
                String body = ResponseBodies.string(response.body(), MAX_CMS_BYTES, UTF_8).trim();
                PlaybackInfo parsed = PlaybackInfoParser.parse(body, "");
                if (parsed.direct && !parsed.url.isEmpty()) return parsed.url;
                if (PlaybackInfoParser.isDirectMedia(response.request().url().toString())) {
                    return response.request().url().toString();
                }
            }
        } catch (Exception failure) {
            AppLog.w("解析", "解析接口失败 [" + safe(parser.name) + "]：" + message(failure));
        }
        return null;
    }

    private static String parserRequest(String parserUrl, String mediaUrl) {
        if (parserUrl.contains("{url}")) return parserUrl.replace("{url}", mediaUrl);
        return parserUrl + mediaUrl;
    }

    private static void applyHeaders(Request.Builder request, com.google.gson.JsonElement value) {
        if (value == null || !value.isJsonObject()) return;
        for (Map.Entry<String, com.google.gson.JsonElement> entry
                : value.getAsJsonObject().entrySet()) {
            if (entry.getValue() != null && entry.getValue().isJsonPrimitive()) {
                request.header(entry.getKey(), entry.getValue().getAsString());
            }
        }
    }

    private TvBoxConfig.Site requireSite(String sourceId, String siteKey) {
        TvBoxConfig.Site site = repository.findSite(sourceId, siteKey);
        if (site == null) throw new IllegalArgumentException("找不到影视站点");
        return site;
    }

    private StorageLibrary requireStorage() {
        if (storageLibrary == null) throw new IllegalStateException("片库服务未启用");
        return storageLibrary;
    }

    private static boolean isStorage(String sourceId) {
        return sourceId != null && sourceId.startsWith("storage:");
    }

    private static String xmlToJson(String body) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new StringReader(body));
        JsonObject video = new JsonObject();
        List<String> flags = new ArrayList<String>();
        List<String> lines = new ArrayList<String>();
        String tag = "";
        String ddFlag = "";
        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                tag = parser.getName().toLowerCase(Locale.ROOT);
                if ("dd".equals(tag)) ddFlag = safe(parser.getAttributeValue(null, "flag"));
            } else if (event == XmlPullParser.TEXT) {
                String text = parser.getText() == null ? "" : parser.getText().trim();
                if (text.isEmpty()) continue;
                if ("dd".equals(tag)) {
                    flags.add(ddFlag.isEmpty() ? "线路 " + (flags.size() + 1) : ddFlag);
                    lines.add(text);
                } else {
                    String key = field(tag);
                    if (!key.isEmpty()) video.addProperty(key, text);
                }
            } else if (event == XmlPullParser.END_TAG) {
                tag = "";
            }
        }
        video.addProperty("vod_play_from", join(flags));
        video.addProperty("vod_play_url", join(lines));
        JsonArray list = new JsonArray();
        list.add(video);
        JsonObject root = new JsonObject();
        root.add("list", list);
        return root.toString();
    }

    private static String field(String tag) {
        if ("id".equals(tag)) return "vod_id";
        if ("name".equals(tag)) return "vod_name";
        if ("pic".equals(tag)) return "vod_pic";
        if ("note".equals(tag)) return "vod_remarks";
        if ("year".equals(tag)) return "vod_year";
        if ("area".equals(tag)) return "vod_area";
        if ("type".equals(tag)) return "type_name";
        if ("actor".equals(tag)) return "vod_actor";
        if ("director".equals(tag)) return "vod_director";
        if ("des".equals(tag)) return "vod_content";
        return "";
    }

    private static String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append("$$$");
            result.append(value);
        }
        return result.toString();
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
