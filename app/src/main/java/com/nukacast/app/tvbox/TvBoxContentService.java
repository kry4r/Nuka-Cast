package com.nukacast.app.tvbox;

import android.util.Xml;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nukacast.app.net.HttpStack;
import com.nukacast.app.net.ResponseBodies;
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
                continue;
            }
            try {
                groups.add(future.get());
            } catch (Exception error) {
                homeFailures.failure(selected.get(i), error);
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
        PlaybackInfo info = site.type == 3
                ? PlaybackInfoParser.parse(spiders.play(site, safe(flag), episodeId), episodeId)
                : PlaybackInfoParser.parse(null, episodeId);
        info.siteKey = site.key;
        info.title = safe(title);
        if (!info.direct && !info.url.isEmpty()) {
            String parsed = resolveWithConfiguredParsers(site, info.url);
            if (!parsed.isEmpty()) {
                info.url = parsed;
                info.direct = true;
            } else {
                info.error = "播放地址仍需要第三方解析";
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

    private String resolveWithConfiguredParsers(TvBoxConfig.Site site, String mediaUrl) {
        TvBoxConfig config = repository.getConfig(site.sourceId);
        if (config == null) return "";
        for (TvBoxConfig.ParseEndpoint parser : config.parses) {
            if (parser.url == null || parser.url.trim().isEmpty()) continue;
            String requestUrl = parser.url + mediaUrl;
            try {
                Request request = new Request.Builder().url(requestUrl)
                        .header("User-Agent", "NukaCast/0.1 Parser")
                        .build();
                try (Response response = HttpStack.client().newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) continue;
                    String body = ResponseBodies.string(
                            response.body(), MAX_CMS_BYTES, UTF_8).trim();
                    PlaybackInfo parsed = PlaybackInfoParser.parse(body, "");
                    if (PlaybackInfoParser.isDirectMedia(parsed.url)) return parsed.url;
                    if (PlaybackInfoParser.isDirectMedia(response.request().url().toString())) {
                        return response.request().url().toString();
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return "";
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
}
