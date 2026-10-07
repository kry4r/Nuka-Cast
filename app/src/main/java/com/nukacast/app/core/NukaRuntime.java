package com.nukacast.app.core;

import android.content.Context;

import com.nukacast.app.airplay.AirPlayReceiver;
import com.nukacast.app.diagnostics.AppLog;
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
    private final com.nukacast.app.dlna.DlnaRenderer dlnaRenderer;
    private final com.nukacast.app.dlna.DlnaService dlnaService;
    private com.nukacast.app.dlna.DlnaSsdp dlnaSsdp;
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
        // DLNA playback goes through the same player as everything else, so the TV shows the same
        // picture, HUD and controls whether the media came from the remote or from a phone.
        dlnaRenderer = new com.nukacast.app.dlna.DlnaRenderer(new com.nukacast.app.dlna.DlnaRenderer.Sink() {
            @Override public void play(String url, String title) {
                // The player itself records the active media, so the TV's HUD and the web console
                // both show what the phone started.
                playerController.play(getContext(), url, title,
                        java.util.Collections.<String, String>emptyMap());
            }

            @Override public void pause() {
                playerController.pause();
            }

            @Override public void resume() {
                playerController.resume();
            }

            @Override public void stop() {
                playerController.stop();
            }

            @Override public void seekTo(int positionMs) {
                playerController.seekTo(positionMs);
            }

            @Override public int positionMs() {
                return playerController.snapshot().positionMs;
            }

            @Override public int durationMs() {
                return playerController.snapshot().durationMs;
            }

            @Override public void setVolume(int volume0To100) {
                playerController.setVolume(volume0To100 / 100f);
            }
        });
        dlnaService = new com.nukacast.app.dlna.DlnaService(dlnaRenderer);
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
            startDlna();
            controlServer = server;
            state.updateService(AppState.ServiceState.READY, "等待连接");
        } catch (Exception failure) {
            server.stop();
            airPlayReceiver.stop();
            stopDlna();
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
        stopDlna();
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
                // A modal (detail screen, programme guide) is what the viewer is looking at, so its
                // layout is what gets inspected; the page behind it would report no problems at all.
                android.view.View decor = activity.inspectableRootForDebug();
                if (decor == null) {
                    decor = activity.getWindow().getDecorView();
                }
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
    public com.nukacast.app.dlna.DlnaService getDlnaService() { return dlnaService; }
    public com.nukacast.app.dlna.DlnaRenderer getDlnaRenderer() { return dlnaRenderer; }

    /** True once the SSDP announcement is running, i.e. the TV is discoverable as a renderer. */
    public boolean isDlnaRunning() {
        return dlnaSsdp != null && dlnaSsdp.isRunning();
    }

    /**
     * Starts the DLNA announcement.
     *
     * <p>The address comes from the device's own interfaces, so the LOCATION a control point receives
     * is one it can actually reach (the Wi-Fi address, not localhost).
     */
    public synchronized void startDlna() {
        if (dlnaSsdp != null && dlnaSsdp.isRunning()) return;
        String address = lanAddress();
        if (address.isEmpty()) {
            AppLog.w("投屏", "没有局域网地址，DLNA 未启动");
            return;
        }
        com.nukacast.app.dlna.DlnaDescription.Device device =
                new com.nukacast.app.dlna.DlnaDescription.Device();
        device.friendlyName = dlnaFriendlyName();
        device.uuid = dlnaUuid();
        device.modelName = deviceProfile.model;
        device.modelNumber = "Android " + deviceProfile.androidVersion;
        device.serial = deviceProfile.manufacturer + " " + deviceProfile.model;
        device.baseUrl = "http://" + address + ":" + CONTROL_PORT;
        dlnaSsdp = new com.nukacast.app.dlna.DlnaSsdp(device,
                device.baseUrl + "/dlna/description.xml", device.uuid);
        dlnaSsdp.start();
    }

    public synchronized void stopDlna() {
        if (dlnaSsdp != null) {
            dlnaSsdp.stop();
            dlnaSsdp = null;
        }
    }

    /** Device name as shown in a phone's cast list; matches the AirPlay name. */
    public String dlnaFriendlyName() {
        String name = AirPlayReceiver.sharedDeviceName(context);
        return name == null || name.isEmpty() ? "NukaCast" : name;
    }

    /** Stable UDN, derived from the same identity AirPlay uses so both stay consistent. */
    public String dlnaUuid() {
        String deviceId = AirPlayReceiver.sharedDeviceId(context);
        String seed = deviceId == null || deviceId.isEmpty() ? dlnaFriendlyName() : deviceId;
        return java.util.UUID.nameUUIDFromBytes(("nukacast-dlna:" + seed)
                .getBytes(java.nio.charset.Charset.forName("UTF-8"))).toString();
    }

    /** The LAN address of the device, or an empty string when there is none. */
    public String lanAddress() {
        try {
            for (java.net.NetworkInterface network
                    : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (java.net.InterfaceAddress address : network.getInterfaceAddresses()) {
                    java.net.InetAddress inet = address.getAddress();
                    if (inet == null || inet.isLoopbackAddress()) continue;
                    if (inet instanceof java.net.Inet4Address) return inet.getHostAddress();
                }
            }
        } catch (Exception error) {
            AppLog.d("投屏", "读取局域网地址失败：" + error.getClass().getSimpleName());
        }
        return "";
    }

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
