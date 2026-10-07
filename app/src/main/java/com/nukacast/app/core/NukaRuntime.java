package com.nukacast.app.core;

import android.content.Context;

import com.nukacast.app.airplay.AirPlayReceiver;
import com.nukacast.app.drama.DramaService;
import com.nukacast.app.live.LiveService;
import com.nukacast.app.sources.RecommendedSources;
import com.nukacast.app.library.MediaLibraryStore;
import com.nukacast.app.player.PlayerController;
import com.nukacast.app.server.ControlServer;
import com.nukacast.app.spider.SpiderManager;
import com.nukacast.app.storage.StorageLibrary;
import com.nukacast.app.tvbox.SearchEngine;
import com.nukacast.app.tvbox.SourceStore;
import com.nukacast.app.tvbox.TvBoxContentService;
import com.nukacast.app.tvbox.TvBoxRepository;

import java.util.List;
import com.nukacast.app.tvbox.SiteHealthStore;
import com.nukacast.app.tvbox.SiteHealthSweep;
import com.nukacast.app.tvbox.model.TvBoxConfig;

public final class NukaRuntime {
    public static final int CONTROL_PORT = 9978;

    private final Context context;
    private final AppState state = new AppState();
    private final DeviceProfile deviceProfile;
    private final SourceStore sourceStore;
    private final TvBoxRepository tvBoxRepository;
    private final SpiderManager spiderManager;
    private final DramaService dramaService;
    private final RecommendedSources recommendedSources;
    private final StorageLibrary storageLibrary;
    private final SearchEngine searchEngine;
    private final SiteHealthStore siteHealthStore;
    private final SiteHealthSweep siteHealthSweep;
    private final TvBoxContentService contentService;
    private final LiveService liveService;
    private final MediaLibraryStore mediaLibrary;
    private final PlayerController playerController;
    private final AirPlayReceiver airPlayReceiver;
    private ControlServer controlServer;

    public NukaRuntime(Context context) {
        this.context = context.getApplicationContext();
        deviceProfile = DeviceProbe.inspect(this.context);
        sourceStore = new SourceStore(this.context);
        tvBoxRepository = new TvBoxRepository(this.context, sourceStore);
        spiderManager = new SpiderManager(this.context);
        dramaService = new DramaService(this.context, tvBoxRepository, spiderManager);
        recommendedSources = new RecommendedSources(this.context, sourceStore,
                tvBoxRepository.getLiveSourceStore(), dramaService.registry());
        storageLibrary = new StorageLibrary(this.context);
        searchEngine = new SearchEngine(this.context, tvBoxRepository, spiderManager, storageLibrary);
        siteHealthStore = new SiteHealthStore(this.context);
        siteHealthSweep = new SiteHealthSweep(tvBoxRepository, searchEngine, siteHealthStore);
        searchEngine.useHealthStore(siteHealthStore);
        contentService = new TvBoxContentService(tvBoxRepository, spiderManager, storageLibrary);
        contentService.useHealthStore(siteHealthStore);
        contentService.useTitleIndex(searchEngine.titles());
        liveService = new LiveService(tvBoxRepository, tvBoxRepository.getLiveSourceStore());
        mediaLibrary = new MediaLibraryStore(this.context);
        playerController = new PlayerController(state, new PlayerController.ProgressListener() {
            @Override public void onProgress(int positionMs, int durationMs) {
                mediaLibrary.updateActiveProgress(positionMs, durationMs);
                // The activity watches this tick to react to a live stream that died mid-playback;
                // a failed channel must be replaced, not left spinning.
                com.nukacast.app.MainActivity activity = com.nukacast.app.MainActivity.onScreen();
                if (activity != null) activity.onPlaybackTick(positionMs, durationMs);
            }
        });
        airPlayReceiver = new AirPlayReceiver(this.context, state, new Runnable() {
            @Override public void run() {
                playerController.stop();
            }
        });
        contentChanged();
    }

    public synchronized void startServices() throws Exception {
        if (controlServer != null) {
            return;
        }
        state.updateService(AppState.ServiceState.STARTING, "正在启动局域网服务");
        ControlServer server = new ControlServer(context, CONTROL_PORT, this);
        try {
            server.start(5000, false);
            airPlayReceiver.start();
            controlServer = server;
            state.updateService(AppState.ServiceState.READY, "等待连接");
        } catch (Exception failure) {
            server.stop();
            airPlayReceiver.stop();
            controlServer = null;
            throw failure;
        }
    }

    public synchronized void stopServices() {
        if (controlServer != null) {
            controlServer.stop();
            controlServer = null;
        }
        airPlayReceiver.stop();
        state.updateService(AppState.ServiceState.STOPPED, "服务已停止");
    }

    public Context getContext() { return context; }
    public AppState getState() { return state; }
    public DeviceProfile getDeviceProfile() { return deviceProfile; }
    public SourceStore getSourceStore() { return sourceStore; }
    public TvBoxRepository getTvBoxRepository() { return tvBoxRepository; }
    public SearchEngine getSearchEngine() { return searchEngine; }
    public TvBoxContentService getContentService() { return contentService; }
    public SpiderManager getSpiderManager() { return spiderManager; }
    public SiteHealthStore getSiteHealthStore() { return siteHealthStore; }
    public SiteHealthSweep getSiteHealthSweep() { return siteHealthSweep; }
    public DramaService getDramaService() { return dramaService; }
    public RecommendedSources getRecommendedSources() { return recommendedSources; }
    public StorageLibrary getStorageLibrary() { return storageLibrary; }
    public LiveService getLiveService() { return liveService; }
    public MediaLibraryStore getMediaLibrary() { return mediaLibrary; }
    /**
     * Bounds of everything currently visible, for diagnosing "something is cut off".
     *
     * <p>Runs on the UI thread so the numbers match what the user sees.
     */
    public java.util.Map<String, Object> getLayoutReport() {
        final com.nukacast.app.MainActivity activity = com.nukacast.app.MainActivity.onScreen();
        if (activity == null) {
            java.util.Map<String, Object> empty = new java.util.LinkedHashMap<String, Object>();
            empty.put("error", "界面未在前台（可能正在播放或已退出）");
            return empty;
        }
        return activity.onUiThreadNow(new java.util.concurrent.Callable<java.util.Map<String, Object>>() {
            @Override public java.util.Map<String, Object> call() {
                android.view.View decor = activity.getWindow().getDecorView();
                android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
                java.util.Map<String, Object> report = com.nukacast.app.diagnostics.LayoutInspector
                        .report(decor, metrics.widthPixels, metrics.heightPixels);
                report.put("page", activity.currentPageName());
                return report;
            }
        });
    }

    /** Shows a page by name (debug API) and reports which one ended up on screen. */
    public String navigateTo(final String page) {
        final com.nukacast.app.MainActivity activity = com.nukacast.app.MainActivity.onScreen();
        if (activity == null) return "界面未在前台";
        return activity.onUiThreadNow(new java.util.concurrent.Callable<String>() {
            @Override public String call() {
                activity.showPageByName(page);
                return activity.currentPageName();
            }
        });
    }

    /** Scrolls the visible page by {@code delta} pixels (debug API). */
    public boolean scrollBy(final int delta) {
        final com.nukacast.app.MainActivity activity = com.nukacast.app.MainActivity.onScreen();
        if (activity == null) return false;
        return activity.onUiThreadNow(new java.util.concurrent.Callable<Boolean>() {
            @Override public Boolean call() {
                return activity.scrollCurrentPage(delta);
            }
        });
    }

    public PlayerController getPlayerController() { return playerController; }
    public AirPlayReceiver getAirPlayReceiver() { return airPlayReceiver; }

    /**
     * Releases everything that can be rebuilt: playlist catalogs, spider sessions and decoded
     * configuration payloads. Called when the platform reports critical memory pressure, which is
     * the situation that used to end with the process being killed on a 1.5 GB TV.
     */
    public void trimCaches() {
        // Playlist catalogs are the biggest rebuildable structure the app holds; the web console
        // simply re-downloads them when a source is opened again.
        try { liveService.clearCache(); } catch (RuntimeException ignored) {}
        // Plugin sessions are native memory (QuickJS runtimes, DexClassLoader spiders) and are the
        // reason a 1 GB TV kills this process; release them before anything else.
        try { spiderManager.dropSessions("系统内存紧张"); } catch (RuntimeException ignored) {}
        // Note: the site compatibility verdicts are deliberately kept. They are what stops a broken
        // JAR from being downloaded and verified again on every retry.
    }

    /** Package-visible for diagnostics; the spider manager owns the real counters. */
    public String pluginSessionSummary() {
        try {
            return spiderManager.sessionSummary();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    public void contentChanged() {
        liveService.clearCache();
        contentService.retainHomeFailures(tvBoxRepository.getEnabledSites());
        state.updateSources(sourceStore.getSources().size(),
                tvBoxRepository.getEnabledSites().size());
    }

    public void sourceHealthChanged() {
        state.updateSourceHealth(sourceStore.getSources().size(),
                tvBoxRepository.getEnabledSites().size());
    }

    public boolean removeSource(String id) {
        List<TvBoxConfig> removedConfigs = tvBoxRepository.configsForTree(id);
        boolean removed = tvBoxRepository.remove(id);
        if (!removed) return false;
        for (TvBoxConfig config : removedConfigs) spiderManager.forgetForConfig(config);
        contentChanged();
        return true;
    }

    public String getWebAddress() {
        return "http://" + NetworkAddress.findLanAddress(context) + ":" + CONTROL_PORT;
    }
}
