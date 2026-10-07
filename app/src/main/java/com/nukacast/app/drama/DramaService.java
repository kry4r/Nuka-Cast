package com.nukacast.app.drama;

import android.content.Context;

import com.nukacast.app.diagnostics.AppLog;
import com.nukacast.app.diagnostics.ErrorCodes;
import com.nukacast.app.drama.model.DramaDetail;
import com.nukacast.app.drama.model.DramaEpisode;
import com.nukacast.app.drama.model.DramaItem;
import com.nukacast.app.drama.model.DramaLine;
import com.nukacast.app.drama.model.DramaLineResult;
import com.nukacast.app.drama.model.DramaPlayResult;
import com.nukacast.app.drama.model.DramaProviderConfig;
import com.nukacast.app.drama.model.DramaSearchResult;
import com.nukacast.app.spider.SpiderManager;
import com.nukacast.app.tvbox.TvBoxRepository;
import com.nukacast.app.tvbox.model.SearchItem;
import com.nukacast.app.tvbox.model.SearchQuery;
import com.nukacast.app.tvbox.model.TvBoxConfig;
import com.nukacast.app.tvbox.search.CmsSiteSearcher;
import com.nukacast.app.tvbox.search.SiteSearcher;
import com.nukacast.app.tvbox.search.SpiderSiteSearcher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Short-drama feature service.
 *
 * <p>Two tiers, kept deliberately separate:
 * <ul>
 *   <li><b>Catalog</b> – metadata from a configured read-only provider (for example the reference
 *       vote site). It ships no episodes and no playback urls.</li>
 *   <li><b>Playback</b> – the user's existing TVBox sources. A drama title is cross-searched and
 *       ranked; candidates are shown for confirmation and playback then flows through the normal
 *       site detail/resolve path.</li>
 * </ul>
 *
 * <p>All ids stay strings, responses are cached with a bound, and failures are structured instead
 * of being replaced by invented content.
 */
public final class DramaService {
    private static final int MAX_CACHE_ENTRIES = 16;
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final int MAX_LINES = 40;
    private static final long LINE_DEADLINE_SECONDS = 12L;
    private static final int SITE_PAGE_SIZE = 60;

    private final DramaCatalogRegistry registry;
    private final TvBoxRepository repository;
    private final CmsSiteSearcher cmsSearcher = new CmsSiteSearcher();
    private final SpiderSiteSearcher spiderSearcher;
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Map<String, CacheEntry<DramaSearchResult>> searchCache =
            new LinkedHashMap<String, CacheEntry<DramaSearchResult>>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(
                        Map.Entry<String, CacheEntry<DramaSearchResult>> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            };
    private final Map<String, CacheEntry<DramaDetail>> detailCache =
            new LinkedHashMap<String, CacheEntry<DramaDetail>>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(
                        Map.Entry<String, CacheEntry<DramaDetail>> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            };
    private final Map<String, CacheEntry<DramaEpisode>> episodeCache =
            new LinkedHashMap<String, CacheEntry<DramaEpisode>>(32, 0.75f, true) {
                @Override protected boolean removeEldestEntry(
                        Map.Entry<String, CacheEntry<DramaEpisode>> eldest) {
                    return size() > MAX_CACHE_ENTRIES * 4;
                }
            };

    public DramaService(Context context, TvBoxRepository repository, SpiderManager spiders) {
        this.registry = new DramaCatalogRegistry(context);
        this.repository = repository;
        this.spiderSearcher = new SpiderSiteSearcher(spiders);
    }

    public DramaCatalogRegistry registry() { return registry; }

    public DramaSearchResult search(String providerId, String keyword) {
        String selected = providerId;
        if (selected == null || selected.isEmpty()) {
            List<DramaProviderConfig> enabled = registry.enabledProviders();
            if (enabled.isEmpty()) {
                return DramaSearchResult.failure("", safe(keyword), "provider_missing",
                        "", "还没有启用短剧目录，请先添加目录");
            }
            selected = enabled.get(0).id;
        }
        final String provider = selected;
        DramaProviderConfig config = registry.find(provider);
        if (config == null) {
            return DramaSearchResult.failure(provider, safe(keyword), "provider_not_found",
                    "", "短剧目录不存在");
        }
        String trimmed = keyword == null ? "" : keyword.trim();
        if (trimmed.isEmpty()) {
            return DramaSearchResult.failure(provider, "", "empty_keyword", "", "请输入搜索关键词");
        }
        String cacheKey = provider + "|" + DramaTitleMatcher.normalize(trimmed);
        DramaSearchResult cached = read(searchCache, cacheKey);
        if (cached != null) return cached;

        long startedAt = System.currentTimeMillis();
        try {
            DramaCatalog catalog = registry.catalog(config);
            DramaSearchResult result = catalog.search(trimmed);
            result.providerName = config.name;
            result.elapsedMs = System.currentTimeMillis() - startedAt;
            registry.recordError(provider, "");
            write(searchCache, cacheKey, result);
            return result;
        } catch (Throwable error) {
            String message = message(error);
            registry.recordError(provider, message);
            AppLog.w("短剧", "目录搜索失败 [" + safe(config.name) + "]：" + message, error);
            return DramaSearchResult.failure(provider, trimmed, codeOf(error),
                    error.getClass().getName(), message);
        }
    }

    public DramaDetail detail(String providerId, String dramaId) throws DramaException {
        if (providerId == null || providerId.isEmpty()) {
            throw new DramaException("provider_missing", "缺少短剧目录");
        }
        if (dramaId == null || dramaId.isEmpty()) {
            throw new DramaException("drama_id_missing", "缺少短剧 ID");
        }
        String cacheKey = providerId + "|" + dramaId;
        DramaDetail cached = read(detailCache, cacheKey);
        if (cached != null) return cached;
        DramaCatalog catalog = registry.catalog(providerId);
        try {
            DramaDetail detail = catalog.detail(dramaId);
            registry.recordError(providerId, "");
            write(detailCache, cacheKey, detail);
            return detail;
        } catch (DramaException error) {
            throw error;
        } catch (Throwable error) {
            String message = message(error);
            AppLog.w("短剧", "目录详情失败：" + message, error);
            throw new DramaException(codeOf(error), message, error);
        }
    }

    /** Category browsing for providers that publish listings. */
    public DramaSearchResult browse(String providerId, String categoryId, int page) {
        DramaProviderConfig config = providerId == null || providerId.isEmpty()
                ? firstEnabled() : registry.find(providerId);
        if (config == null) {
            return DramaSearchResult.failure("", "", "provider_missing", "",
                    "还没有启用短剧目录，请先添加目录");
        }
        String cacheKey = config.id + "|browse|" + safe(categoryId) + "|" + Math.max(1, page);
        DramaSearchResult cached = read(searchCache, cacheKey);
        if (cached != null) return cached;
        try {
            DramaSearchResult result = registry.catalog(config)
                    .browse(categoryId, Math.max(1, page));
            result.providerName = config.name;
            write(searchCache, cacheKey, result);
            return result;
        } catch (Throwable error) {
            String message = message(error);
            registry.recordError(config.id, message);
            return DramaSearchResult.failure(config.id, "", codeOf(error),
                    error.getClass().getName(), message);
        }
    }

    /**
     * Resolves one episode of a direct-play provider. Metadata-only catalogs raise a structured
     * error so callers fall back to TVBox line matching instead of showing a broken player.
     */
    public DramaEpisode episode(String providerId, String dramaId, int index) throws DramaException {
        if (providerId == null || providerId.isEmpty()) {
            throw new DramaException("provider_missing", "缺少短剧目录");
        }
        if (dramaId == null || dramaId.isEmpty()) {
            throw new DramaException("drama_id_missing", "缺少短剧 ID");
        }
        String cacheKey = providerId + "|" + dramaId + "|" + index;
        DramaEpisode cached = read(episodeCache, cacheKey);
        if (cached != null) return cached;
        DramaCatalog catalog = registry.catalog(providerId);
        try {
            DramaEpisode episode = catalog.episode(dramaId, index);
            if (episode == null || episode.playUrl.isEmpty()) {
                throw new DramaException("play_not_found", "没有解析到第 " + index + " 集的播放地址");
            }
            episode.direct = true;
            write(episodeCache, cacheKey, episode);
            return episode;
        } catch (DramaException error) {
            throw error;
        } catch (Throwable error) {
            String message = message(error);
            AppLog.w("短剧", "剧集解析失败：" + message, error);
            throw new DramaException(codeOf(error), message, error);
        }
    }

    /**
     * Resolves one episode into something the player can open, plus the title to show. The provider
     * keeps its own header rules (referer/user-agent) so a direct URL is not played bare.
     */
    public DramaPlayResult play(String providerId, String dramaId, int index) throws DramaException {
        DramaProviderConfig config = registry.find(providerId);
        if (config == null) {
            throw new DramaException("provider_missing", "短剧目录不存在");
        }
        DramaEpisode episode = episode(providerId, dramaId, index);
        DramaItem item;
        try {
            item = detail(providerId, dramaId).item;
        } catch (DramaException error) {
            item = new DramaItem();
            item.providerId = providerId;
            item.dramaId = dramaId;
        }
        DramaPlayResult result = new DramaPlayResult();
        result.providerId = providerId;
        result.providerName = config.name;
        result.dramaId = dramaId;
        result.index = episode.index;
        result.episodeName = episode.name;
        result.title = item.title.isEmpty()
                ? episode.name
                : item.title + " · " + episode.name;
        result.url = episode.playUrl;
        if (!episode.headers.isEmpty()) {
            result.headers.putAll(episode.headers);
        } else if (!config.referer.isEmpty()) {
            result.headers.put("Referer", config.referer);
        }
        // Short-drama CMS back ends (非凡 / 电影天堂 and clones) publish player pages, not media: the
        // episode URL points at /share/<hash>. Resolve it, otherwise every episode fails to play.
        String media = com.nukacast.app.tvbox.MaccmsShareResolver.resolve(
                result.url, result.headers.get("Referer"));
        if (media != null) {
            result.url = media;
        }
        if (!result.isPlayable()) {
            throw new DramaException("play_not_found",
                    "该剧集没有可直接播放的地址，请改用播放线路");
        }
        return result;
    }

    private DramaProviderConfig firstEnabled() {
        List<DramaProviderConfig> enabled = registry.enabledProviders();
        return enabled.isEmpty() ? null : enabled.get(0);
    }

    /**
     * Finds playback candidates for a catalog entry by searching the user's enabled TVBox sites.
     * Never auto-selects a title: the caller must surface the ranked candidates.
     */
    public DramaLineResult lines(String providerId, String dramaId, String sourceIdFilter) {
        long startedAt = System.currentTimeMillis();
        DramaLineResult result = new DramaLineResult();
        DramaItem item;
        try {
            item = detail(providerId, dramaId).item;
        } catch (Exception error) {
            result.error = message(error);
            result.elapsedMs = System.currentTimeMillis() - startedAt;
            return result;
        }
        final List<TvBoxConfig.Site> sites = selectSites(sourceIdFilter);
        result.searchedSites = sites.size();
        if (sites.isEmpty()) {
            result.searched = false;
            result.error = "没有启用可搜索的片源，先在设置中添加或启用片源";
            result.elapsedMs = System.currentTimeMillis() - startedAt;
            return result;
        }
        result.searched = true;

        final String title = item.title;
        List<Callable<List<SearchItem>>> calls = new ArrayList<Callable<List<SearchItem>>>();
        for (final TvBoxConfig.Site site : sites) {
            calls.add(new Callable<List<SearchItem>>() {
                @Override public List<SearchItem> call() throws Exception {
                    SiteSearcher searcher = site.type == 3 ? spiderSearcher : cmsSearcher;
                    SearchQuery query = new SearchQuery();
                    query.keyword = title;
                    query.page = 1;
                    query.pageSize = SITE_PAGE_SIZE;
                    return searcher.search(site, query);
                }
            });
        }

        List<SearchItem> collected = new ArrayList<SearchItem>();
        try {
            List<Future<List<SearchItem>>> futures =
                    io.invokeAll(calls, LINE_DEADLINE_SECONDS, TimeUnit.SECONDS);
            for (int i = 0; i < futures.size(); i++) {
                Future<List<SearchItem>> future = futures.get(i);
                if (future.isCancelled()) {
                    result.failedSites++;
                    AppLog.w("短剧", "片源搜索超时 [" + sites.get(i).name + "]");
                    continue;
                }
                try {
                    List<SearchItem> items = future.get();
                    if (items != null) collected.addAll(items);
                } catch (Exception error) {
                    result.failedSites++;
                    AppLog.w("短剧", "片源搜索失败 [" + sites.get(i).name + "]：" + message(error));
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            result.error = "搜索已取消";
            result.elapsedMs = System.currentTimeMillis() - startedAt;
            return result;
        }

        List<DramaLine> ranked = DramaTitleMatcher.rank(collected, title);
        if (ranked.size() > MAX_LINES) ranked = new ArrayList<DramaLine>(ranked.subList(0, MAX_LINES));
        result.lines.addAll(ranked);
        if (result.lines.isEmpty()) {
            result.error = result.failedSites > 0
                    ? "没有匹配的播放线路，且 " + result.failedSites + " 个片源查询失败"
                    : "已启用的片源里没有找到这部剧，可在网页中添加短剧片源后重试";
        } else if (result.failedSites > 0) {
            result.error = result.failedSites + " 个片源查询失败，结果可能不完整";
        }
        result.elapsedMs = System.currentTimeMillis() - startedAt;
        return result;
    }

    public void shutdown() {
        io.shutdownNow();
    }

    List<TvBoxConfig.Site> selectSites(String sourceIdFilter) {
        List<TvBoxConfig.Site> result = new ArrayList<TvBoxConfig.Site>();
        for (TvBoxConfig.Site site : repository.getEnabledSites()) {
            if (!site.canSearch()) continue;
            if (site.type != 0 && site.type != 1 && site.type != 3) continue;
            if (sourceIdFilter != null && !sourceIdFilter.isEmpty()
                    && !sourceIdFilter.equals(site.sourceId)) continue;
            result.add(site);
        }
        return result;
    }

    static String codeOf(Throwable error) {
        if (error instanceof DramaException) return ((DramaException) error).code;
        return ErrorCodes.of(error);
    }

    private synchronized <T> T read(Map<String, CacheEntry<T>> cache, String key) {
        CacheEntry<T> entry = cache.get(key);
        if (entry == null) return null;
        if (entry.expiresAt < System.currentTimeMillis()) {
            cache.remove(key);
            return null;
        }
        return entry.value;
    }

    private synchronized <T> void write(Map<String, CacheEntry<T>> cache, String key, T value) {
        cache.put(key, new CacheEntry<T>(value));
    }

    private static String message(Throwable error) {
        if (error == null) return "未知错误";
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private static final class CacheEntry<T> {
        final T value;
        final long expiresAt;

        CacheEntry(T value) {
            this.value = value;
            this.expiresAt = System.currentTimeMillis() + CACHE_TTL_MS;
        }
    }

    /** Diagnostics snapshot for {@code /api/diagnostics}. */
    public Map<String, Object> diagnostics() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("cachedSearches", searchCache.size());
        result.put("cachedDetails", detailCache.size());
        result.put("searchableSites", selectSites("").size());
        List<Map<String, Object>> providers = new ArrayList<Map<String, Object>>();
        for (DramaProviderConfig provider : registry.providers()) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", provider.id);
            row.put("name", provider.name);
            row.put("host", provider.host());
            row.put("enabled", provider.enabled);
            row.put("error", provider.error);
            providers.add(row);
        }
        result.put("providers", providers);
        return result;
    }
}
