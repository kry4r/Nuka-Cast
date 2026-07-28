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
        public int videoWidth;
        public int videoHeight;
        public long videoConfigPackets;
        public long videoKeyFrames;
        public long decoderInputs;
        public long decoderOutputs;
        public long decoderFormatChanges;
        public String decoderName;
        public boolean decoderSoftwareFallback;
    }

    private final AppState appState;
    private final Runnable onSessionStart;
    private final NativeAirPlayBridge bridge;
    private final AirPlayPublisher publisher;
    private final H264VideoRenderer video = new H264VideoRenderer();
    private final PcmAudioRenderer audio = new PcmAudioRenderer();
    private final AirPlaySessionState session = new AirPlaySessionState();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile String state = "stopped";
    private volatile String error = "";
    private volatile boolean stopped;
    private boolean restartQueued;

    public AirPlayReceiver(Context context, AppState appState, Runnable onSessionStart) {
        this.appState = appState;
        this.onSessionStart = onSessionStart;
        this.bridge = new NativeAirPlayBridge(this);
        this.publisher = new AirPlayPublisher(context);
        watchdog.scheduleWithFixedDelay(new Runnable() {
            @Override public void run() { checkIdle(); }
        }, 2, 2, TimeUnit.SECONDS);
    }

    public synchronized void start() throws Exception {
        if ("ready".equals(state)) return;
        stopped = false;
        state = "starting";
        error = "";
        try {
            bridge.start();
            publisher.start(bridge.port(), bridge.publicKey());
            session.receiverStarted();
            state = "ready";
            appState.updateActiveMedia("");
        } catch (Exception failure) {
            publisher.stop();
            bridge.stop();
            state = "error";
            error = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            throw failure;
        }
    }

    public synchronized void stop() {
        stopped = true;
        session.receiverStopped();
        publisher.stop();
        bridge.stop();
        state = "stopped";
        appState.updateActiveMedia("");
        video.flush();
        audio.flush();
    }

    public void disconnectSession() {
        endSession();
        scheduleRestart();
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
        snapshot.error = error.isEmpty() ? video.error() : error;
        snapshot.sessionActive = session.isActive();
        snapshot.videoFrames = video.received();
        snapshot.videoDrops = video.dropped();
        snapshot.audioPackets = audio.packets();
        snapshot.audioDrops = audio.dropped();
        snapshot.videoWidth = video.width();
        snapshot.videoHeight = video.height();
        snapshot.videoConfigPackets = video.configPackets();
        snapshot.videoKeyFrames = video.keyFrames();
        snapshot.decoderInputs = video.decoderInputs();
        snapshot.decoderOutputs = video.decoderOutputs();
        snapshot.decoderFormatChanges = video.decoderFormatChanges();
        snapshot.decoderName = video.decoderName();
        snapshot.decoderSoftwareFallback = video.softwareFallback();
        return snapshot;
    }

    @Override public void onVideo(byte[] data, int type, long presentationTimeUs) {
        if (!packetReceived()) return;
        video.offer(data, type, presentationTimeUs);
    }

    @Override public void onAudio(short[] samples, long rtpTimestamp) {
        if (!packetReceived()) return;
        audio.offer(samples);
    }

    @Override public void onSession(boolean active) {
        if (active) {
            packetReceived();
            return;
        }
        endSession();
        scheduleRestart();
    }

    private boolean packetReceived() {
        int result = session.recordPacket(System.currentTimeMillis());
        if (result == AirPlaySessionState.PACKET_REJECTED) return false;
        if (result == AirPlaySessionState.PACKET_STARTED) {
            mainHandler.post(new Runnable() {
                @Override public void run() {
                    if (onSessionStart != null) onSessionStart.run();
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            if (session.isActive()) appState.updateActiveMedia("AirPlay 镜像");
                        }
                    });
                }
            });
        }
        return true;
    }

    private void checkIdle() {
        if (session.isIdle(System.currentTimeMillis(), 2000L)) {
            endSession();
            scheduleRestart();
        }
    }

    private void endSession() {
        session.disconnect();
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
            session.receiverStarted();
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

    private synchronized void scheduleRestart() {
        if (stopped || restartQueued) return;
        restartQueued = true;
        try {
            watchdog.schedule(new Runnable() {
                @Override public void run() {
                    try {
                        restartNativeReceiver();
                    } finally {
                        synchronized (AirPlayReceiver.this) { restartQueued = false; }
                    }
                }
            }, 150L, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            restartQueued = false;
        }
    }
}
