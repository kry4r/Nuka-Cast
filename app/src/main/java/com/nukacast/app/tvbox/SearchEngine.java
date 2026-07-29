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
    private static final long SEARCH_DEADLINE_SECONDS = 10;
    private final TvBoxRepository repository;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final CmsSiteSearcher cmsSearcher = new CmsSiteSearcher();
    private final SpiderSiteSearcher spiderSearcher;
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
        List<List<SearchItem>> successfulItems = new ArrayList<List<SearchItem>>();
        if (storageLibrary != null && (query.sourceId == null || query.sourceId.isEmpty()
                || query.sourceId.startsWith("storage:"))) {
            successfulItems.add(storageLibrary.search(query));
        }
        for (int i = 0; i < futures.size(); i++) {
            Future<SiteOutcome> future = futures.get(i);
            if (future.isCancelled()) {
                TvBoxConfig.Site site = sites.get(i);
                AppLog.w("搜索", "站点搜索超时 [" + site.name + "]");
                response.failedSites++;
                response.partial = true;
                response.errors.add(new SearchResponse.SiteError(site.key, site.name, "搜索超时"));
                continue;
            }
            try {
                SiteOutcome outcome = future.get();
                if (outcome.error != null) {
                    AppLog.w("搜索", "站点搜索失败 [" + outcome.site.name + "]："
                            + message(outcome.error), outcome.error);
                    response.failedSites++;
                    response.errors.add(new SearchResponse.SiteError(
                            outcome.site.key, outcome.site.name, message(outcome.error)));
                    continue;
                }
                successfulItems.add(outcome.items);
                successfulSiteCount++;
            } catch (Exception error) {
                AppLog.w("搜索", "搜索任务失败：" + message(error), error);
                response.failedSites++;
                response.partial = true;
            }
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

    private List<TvBoxConfig.Site> selectedSites(SearchQuery query) {
        return selectSites(repository.getEnabledSites(), query);
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
