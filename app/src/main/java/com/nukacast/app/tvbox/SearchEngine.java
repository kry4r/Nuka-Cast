package com.nukacast.app.tvbox;

import android.content.Context;

import com.nukacast.app.diagnostics.AppLog;
import com.nukacast.app.spider.SpiderManager;
import com.nukacast.app.storage.StorageLibrary;
import com.nukacast.app.tvbox.model.SearchItem;
import com.nukacast.app.tvbox.model.SearchQuery;
import com.nukacast.app.tvbox.model.SearchResponse;
import com.nukacast.app.tvbox.model.TvBoxConfig;
import com.nukacast.app.tvbox.search.CmsSiteSearcher;
import com.nukacast.app.tvbox.search.SiteSearcher;
import com.nukacast.app.tvbox.search.SpiderSiteSearcher;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class SearchEngine {
    /** Upper bound on sites searched in one request when the caller did not name sites. */
    static final int MAX_SEARCH_SITES = 24;
    private static final long SEARCH_DEADLINE_SECONDS = 10;
    private final TvBoxRepository repository;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final CmsSiteSearcher cmsSearcher = new CmsSiteSearcher();
    private final SpiderSiteSearcher spiderSearcher;
    private final SpiderManager spiderManager;
    private final StorageLibrary storageLibrary;

    public SearchEngine(Context context, TvBoxRepository repository) {
        this(context, repository, new SpiderManager(context), null);
    }

    public SearchEngine(Context context, TvBoxRepository repository, SpiderManager spiderManager) {
        this(context, repository, spiderManager, null);
    }

    public SearchEngine(Context context, TvBoxRepository repository, SpiderManager spiderManager,
                        StorageLibrary storageLibrary) {
        this.repository = repository;
        this.spiderManager = spiderManager;
        this.spiderSearcher = new SpiderSiteSearcher(spiderManager);
        this.storageLibrary = storageLibrary;
    }

    public SearchEngine(TvBoxRepository repository) {
        this(repositoryContext(repository), repository);
    }

    public SearchResponse search(final SearchQuery query) throws InterruptedException {
        long startedAt = System.currentTimeMillis();
        final List<TvBoxConfig.Site> sites = selectedSites(query);
        List<Callable<SiteOutcome>> calls = new ArrayList<Callable<SiteOutcome>>();
        for (final TvBoxConfig.Site site : sites) {
            calls.add(new Callable<SiteOutcome>() {
                @Override public SiteOutcome call() {
                    try {
                        SiteSearcher searcher = site.type == 3 ? spiderSearcher : cmsSearcher;
                        return SiteOutcome.success(site, searcher.search(site, query));
                    } catch (Throwable error) {
                        return SiteOutcome.failure(site, error);
                    }
                }
            });
        }

        List<Future<SiteOutcome>> futures = executor.invokeAll(calls, SEARCH_DEADLINE_SECONDS, TimeUnit.SECONDS);
        SearchResponse response = new SearchResponse();
        response.keyword = query.keyword;
        response.searchedSites = sites.size();
        int successfulSiteCount = 0;
        int timedOutSites = 0;
        int failedSites = 0;
        List<List<SearchItem>> successfulItems = new ArrayList<List<SearchItem>>();
        if (storageLibrary != null && (query.sourceId == null || query.sourceId.isEmpty()
                || query.sourceId.startsWith("storage:"))) {
            successfulItems.add(storageLibrary.search(query));
        }
        for (int i = 0; i < futures.size(); i++) {
            Future<SiteOutcome> future = futures.get(i);
            if (future.isCancelled()) {
                TvBoxConfig.Site site = sites.get(i);
                // A cancelled task is the deadline doing its job, not a site error worth a stack.
                timedOutSites++;
                AppLog.d("搜索", "站点搜索超时 [" + site.name + "]");
                response.failedSites++;
                response.partial = true;
                response.errors.add(new SearchResponse.SiteError(site.key, site.name, "搜索超时"));
                continue;
            }
            try {
                SiteOutcome outcome = future.get();
                if (outcome.error != null) {
                    if (isCancellation(outcome.error)) {
                        timedOutSites++;
                        AppLog.d("搜索", "站点搜索超时 [" + outcome.site.name + "]");
                        response.errors.add(new SearchResponse.SiteError(
                                outcome.site.key, outcome.site.name, "搜索超时"));
                        response.failedSites++;
                        response.partial = true;
                        continue;
                    }
                    failedSites++;
                    // Per-site detail stays at debug level: the summary below and the diagnostics
                    // payload carry the same information without flooding the log with 100 lines.
                    AppLog.d("搜索", "站点搜索失败 [" + outcome.site.name + "]："
                            + message(outcome.error));
                    response.failedSites++;
                    response.errors.add(new SearchResponse.SiteError(
                            outcome.site.key, outcome.site.name, message(outcome.error)));
                    continue;
                }
                successfulItems.add(outcome.items);
                successfulSiteCount++;
            } catch (Exception error) {
                failedSites++;
                AppLog.d("搜索", "搜索任务失败：" + message(error));
                response.failedSites++;
                response.partial = true;
            }
        }
        if (!sites.isEmpty()) {
            AppLog.i("搜索", "搜索完成 [" + query.keyword + "]：" + sites.size() + " 个站点 → "
                    + successfulSiteCount + " 成功 / " + timedOutSites + " 超时 / "
                    + failedSites + " 失败 · "
                    + (System.currentTimeMillis() - startedAt) + " ms");
        }
        response.items.addAll(SearchResultMerger.merge(successfulItems, query.pageSize));
        response.elapsedMs = System.currentTimeMillis() - startedAt;
        response.partial |= response.failedSites > 0;
        if (query.sourceId != null && !query.sourceId.isEmpty()
                && !query.sourceId.startsWith("storage:") && !sites.isEmpty()) {
            repository.recordSearchOutcome(query.sourceId, response.elapsedMs,
                    successfulSiteCount, sites.size());
        }
        return response;
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    /** True when the failure is the deadline cancelling work rather than a site defect. */
    static boolean isCancellation(Throwable error) {
        if (error instanceof InterruptedException) return true;
        if (error instanceof java.io.InterruptedIOException) return true;
        String name = error == null ? "" : error.getClass().getName();
        return name.endsWith("InterruptedException") || name.endsWith("CancellationException")
                || name.endsWith("InterruptedIOException");
    }

    private List<TvBoxConfig.Site> selectedSites(SearchQuery query) {
        List<TvBoxConfig.Site> sites = selectSites(repository.getEnabledSites(), query);
        if (spiderManager != null) {
            List<TvBoxConfig.Site> usable = new ArrayList<TvBoxConfig.Site>();
            boolean paused = spiderManager.pausedForMemory();
            for (TvBoxConfig.Site site : sites) {
                // Plugin sites are dropped entirely while the app is shedding memory, and skipped
                // when their plugin cannot load here (Dalvik verifier, JAR hash mismatch).
                if (site.type == 3 && (paused || spiderManager.compatibility().isUnsupported(site))) {
                    continue;
                }
                usable.add(site);
            }
            sites = usable;
        }
        return limitFanOut(sites, query);
    }

    /**
     * A config with 139 sites cannot be searched in full on a 1 GB TV: every plugin site costs a
     * JS runtime or a spider instance in native memory, and the old behaviour spent the whole
     * timeout budget creating sessions for sites that never answered. Cheap CMS sites go first, so
     * a truncated search still returns the results users actually saw before.
     */
    static List<TvBoxConfig.Site> limitFanOut(List<TvBoxConfig.Site> sites, SearchQuery query) {
        boolean explicit = query != null && !query.siteKeys.isEmpty();
        if (explicit || sites.size() <= MAX_SEARCH_SITES) return sites;
        List<TvBoxConfig.Site> cheap = new ArrayList<TvBoxConfig.Site>();
        List<TvBoxConfig.Site> plugins = new ArrayList<TvBoxConfig.Site>();
        for (TvBoxConfig.Site site : sites) {
            if (site.type == 3) plugins.add(site);
            else cheap.add(site);
        }
        List<TvBoxConfig.Site> limited = new ArrayList<TvBoxConfig.Site>();
        int pluginQuota = Math.max(0, MAX_SEARCH_SITES - cheap.size());
        limited.addAll(cheap.subList(0, Math.min(cheap.size(), MAX_SEARCH_SITES)));
        limited.addAll(plugins.subList(0, Math.min(plugins.size(), pluginQuota)));
        AppLog.i("搜索", "站点较多，本次搜索 " + limited.size() + "/" + sites.size()
                + " 个（优先普通站点，插件站点每次最多 " + pluginQuota + " 个）");
        return limited;
    }

    static List<TvBoxConfig.Site> selectSites(List<TvBoxConfig.Site> available,
                                               SearchQuery query) {
        List<TvBoxConfig.Site> result = new ArrayList<TvBoxConfig.Site>();
        for (TvBoxConfig.Site site : available) {
            if (!site.canSearch()) continue;
            if (query.sourceId != null && !query.sourceId.isEmpty()
                    && !query.sourceId.equals(site.sourceId)) continue;
            if (!query.siteKeys.isEmpty() && !query.siteKeys.contains(site.key)) continue;
            if (site.type != 0 && site.type != 1 && site.type != 3) continue;
            result.add(site);
        }
        return result;
    }

    private static Context repositoryContext(TvBoxRepository repository) {
        return repository.getContext();
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static final class SiteOutcome {
        final TvBoxConfig.Site site;
        final List<SearchItem> items;
        final Throwable error;

        private SiteOutcome(TvBoxConfig.Site site, List<SearchItem> items, Throwable error) {
            this.site = site;
            this.items = items;
            this.error = error;
        }

        static SiteOutcome success(TvBoxConfig.Site site, List<SearchItem> items) {
            return new SiteOutcome(site, items, null);
        }

        static SiteOutcome failure(TvBoxConfig.Site site, Throwable error) {
            return new SiteOutcome(site, new ArrayList<SearchItem>(), error);
        }
    }
}
