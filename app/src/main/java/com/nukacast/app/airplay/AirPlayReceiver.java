package com.nukacast.app.airplay;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;

import com.nukacast.app.core.AppState;
import com.nukacast.app.diagnostics.AppLog;

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
        AppLog.i("AirPlay", "正在启动接收器");
        try {
            bridge.start();
            session.receiverStarted();
            publishOrWait();
            appState.updateActiveMedia("");
        } catch (Exception failure) {
            publisher.stop();
            bridge.stop();
            state = "error";
            error = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            AppLog.e("AirPlay", "接收器启动失败：" + error, failure);
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
        AppLog.i("AirPlay", "接收器已停止");
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
            sessionTransition(session.nativeConnected(System.currentTimeMillis()));
            return;
        }
        endSession();
        scheduleRestart();
    }

    private boolean packetReceived() {
        int result = session.recordPacket(System.currentTimeMillis());
        if (result == AirPlaySessionState.PACKET_REJECTED) return false;
        sessionTransition(result);
        return true;
    }

    private void sessionTransition(int result) {
        if (result == AirPlaySessionState.PACKET_STARTED) {
            AppLog.i("AirPlay", "镜像会话开始接收数据");
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
    }

    private void checkIdle() {
        if (!stopped && !session.isActive() && publisher.needsPublish()) {
            synchronized (this) {
                if (!stopped && !session.isActive()) publishOrWait();
            }
        }
        if (session.isIdle(System.currentTimeMillis(), 2000L)) {
            endSession();
            scheduleRestart();
        }
    }

    private void endSession() {
        boolean wasActive = session.isActive();
        session.disconnect();
        video.flush();
        audio.flush();
        appState.updateActiveMedia("");
        if (wasActive) AppLog.i("AirPlay", "镜像会话已结束");
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
            session.receiverStarted();
            publishOrWait();
        } catch (Exception failure) {
            publisher.stop();
            bridge.stop();
            state = "error";
            error = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            AppLog.e("AirPlay", "接收器重启失败：" + error, failure);
            appState.updateActiveMedia("");
        }
    }

    private void publishOrWait() {
        String previousState = state;
        String previousError = error;
        try {
            publisher.start(bridge.port(), bridge.publicKey());
            state = "ready";
            error = "";
            if (!"ready".equals(previousState)) {
                AppLog.i("AirPlay", "接收器已发布，可被 iOS 发现，端口 " + bridge.port());
            }
        } catch (Exception failure) {
            state = "waiting_network";
            error = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            if (!"waiting_network".equals(previousState) || !error.equals(previousError)) {
                AppLog.w("AirPlay", "等待可用局域网后重新发布：" + error, failure);
            }
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
