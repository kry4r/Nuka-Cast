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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class SearchEngine {
    /** Upper bound on sites searched in one request when the caller did not name sites. */
    public static final int MAX_SEARCH_SITES = 24;
    private static final long SEARCH_DEADLINE_SECONDS = 10;
    private final TvBoxRepository repository;
    private final TitleIndex titleIndex = new TitleIndex();

    /** Titles seen on this device, for turning an initials query into a real one. */
    public TitleIndex titles() { return titleIndex; }
    /** Site tasks run in parallel; the count also tells whether the pool is still busy. */
    private static final int SEARCH_POOL_SIZE = 4;
    private final ExecutorService executor = Executors.newFixedThreadPool(SEARCH_POOL_SIZE);
    private final CmsSiteSearcher cmsSearcher = new CmsSiteSearcher();
    private final SpiderSiteSearcher spiderSearcher;
    private final SpiderManager spiderManager;
    private final StorageLibrary storageLibrary;
    private SiteHealthStore healthStore;

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
        // A query of initials cannot match Chinese titles in a CMS database. If this device has
        // already seen a matching title (home lists, earlier results), search for that instead -
        // which is what a user typing "LLDQ" expects to happen - preferring the canonical prefix
        // (流浪地球) over one long sequel, and retrying with the full title if that finds nothing.
        final List<String> initialKeywords = initialKeywords(query);
        SearchResponse response = searchOnce(query, initialKeywords.isEmpty()
                ? "" : initialKeywords.get(0), startedAt);
        if (response.items.isEmpty() && initialKeywords.size() > 1) {
            AppLog.i("搜索", "首字母结果为空，改按“" + initialKeywords.get(1) + "”再搜一次");
            SearchQuery retry = new SearchQuery();
            retry.keyword = initialKeywords.get(1);
            retry.sourceId = query.sourceId;
            retry.siteKeys = query.siteKeys;
            retry.page = query.page;
            retry.pageSize = query.pageSize;
            SearchResponse second = searchOnce(retry, "", startedAt);
            if (!second.items.isEmpty()) {
                List<SearchItem> combined = new ArrayList<SearchItem>(response.items);
                combined.addAll(second.items);
                List<List<SearchItem>> groups = new ArrayList<List<SearchItem>>();
                groups.add(combined);
                response.items.clear();
                response.items.addAll(SearchResultMerger.merge(groups, query.pageSize));
                response.searchedSites += second.searchedSites;
                response.failedSites += second.failedSites;
                response.partial = response.partial || second.partial;
            }
        }
        return response;
    }

    private SearchResponse searchOnce(final SearchQuery query, String expandedKeyword,
                                      long startedAt) throws InterruptedException {
        final String initialsExpanded = expandedKeyword == null || expandedKeyword.isEmpty()
                ? null : expandedKeyword;
        if (initialsExpanded != null) query.keyword = initialsExpanded;
        final List<TvBoxConfig.Site> sites = selectedSites(query);
        List<Callable<SiteOutcome>> calls = new ArrayList<Callable<SiteOutcome>>();
        for (final TvBoxConfig.Site site : sites) {
            calls.add(new Callable<SiteOutcome>() {
                @Override public SiteOutcome call() {
                    inFlight.incrementAndGet();
                    try {
                        SiteSearcher searcher = site.type == 3 ? spiderSearcher : cmsSearcher;
                        return SiteOutcome.success(site, searcher.search(site, query));
                    } catch (Throwable error) {
                        return SiteOutcome.failure(site, error);
                    } finally {
                        inFlight.decrementAndGet();
                    }
                }
            });
        }

        List<Future<SiteOutcome>> futures = executor.invokeAll(calls, SEARCH_DEADLINE_SECONDS, TimeUnit.SECONDS);
        SearchResponse response = new SearchResponse();
        response.keyword = query.keyword;
        response.expandedKeyword = initialsExpanded == null ? "" : initialsExpanded;
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
        // Remember the titles that came back: they are what makes the next initials query work.
        for (SearchItem item : response.items) titleIndex.add(item.name);
        response.elapsedMs = System.currentTimeMillis() - startedAt;
        response.partial |= response.failedSites > 0;
        if (query.sourceId != null && !query.sourceId.isEmpty()
                && !query.sourceId.startsWith("storage:") && !sites.isEmpty()) {
            repository.recordSearchOutcome(query.sourceId, response.elapsedMs,
                    successfulSiteCount, sites.size());
        }
        return response;
    }

    /** Recorded verdicts decide which sites are worth the budget; absent data means "try it". */
    public void useHealthStore(SiteHealthStore store) {
        this.healthStore = store;
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

    /**
     * Counts site tasks that have not returned yet. Plugin calls run inside a JS runtime or a
     * dex-loaded spider and cannot be interrupted, so a batch that overruns its deadline leaves its
     * threads behind. Once the pool is full of those, a new search would only queue behind them and
     * look like it hung forever, which is exactly what "一直显示搜索中" was.
     */
    private final java.util.concurrent.atomic.AtomicInteger inFlight =
            new java.util.concurrent.atomic.AtomicInteger();

    /** True when every search thread is still busy with an earlier batch. */
    boolean poolSaturated() {
        return inFlight.get() >= SEARCH_POOL_SIZE;
    }

    /**
     * Returns the real title to search for when {@code query.keyword} is initials and this device
     * knows a matching title, or null when the query should be used as typed.
     */
    String expandInitials(SearchQuery query) {
        if (query == null || !PinyinInitials.isInitialQuery(query.keyword)) return null;
        List<String> candidates = titleIndex.match(query.keyword, 3);
        if (candidates.isEmpty()) return null;
        String title = candidates.get(0);
        AppLog.i("搜索", "首字母 " + query.keyword + " → 按“" + title + "”搜索");
        query.keyword = title;
        return title;
    }

    /**
     * The keyword an initials query should really search for.
     *
     * <p>"LLDQ" with 流浪地球之大夏战狼 in the index must search <em>流浪地球</em>, not that one long
     * sequel: the canonical prefix matches every site that carries any 流浪地球 title. The full title
     * is kept as a second attempt for indexes that hold nothing but long names.
     */
    private List<String> initialKeywords(SearchQuery query) {
        List<String> keywords = new ArrayList<String>();
        String exact = expandInitials(query);
        if (exact == null) return keywords;
        keywords.add(canonicalTitle(exact));
        if (!keywords.get(0).equals(exact)) keywords.add(exact);
        return keywords;
    }

    /** Trims a title at the first subtitle/season marker: 流浪地球之大夏战狼 → 流浪地球. */
    static String canonicalTitle(String title) {
        if (title == null) return "";
        String value = title.trim();
        int cut = value.length();
        for (String marker : new String[]{"：", ":", "（", "(", "·", "之", " ", "第"}) {
            int index = value.indexOf(marker);
            if (index >= 2 && index < cut) cut = index;
        }
        String canonical = value.substring(0, cut).replaceAll("[0-9]+$", "").trim();
        return canonical.length() >= 2 ? canonical : value;
    }

    private List<TvBoxConfig.Site> selectedSites(SearchQuery query) {
        List<TvBoxConfig.Site> sites = selectSites(repository.getEnabledSites(), query);
        boolean saturated = poolSaturated();
        List<TvBoxConfig.Site> usable = new ArrayList<TvBoxConfig.Site>();
        boolean paused = spiderManager != null && spiderManager.pausedForMemory();
        int skippedBroken = 0;
        int skippedUnhealthy = 0;
        for (TvBoxConfig.Site site : sites) {
            // Plugin sites are dropped entirely while the app is shedding memory, and skipped when
            // their plugin cannot load here (Dalvik verifier, JAR hash mismatch).
            boolean unsupportedPlugin = site.type == 3
                    && com.nukacast.app.spider.SpiderManager.isJarSpiderSite(
                            site.jar, site.globalSpider, site.api)
                    && !com.nukacast.app.spider.SpiderManager.jarSpidersSupported();
            if (spiderManager != null && site.type == 3
                    && (paused || saturated || spiderManager.compatibility().isUnsupported(site)
                        || unsupportedPlugin)) {
                skippedBroken++;
                continue;
            }
            if (!query.forceSites && healthStore != null && healthStore.isKnownBad(site)) {
                // Measured as unusable minutes ago: searching it again spends the deadline and a
                // plugin runtime on a site that will not answer.
                skippedUnhealthy++;
                continue;
            }
            usable.add(site);
        }
        if (skippedUnhealthy > 0 || skippedBroken > 0) {
            AppLog.i("搜索", "跳过站点：体检不可用 " + skippedUnhealthy + " · 本机不支持或线程已占满 "
                    + skippedBroken + (saturated ? "（上一次搜索仍有站点未返回）" : ""));
        }
        List<TvBoxConfig.Site> limited = limitFanOut(usable, query);
        if (!query.forceSites && healthStore != null && limited.size() > 1) {
            // Sites already known to answer go first, so a deadline that cuts the batch short is
            // spent on sites that produce results.
            final List<String> good = new ArrayList<String>();
            for (TvBoxConfig.Site site : limited) {
                if (healthStore.isKnownGood(site)) good.add(site.key);
            }
            if (!good.isEmpty() && good.size() < limited.size()) {
                Collections.sort(limited, new java.util.Comparator<TvBoxConfig.Site>() {
                    @Override public int compare(TvBoxConfig.Site left, TvBoxConfig.Site right) {
                        boolean leftGood = good.contains(left.key);
                        boolean rightGood = good.contains(right.key);
                        if (leftGood == rightGood) return 0;
                        return leftGood ? -1 : 1;
                    }
                });
            }
        }
        return limited;
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
