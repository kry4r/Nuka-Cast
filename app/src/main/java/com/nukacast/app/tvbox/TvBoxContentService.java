package com.nukacast.app.tvbox;

import android.util.Xml;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nukacast.app.net.HttpStack;
import com.nukacast.app.net.ResponseBodies;
import com.nukacast.app.diagnostics.AppLog;
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
    private final CmsSiteSearcher cmsSearcher = new CmsSiteSearcher();
    private final ExecutorService homeExecutor = Executors.newFixedThreadPool(4);
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

    public List<SearchItem> home(int maxSites, int maxItems) throws InterruptedException {
        List<TvBoxConfig.Site> selected = new ArrayList<TvBoxConfig.Site>();
        for (TvBoxConfig.Site site : repository.getEnabledSites()) {
            if (site.type != 0 && site.type != 1 && site.type != 3) continue;
            selected.add(site);
            if (selected.size() >= Math.max(1, maxSites)) break;
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
                        homeFailures.failure(site, error);
                        AppLog.w("片源", "首页站点失败 [" + safe(site.name) + "]："
                                + message(error), error);
                        return Collections.emptyList();
                    }
                }
            });
        }

        List<List<SearchItem>> groups = new ArrayList<List<SearchItem>>();
        List<Future<List<SearchItem>>> futures = homeExecutor.invokeAll(calls, 10, TimeUnit.SECONDS);
        for (int i = 0; i < futures.size(); i++) {
            Future<List<SearchItem>> future = futures.get(i);
            if (future.isCancelled()) {
                homeFailures.failure(selected.get(i), "首页请求超时");
                AppLog.w("片源", "首页站点超时 [" + safe(selected.get(i).name) + "]");
                continue;
            }
            try {
                groups.add(future.get());
            } catch (Exception error) {
                homeFailures.failure(selected.get(i), error);
                AppLog.w("片源", "首页站点失败 [" + safe(selected.get(i).name) + "]："
                        + message(error), error);
            }
        }
        return SearchResultMerger.merge(groups, Math.max(1, maxItems));
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
        return info;
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
        for (TvBoxConfig.ParseEndpoint parser : parsers) {
            if (parser == null || parser.url == null || parser.url.trim().isEmpty()) continue;
            String requestUrl = parserRequest(parser.url.trim(), info.url);
            if (info.sniffUrl.isEmpty()) info.sniffUrl = requestUrl;
            try {
                Request.Builder request = new Request.Builder().url(requestUrl)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 4.4; NukaCast) AppleWebKit/537.36");
                applyHeaders(request, parser.header);
                try (Response response = HttpStack.client().newCall(request.build()).execute()) {
                    if (!response.isSuccessful() || response.body() == null) continue;
                    String body = ResponseBodies.string(
                            response.body(), MAX_CMS_BYTES, UTF_8).trim();
                    PlaybackInfo parsed = PlaybackInfoParser.parse(body, "");
                    if (parsed.direct && !parsed.url.isEmpty()) {
                        info.url = parsed.url;
                        info.direct = true;
                        info.headers.putAll(parsed.headers);
                        info.error = "";
                        return;
                    }
                    if (PlaybackInfoParser.isDirectMedia(response.request().url().toString())) {
                        info.url = response.request().url().toString();
                        info.direct = true;
                        info.error = "";
                        return;
                    }
                }
            } catch (Exception failure) {
                AppLog.w("解析", "解析接口失败 [" + safe(parser.name) + "]："
                        + message(failure), failure);
            }
        }
        if (info.sniffUrl.isEmpty()) info.sniffUrl = directSniff;
        info.error = info.sniffUrl.isEmpty()
                ? "播放地址需要解析，但配置没有可用解析器"
                : "解析接口未返回直链，需要嗅探解析页";
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
