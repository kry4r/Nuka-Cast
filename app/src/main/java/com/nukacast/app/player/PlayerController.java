package com.nukacast.app.player;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.nukacast.app.core.AppState;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PlayerController {
    public interface ProgressListener {
        void onProgress(int positionMs, int durationMs);
    }

    public static final class Snapshot {
        public String state;
        public String title;
        public String url;
        public int positionMs;
        public int durationMs;
        public boolean playing;
        public String error;
    }

    private final AppState appState;
    private final ProgressListener progressListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private ExoPlayer player;
    private SurfaceHolder surfaceHolder;
    private String title = "";
    private String url = "";
    private String state = "idle";
    private String error = "";
    private final Runnable progressTicker = new Runnable() {
        @Override public void run() {
            reportProgress();
            synchronized (lock) {
                if (player != null) mainHandler.postDelayed(this, 10000L);
            }
        }
    };

    public PlayerController(AppState appState) {
        this(appState, null);
    }

    public PlayerController(AppState appState, ProgressListener progressListener) {
        this.appState = appState;
        this.progressListener = progressListener;
    }

    public void attachSurface(final SurfaceHolder holder) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                synchronized (lock) {
                    surfaceHolder = holder;
                    if (player != null) player.setVideoSurfaceHolder(holder);
                }
            }
        });
    }

    public void detachSurface(final SurfaceHolder holder) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                synchronized (lock) {
                    if (surfaceHolder == holder) {
                        surfaceHolder = null;
                        if (player != null) player.clearVideoSurface();
                    }
                }
            }
        });
    }

    public void play(final Context context, final String mediaUrl, final String mediaTitle,
                     final Map<String, String> headers) {
        play(context, mediaUrl, mediaTitle, headers, 0);
    }

    public void play(final Context context, final String mediaUrl, final String mediaTitle,
                     final Map<String, String> headers, final int startPositionMs) {
        if (mediaUrl == null || (!mediaUrl.startsWith("http://") && !mediaUrl.startsWith("https://")
                && !mediaUrl.startsWith("file://"))) {
            throw new IllegalArgumentException("不支持的播放地址");
        }
        mainHandler.post(new Runnable() {
            @Override public void run() {
                startPlayer(context.getApplicationContext(), mediaUrl, mediaTitle,
                        headers == null ? Collections.<String, String>emptyMap() : headers,
                        Math.max(0, startPositionMs));
            }
        });
    }

    public void toggle() {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                synchronized (lock) {
                    if (player == null) return;
                    if (player.isPlaying()) {
                        player.pause();
                        state = "paused";
                    } else {
                        player.play();
                        state = "playing";
                    }
                }
            }
        });
    }

    public void seekBy(final int offsetMs) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                synchronized (lock) {
                    if (player == null) return;
                    long duration = player.getDuration();
                    long target = Math.max(0L, player.getCurrentPosition() + offsetMs);
                    if (duration != C.TIME_UNSET) target = Math.min(duration, target);
                    player.seekTo(target);
                }
            }
        });
    }

    public void stop() {
        mainHandler.post(new Runnable() {
            @Override public void run() { releasePlayer("idle"); }
        });
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            Snapshot snapshot = new Snapshot();
            snapshot.state = state;
            snapshot.title = title;
            snapshot.url = url;
            snapshot.error = error;
            if (player != null) {
                snapshot.positionMs = integerTime(player.getCurrentPosition());
                snapshot.durationMs = player.getDuration() == C.TIME_UNSET ? 0 : integerTime(player.getDuration());
                snapshot.playing = player.isPlaying();
            }
            return snapshot;
        }
    }

    private void startPlayer(Context context, String mediaUrl, String mediaTitle,
                             Map<String, String> headers, int startPositionMs) {
        releasePlayer("loading");
        synchronized (lock) {
            title = mediaTitle == null ? "" : mediaTitle;
            url = mediaUrl;
            error = "";
            state = "loading";
            appState.updateActiveMedia(title);

            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setUserAgent(header(headers, "User-Agent", "NukaCast/0.1 ExoPlayer"))
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(new LinkedHashMap<String, String>(headers));
            ExoPlayer created = new ExoPlayer.Builder(context)
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(http))
                    .build();
            player = created;
            created.setWakeMode(C.WAKE_MODE_LOCAL);
            created.setHandleAudioBecomingNoisy(true);
            if (surfaceHolder != null) created.setVideoSurfaceHolder(surfaceHolder);
            created.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int playbackState) {
                    synchronized (lock) {
                        if (player != created) return;
                        if (playbackState == Player.STATE_BUFFERING) state = "buffering";
                        else if (playbackState == Player.STATE_READY) state = created.isPlaying() ? "playing" : "paused";
                        else if (playbackState == Player.STATE_ENDED) {
                            state = "ended";
                            reportProgress();
                            appState.updateActiveMedia("");
                        }
                    }
                }

                @Override public void onIsPlayingChanged(boolean isPlaying) {
                    synchronized (lock) {
                        if (player == created && created.getPlaybackState() == Player.STATE_READY) {
                            state = isPlaying ? "playing" : "paused";
                        }
                    }
                }

                @Override public void onPlayerError(PlaybackException failure) {
                    synchronized (lock) {
                        if (player != created) return;
                        state = "error";
                        error = failure.getErrorCodeName() + ": "
                                + (failure.getMessage() == null ? "播放失败" : failure.getMessage());
                        reportProgress();
                        appState.updateActiveMedia("");
                    }
                }
            });
            created.setMediaItem(MediaItem.fromUri(mediaUrl));
            created.prepare();
            if (startPositionMs > 0) created.seekTo(startPositionMs);
            created.play();
            mainHandler.removeCallbacks(progressTicker);
            mainHandler.postDelayed(progressTicker, 10000L);
        }
    }

    private void releasePlayer(String nextState) {
        reportProgress();
        synchronized (lock) {
            mainHandler.removeCallbacks(progressTicker);
            if (player != null) {
                player.release();
                player = null;
            }
            state = nextState;
            if ("idle".equals(nextState)) {
                title = "";
                url = "";
                error = "";
                appState.updateActiveMedia("");
            }
        }
    }

    private void reportProgress() {
        if (progressListener == null) return;
        int position;
        int duration;
        synchronized (lock) {
            if (player == null) return;
            position = integerTime(player.getCurrentPosition());
            duration = player.getDuration() == C.TIME_UNSET ? 0 : integerTime(player.getDuration());
        }
        progressListener.onProgress(position, duration);
    }

    private static int integerTime(long value) {
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, value));
    }

    private static String header(Map<String, String> headers, String name, String fallback) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return fallback;
    }
}
