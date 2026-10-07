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
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.nukacast.app.net.HttpStack;
import com.nukacast.app.core.AppState;
import com.nukacast.app.diagnostics.AppLog;
import com.nukacast.app.net.HttpStack;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import okhttp3.OkHttpClient;

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
    /** Player state as last seen on the main thread; read by HTTP requests. */
    private int mirroredPositionMs;
    private int mirroredDurationMs;
    private boolean mirroredPlaying;
    private final Object lock = new Object();
    private ExoPlayer player;
    private SurfaceHolder surfaceHolder;
    private String title = "";
    private String url = "";
    private String state = "idle";
    private String error = "";
    private int tickCount;

    private final Runnable progressTicker = new Runnable() {
        @Override public void run() {
            mirrorPlayerState();
            if (++tickCount % TICKS_PER_PROGRESS_REPORT == 0) reportProgress();
            synchronized (lock) {
                if (player != null) mainHandler.postDelayed(this, MIRROR_INTERVAL_MS);
            }
        }
    };

    /** How often the main thread refreshes the readable player state. */
    private static final long MIRROR_INTERVAL_MS = 1000L;
    /** Progress callbacks are throttled: the library write must not happen every second. */
    private static final int TICKS_PER_PROGRESS_REPORT = 20;

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

    /**
     * A consistent view of the player, safe to read from any thread.
     *
     * <p>ExoPlayer refuses to be touched from a non-main thread ({@code Player is accessed on the
     * wrong thread}), which is what {@code /api/player} hit: the HTTP request thread called
     * {@code getCurrentPosition()}. Playback state is therefore mirrored whenever the main thread
     * updates it, and this method only reads those mirrored fields.
     */
    public Snapshot snapshot() {
        synchronized (lock) {
            Snapshot snapshot = new Snapshot();
            snapshot.state = state;
            snapshot.title = title;
            snapshot.url = url;
            snapshot.error = error;
            snapshot.positionMs = mirroredPositionMs;
            snapshot.durationMs = mirroredDurationMs;
            snapshot.playing = mirroredPlaying;
            return snapshot;
        }
    }

    /** Called from the main thread to keep the readable copy of the player state current. */
    private void mirrorPlayerState() {
        if (player == null) return;
        long position = player.getCurrentPosition();
        long duration = player.getDuration();
        synchronized (lock) {
            mirroredPositionMs = integerTime(position);
            mirroredDurationMs = duration == C.TIME_UNSET ? 0 : integerTime(duration);
            mirroredPlaying = player.isPlaying();
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
            AppLog.i("播放器", "开始播放：" + (title.isEmpty() ? "未命名媒体" : title));
            appState.updateActiveMedia(title);

            // DefaultHttpDataSource instead of extension-okhttp: that extension is pinned to 2.14.2
            // (the last release supporting API 19) while core/hls/dash are 2.18.5, and its error
            // path calls a constructor that no longer exists. Whenever a CDN answered 403/404, the
            // OkHttp data source raised java.lang.NoSuchMethodError from an ExoPlayer loader thread,
            // which is an uncaught exception and therefore killed the process — the "看一会就闪退"
            // that no retry could survive.
            OkHttpDataSource.Factory http = new OkHttpDataSource.Factory(
                    HttpStack.client(),
                    header(headers, "User-Agent", "NukaCast/0.1 ExoPlayer"),
                    new LinkedHashMap<String, String>(headers));
            // DefaultDataSource still handles file:// and content:// for local media, but every
            // network scheme goes through OkHttp: the platform's HttpsURLConnection cannot negotiate
            // TLS 1.2 on API 19, which made HTTPS media fail with an SSL handshake error.
            DefaultDataSource.Factory dataSource = new DefaultDataSource.Factory(context, http);
            ExoPlayer created = new ExoPlayer.Builder(context)
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSource))
                    .build();
            player = created;
            created.setWakeMode(C.WAKE_MODE_LOCAL);
            mirrorPlayerState();
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
                        AppLog.e("播放器", error, failure);
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
            mainHandler.postDelayed(progressTicker, MIRROR_INTERVAL_MS);
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

    static OkHttpClient httpClient() {
        return HttpStack.client();
    }
}
