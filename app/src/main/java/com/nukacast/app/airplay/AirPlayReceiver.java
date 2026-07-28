package com.nukacast.app.airplay;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;

import com.nukacast.app.core.AppState;

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class AirPlayReceiver implements NativeAirPlayBridge.Listener {
    public static final class Snapshot {
        public String state;
        public int port;
        public String error;
        public boolean sessionActive;
        public long videoFrames;
        public long videoDrops;
        public long audioPackets;
        public long audioDrops;
    }

    private final AppState appState;
    private final Runnable onSessionStart;
    private final NativeAirPlayBridge bridge;
    private final AirPlayPublisher publisher;
    private final H264VideoRenderer video = new H264VideoRenderer();
    private final PcmAudioRenderer audio = new PcmAudioRenderer();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile String state = "stopped";
    private volatile String error = "";
    private volatile long lastPacketAt;
    private volatile boolean sessionActive;
    private volatile boolean stopped;

    public AirPlayReceiver(Context context, AppState appState, Runnable onSessionStart) {
        this.appState = appState;
        this.onSessionStart = onSessionStart;
        this.bridge = new NativeAirPlayBridge(this);
        this.publisher = new AirPlayPublisher(context);
        watchdog.scheduleAtFixedRate(new Runnable() {
            @Override public void run() { checkIdle(); }
        }, 2, 2, TimeUnit.SECONDS);
    }

    public synchronized void start() {
        if ("ready".equals(state)) return;
        stopped = false;
        state = "starting";
        error = "";
        try {
            bridge.start();
            publisher.start(bridge.port(), bridge.publicKey());
            state = "ready";
            appState.updateActiveMedia("");
        } catch (Exception failure) {
            publisher.stop();
            bridge.stop();
            state = "error";
            error = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        }
    }

    public synchronized void stop() {
        stopped = true;
        publisher.stop();
        bridge.stop();
        sessionActive = false;
        state = "stopped";
        appState.updateActiveMedia("");
        video.stop();
        audio.stop();
        watchdog.shutdownNow();
    }

    public void disconnectSession() {
        endSession();
        try {
            watchdog.execute(new Runnable() {
                @Override public void run() { restartNativeReceiver(); }
            });
        } catch (RejectedExecutionException ignored) {
            // The application service is already stopping.
        }
    }

    public void attachSurface(SurfaceHolder holder) {
        video.setSurface(holder == null ? null : holder.getSurface());
    }

    public void detachSurface(SurfaceHolder holder) {
        video.setSurface(null);
    }

    public Snapshot snapshot() {
        Snapshot snapshot = new Snapshot();
        snapshot.state = state;
        snapshot.port = bridge.port();
        snapshot.error = error;
        snapshot.sessionActive = sessionActive;
        snapshot.videoFrames = video.received();
        snapshot.videoDrops = video.dropped();
        snapshot.audioPackets = audio.packets();
        snapshot.audioDrops = audio.dropped();
        return snapshot;
    }

    @Override public void onVideo(byte[] data, int type, long presentationTimeUs) {
        packetReceived();
        video.offer(data, type, presentationTimeUs);
    }

    @Override public void onAudio(short[] samples, long rtpTimestamp) {
        packetReceived();
        audio.offer(samples);
    }

    @Override public void onSession(boolean active) {
        if (active) packetReceived();
        else endSession();
    }

    private void packetReceived() {
        lastPacketAt = System.currentTimeMillis();
        if (!sessionActive) {
            sessionActive = true;
            mainHandler.post(new Runnable() {
                @Override public void run() {
                    if (onSessionStart != null) onSessionStart.run();
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            if (sessionActive) appState.updateActiveMedia("AirPlay 镜像");
                        }
                    });
                }
            });
        }
    }

    private void checkIdle() {
        if (sessionActive && System.currentTimeMillis() - lastPacketAt > 4000L) endSession();
    }

    private void endSession() {
        sessionActive = false;
        video.flush();
        audio.flush();
        appState.updateActiveMedia("");
    }

    private synchronized void restartNativeReceiver() {
        if (stopped) return;
        state = "restarting";
        error = "";
        publisher.stop();
        bridge.stop();
        if (stopped) return;
        try {
            bridge.start();
            publisher.start(bridge.port(), bridge.publicKey());
            state = "ready";
        } catch (Exception failure) {
            publisher.stop();
            bridge.stop();
            state = "error";
            error = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            appState.updateActiveMedia("");
        }
    }
}
