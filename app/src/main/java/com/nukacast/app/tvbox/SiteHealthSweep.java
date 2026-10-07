package com.nukacast.app.tvbox;

import com.nukacast.app.diagnostics.AppLog;
import com.nukacast.app.diagnostics.SessionMarker;
import com.nukacast.app.diagnostics.StageTrace;
import com.nukacast.app.tvbox.model.SearchItem;
import com.nukacast.app.tvbox.model.SearchQuery;
import com.nukacast.app.tvbox.model.SearchResponse;
import com.nukacast.app.tvbox.model.TvBoxConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Measures every site once and remembers the answer.
 *
 * <p>Runs one site at a time on purpose. A community config can hold 140 sites; testing them in
 * parallel is what makes a small TV thrash between spider runtimes, and the point of the sweep is
 * to end up with a short list of sites that work, so the slow path is the correct one here. The
 * sweep is resumable in spirit: each verdict is stored as soon as it is known, so stopping halfway
 * still leaves useful data behind.
 */
public final class SiteHealthSweep {
    private static final String TAG = "站点体检";
    /** Pause between sites: a 140-site sweep should not look like an attack to a shared host. */
    private static final long PACE_MS = 250L;
    private static final String DEFAULT_KEYWORD = "庆余年";
    private static final int DEFAULT_LIMIT = 200;

    public static final class Result {
        public String siteKey = "";
        public String siteName = "";
        public String sourceId = "";
        public int type;
        public boolean ok;
        public int itemCount;
        public long latencyMs;
        public String reason = "";
        public long at;
    }

    public static final class Job {
        public long startedAt;
        public long finishedAt;
        public int total;
        public int done;
        public int ok;
        public int failed;
        public String keyword = "";
        public String currentSite = "";
        public boolean running;
        public boolean cancelled;
        public String error = "";
        public final List<Result> results = new ArrayList<Result>();

        public synchronized Result resultOf(String siteKey) {
            for (Result result : results) {
                if (result.siteKey.equals(siteKey)) return result;
            }
            return null;
        }
    }

    private final TvBoxRepository repository;
    private final SearchEngine engine;
    private final SiteHealthStore store;
    private final Object lock = new Object();
    private Job job = new Job();
    private Thread worker;

    public SiteHealthSweep(TvBoxRepository repository, SearchEngine engine, SiteHealthStore store) {
        this.repository = repository;
        this.engine = engine;
        this.store = store;
        this.store.load();
    }

    public SiteHealthStore store() {
        return store;
    }

    public Job status() {
        synchronized (lock) {
            return job;
        }
    }

    /** Starts a sweep; returns the live job. A second call while running just returns the job. */
    public Job start(int limit, String keyword, boolean pluginSitesOnly, boolean failedOnly) {
        synchronized (lock) {
            if (job.running) return job;
            final Job next = new Job();
            next.startedAt = System.currentTimeMillis();
            next.running = true;
            next.keyword = keyword == null || keyword.trim().isEmpty()
                    ? DEFAULT_KEYWORD : keyword.trim();
            job = next;
            final int max = limit <= 0 ? DEFAULT_LIMIT : limit;
            worker = new Thread(new Runnable() {
                @Override public void run() {
                    sweep(next, max, pluginSitesOnly, failedOnly);
                }
            }, "nukacast-site-sweep");
            worker.setDaemon(true);
            worker.start();
            return next;
        }
    }

    public boolean stop() {
        synchronized (lock) {
            if (!job.running) return false;
            job.cancelled = true;
        }
        Thread running = worker;
        if (running != null) running.interrupt();
        return true;
    }

    private void sweep(Job target, int max, boolean pluginSitesOnly, boolean failedOnly) {
        List<TvBoxConfig.Site> sites = new ArrayList<TvBoxConfig.Site>();
        for (TvBoxConfig.Site site : repository.getEnabledSites()) {
            if (pluginSitesOnly && site.type != 3) continue;
            if (failedOnly) {
                SiteHealthStore.Verdict verdict = store.of(site.key);
                if (verdict == null || verdict.ok) continue;
            }
            sites.add(site);
        }
        // Cheap CMS sites first: if the sweep is stopped early, the useful results are already in.
        Collections.sort(sites, new java.util.Comparator<TvBoxConfig.Site>() {
            @Override public int compare(TvBoxConfig.Site left, TvBoxConfig.Site right) {
                int leftRank = left.type == 3 ? 1 : 0;
                int rightRank = right.type == 3 ? 1 : 0;
                if (leftRank != rightRank) return leftRank - rightRank;
                return String.valueOf(left.name).compareToIgnoreCase(String.valueOf(right.name));
            }
        });
        if (sites.size() > max) sites = sites.subList(0, max);

        synchronized (lock) {
            target.total = sites.size();
        }
        AppLog.i(TAG, "开始体检 " + sites.size() + " 个站点（关键词：" + target.keyword + "）");
        int ok = 0;
        int failed = 0;
        for (TvBoxConfig.Site site : sites) {
            synchronized (lock) {
                if (target.cancelled) {
                    target.running = false;
                    target.finishedAt = System.currentTimeMillis();
                    AppLog.i(TAG, "体检已停止：已完成 " + target.done + "/" + target.total);
                    return;
                }
                target.currentSite = site.name == null ? site.key : site.name;
            }
            Result result = test(site, target.keyword);
            if (result.ok) ok++;
            else failed++;
            store.record(site, result.ok, result.itemCount, result.latencyMs, result.reason);
            synchronized (lock) {
                target.results.add(result);
                target.done++;
                target.ok = ok;
                target.failed = failed;
            }
            SessionMarker.publishPluginSessions("体检 " + target.done + "/" + target.total);
            try {
                Thread.sleep(PACE_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                synchronized (lock) {
                    target.running = false;
                    target.finishedAt = System.currentTimeMillis();
                }
                AppLog.i(TAG, "体检中断：已完成 " + target.done + "/" + target.total);
                return;
            }
        }
        synchronized (lock) {
            target.running = false;
            target.finishedAt = System.currentTimeMillis();
        }
        AppLog.i(TAG, "体检完成：" + target.total + " 个站点 → 可用 " + ok + " / 失败 " + failed);
        StageTrace.component("source", "site_sweep", "sweep", true,
                ok + "/" + target.total + " 可用");
    }

    /** Runs one search against one site and turns the outcome into a verdict. */
    public Result test(TvBoxConfig.Site site, String keyword) {
        Result result = new Result();
        result.siteKey = site.key == null ? "" : site.key;
        result.siteName = site.name == null ? "" : site.name;
        result.sourceId = site.sourceId == null ? "" : site.sourceId;
        result.type = site.type;
        result.at = System.currentTimeMillis();
        SearchQuery query = new SearchQuery();
        query.keyword = keyword == null || keyword.trim().isEmpty() ? DEFAULT_KEYWORD : keyword.trim();
        query.siteKeys.add(result.siteKey);
        query.pageSize = 20;
        query.forceSites = true;
        long startedAt = System.currentTimeMillis();
        try {
            SearchResponse response = engine.search(query);
            result.latencyMs = response.elapsedMs > 0
                    ? response.elapsedMs : System.currentTimeMillis() - startedAt;
            result.itemCount = response.items.size();
            String failure = failureOf(response, result.siteKey);
            if (failure != null) {
                result.ok = false;
                result.reason = failure;
            } else {
                result.ok = true;
                result.reason = "";
            }
        } catch (Throwable error) {
            result.latencyMs = System.currentTimeMillis() - startedAt;
            result.ok = false;
            result.reason = com.nukacast.app.diagnostics.ErrorCodes.message(error);
        }
        if (!result.ok) {
            AppLog.d(TAG, "站点不可用 [" + result.siteName + "]：" + result.reason);
        }
        return result;
    }

    /** The reason a single-site search failed, or null when the site answered. */
    private static String failureOf(SearchResponse response, String siteKey) {
        for (SearchResponse.SiteError error : response.errors) {
            if (siteKey.equals(error.siteKey)) {
                return error.message == null || error.message.isEmpty() ? "站点无响应" : error.message;
            }
        }
        return null;
    }
}
