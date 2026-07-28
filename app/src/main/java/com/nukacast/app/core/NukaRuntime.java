package com.nukacast.app.core;

import android.content.Context;

import com.nukacast.app.airplay.AirPlayReceiver;
import com.nukacast.app.live.LiveService;
import com.nukacast.app.library.MediaLibraryStore;
import com.nukacast.app.player.PlayerController;
import com.nukacast.app.security.PairingManager;
import com.nukacast.app.server.ControlServer;
import com.nukacast.app.spider.SpiderManager;
import com.nukacast.app.storage.StorageLibrary;
import com.nukacast.app.tvbox.SearchEngine;
import com.nukacast.app.tvbox.SourceStore;
import com.nukacast.app.tvbox.TvBoxContentService;
import com.nukacast.app.tvbox.TvBoxRepository;

public final class NukaRuntime {
    public static final int CONTROL_PORT = 9978;

    private final Context context;
    private final AppState state = new AppState();
    private final DeviceProfile deviceProfile;
    private final PairingManager pairingManager;
    private final SourceStore sourceStore;
    private final TvBoxRepository tvBoxRepository;
    private final SpiderManager spiderManager;
    private final StorageLibrary storageLibrary;
    private final SearchEngine searchEngine;
    private final TvBoxContentService contentService;
    private final LiveService liveService;
    private final MediaLibraryStore mediaLibrary;
    private final PlayerController playerController;
    private final AirPlayReceiver airPlayReceiver;
    private ControlServer controlServer;

    public NukaRuntime(Context context) {
        this.context = context.getApplicationContext();
        deviceProfile = DeviceProbe.inspect(this.context);
        pairingManager = new PairingManager(this.context);
        sourceStore = new SourceStore(this.context);
        tvBoxRepository = new TvBoxRepository(this.context, sourceStore);
        spiderManager = new SpiderManager(this.context);
        storageLibrary = new StorageLibrary(this.context);
        searchEngine = new SearchEngine(this.context, tvBoxRepository, spiderManager, storageLibrary);
        contentService = new TvBoxContentService(tvBoxRepository, spiderManager, storageLibrary);
        liveService = new LiveService(tvBoxRepository);
        mediaLibrary = new MediaLibraryStore(this.context);
        playerController = new PlayerController(state, new PlayerController.ProgressListener() {
            @Override public void onProgress(int positionMs, int durationMs) {
                mediaLibrary.updateActiveProgress(positionMs, durationMs);
            }
        });
        airPlayReceiver = new AirPlayReceiver(this.context, state, new Runnable() {
            @Override public void run() {
                playerController.stop();
            }
        });
        state.updateSources(sourceStore.getSources().size(), tvBoxRepository.getEnabledSites().size());
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
    public PairingManager getPairingManager() { return pairingManager; }
    public SourceStore getSourceStore() { return sourceStore; }
    public TvBoxRepository getTvBoxRepository() { return tvBoxRepository; }
    public SearchEngine getSearchEngine() { return searchEngine; }
    public TvBoxContentService getContentService() { return contentService; }
    public StorageLibrary getStorageLibrary() { return storageLibrary; }
    public LiveService getLiveService() { return liveService; }
    public MediaLibraryStore getMediaLibrary() { return mediaLibrary; }
    public PlayerController getPlayerController() { return playerController; }
    public AirPlayReceiver getAirPlayReceiver() { return airPlayReceiver; }

    public String getWebAddress() {
        return "http://" + NetworkAddress.findLanAddress(context) + ":" + CONTROL_PORT;
    }
}
