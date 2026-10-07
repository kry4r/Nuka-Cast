package com.nukacast.app.player;

import android.content.Context;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.Tracks;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
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
        /** Selected video track and decoder: what the picture actually is, and who decodes it. */
        public int videoWidth;
        public int videoHeight;
        public int videoBitrate;
        public String videoMime = "";
        public String decoder = "";
        public boolean softwareDecoderPreferred;
        /** Selector parameters and the variants on offer, so a soft picture can be explained. */
        public String selectorParameters = "";
        public String availableVideoTracks = "";
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
    /** Application context, kept so diagnostics can report the decoder preference. */
    private Context appContext;
    private DefaultTrackSelector trackSelector;
    /** True while the best available variant is being forced; cleared when the decoder refuses it. */
    private boolean bestQualityForced;
    /** Video override currently applied, so it is not re-applied on every tracks change. */
    private com.google.android.exoplayer2.source.TrackGroup forcedGroup;
    private int forcedTrack = -1;
    private String title = "";
    private String url = "";
    private String state = "idle";
    private String error = "";
    /** Kept so a failed stream can be restarted with a different decoder. */
    private String currentUrl = "";
    private String currentTitle = "";
    private Map<String, String> currentHeaders = Collections.emptyMap();
    private int retriedWithSoftware;
    /** Codec name seen for the current stream, for diagnostics. */
    private String decoderName = "";
    /** Video track actually being rendered: answers "why does it look soft". */
    private int videoWidth;
    private int videoHeight;
    private int videoBitrate;
    private String videoMime = "";
    /** Variants offered by the current manifest, rebuilt once a second. */
    private String videoTracks = "";
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
    /** How long the forced highest variant may stay without a picture before giving up. */
    private static final long QUALITY_WATCHDOG_MS = 8_000L;

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
            snapshot.videoWidth = videoWidth;
            snapshot.videoHeight = videoHeight;
            snapshot.videoBitrate = videoBitrate;
            snapshot.videoMime = videoMime;
            snapshot.decoder = decoderName;
            DefaultTrackSelector selector = trackSelector;
            if (selector != null) {
                snapshot.selectorParameters = "best=" + bestQualityForced
                        + (forcedGroup == null ? "" : " forced@" + forcedTrack);
            }
            snapshot.availableVideoTracks = videoTracks;
            snapshot.softwareDecoderPreferred = DecoderPreference.prefersSoftware(
                    appState == null ? null : appContext);
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
            // The selected track is reported so "picture looks soft" can be answered with numbers:
            // which resolution and bitrate ExoPlayer actually chose from the adaptive playlist.
            Format video = player.getVideoFormat();
            if (video != null) {
                videoWidth = video.width;
                videoHeight = video.height;
                videoBitrate = video.bitrate == Format.NO_VALUE ? 0 : video.bitrate;
                videoMime = video.sampleMimeType == null ? "" : video.sampleMimeType;
            }
            // Every variant the manifest offers, so "why is it soft" has an answer.
            StringBuilder tracks = new StringBuilder();
            for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
                if (group.getType() != C.TRACK_TYPE_VIDEO) continue;
                for (int i = 0; i < group.length; i++) {
                    Format format = group.getTrackFormat(i);
                    if (tracks.length() > 0) tracks.append(", ");
                    tracks.append(format.width).append('x').append(format.height);
                    if (format.bitrate != Format.NO_VALUE) {
                        tracks.append('@').append(format.bitrate / 1000).append('k');
                    }
                    if (group.isTrackSelected(i)) tracks.append('*');
                }
            }
            videoTracks = tracks.toString();
        }
    }

    private void startPlayer(Context context, String mediaUrl, String mediaTitle,
                             Map<String, String> headers, int startPositionMs) {
        releasePlayer("loading");
        synchronized (lock) {
            title = mediaTitle == null ? "" : mediaTitle;
            if (!mediaUrl.equals(currentUrl)) retriedWithSoftware = 0;
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
            DefaultRenderersFactory renderers = new DefaultRenderersFactory(context);
            // Covers codec *initialisation* failures; a decoder that dies mid-stream is retried by
            // the error handler below, which flips DecoderPreference to software and replays.
            renderers.setEnableDecoderFallback(true);
            renderers.setMediaCodecSelector(DecoderPreference.selector(context));
            // Adaptive streams were being rendered at whatever bitrate the emulated bandwidth meter
            // guessed on this hardware, which on a TV meant the 360p variant on a 1080p panel ("太糊了").
            // Choose the highest bitrate the decoder can actually play instead of adapting downwards.
            DefaultTrackSelector selector = new DefaultTrackSelector(context);
            selector.setParameters(selector.getParameters().buildUpon()
                    .setForceHighestSupportedBitrate(true)
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    .setAllowVideoNonSeamlessAdaptiveness(true)
                    .setMaxVideoSize(Integer.MAX_VALUE, Integer.MAX_VALUE)
                    .setMaxVideoBitrate(Integer.MAX_VALUE)
                    .setMaxVideoFrameRate(Integer.MAX_VALUE)
                    .setExceedVideoConstraintsIfNecessary(true)
                    .build());
            trackSelector = selector;
            // forceHighestSupportedBitrate alone was not enough: ExoPlayer still selected the 320p
            // variant of a manifest offering 1080p at 6.2 Mbps, which is what "太糊了" was. The
            // highest variant is therefore selected explicitly once the manifest is known.
            bestQualityForced = true;
            ExoPlayer created = new ExoPlayer.Builder(context, renderers)
                    .setTrackSelector(selector)
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

                @Override public void onTracksChanged(com.google.android.exoplayer2.Tracks tracks) {
                    forceBestVideoTrack(tracks);
                }

                @Override public void onPlayerError(final PlaybackException failure) {
                    String description = failure.getErrorCodeName() + ": "
                            + (failure.getMessage() == null ? "播放失败" : failure.getMessage());
                    // Measured on the target TV: its AVC hardware decoder accepts the stream and then
                    // dies inside MediaCodec.dequeueInputBuffer. ExoPlayer reports that as
                    // ERROR_CODE_DECODING_FAILED and (without help) stops playback, which the user
                    // sees as being thrown back to the home screen. Retrying once with the software
                    // decoder is what makes such streams playable at all.
                    if (isDecoderFailure(failure) && retriedWithSoftware < 1
                            && !DecoderPreference.prefersSoftware(context)) {
                        retriedWithSoftware++;
                        DecoderPreference.preferSoftware(context);
                        int position = mirroredPositionMs;
                        AppLog.w("播放器", "硬件解码失败，改用软件解码重试："
                                + failure.getErrorCodeName(), failure);
                        releasePlayer("retrying");
                        startPlayer(context, currentUrl, currentTitle, currentHeaders, position);
                        return;
                    }
                    if (isDecoderFailure(failure) && bestQualityForced) {
                        // The best variant needs more than this decoder can do: go back to automatic
                        // selection so the user gets a picture rather than an error.
                        bestQualityForced = false;
                        forcedGroup = null;
                        forcedTrack = -1;
                        if (trackSelector != null) {
                            trackSelector.setParameters(
                                    trackSelector.buildUponParameters().clearOverrides().build());
                        }
                        AppLog.w("播放器", "最高画质无法解码，改回自动选择清晰度（" + description + "）");
                        releasePlayer("retrying-quality");
                        startPlayer(context, currentUrl, currentTitle, currentHeaders, 0);
                        return;
                    }
                    synchronized (lock) {
                        if (player != created) return;
                        state = "error";
                        error = description;
                        AppLog.e("播放器", error, failure);
                        reportProgress();
                        appState.updateActiveMedia("");
                    }
                }
            });
            appContext = context.getApplicationContext();
            currentUrl = mediaUrl;
            currentTitle = mediaTitle == null ? "" : mediaTitle;
            currentHeaders = headers == null ? Collections.<String, String>emptyMap() : headers;
            if (bestQualityForced) {
                mainHandler.removeCallbacks(qualityWatchdog);
                mainHandler.postDelayed(qualityWatchdog, QUALITY_WATCHDOG_MS);
            }
            created.setMediaItem(MediaItem.fromUri(mediaUrl));
            created.prepare();
            if (startPositionMs > 0) created.seekTo(startPositionMs);
            created.play();
            mainHandler.removeCallbacks(progressTicker);
            mainHandler.postDelayed(progressTicker, MIRROR_INTERVAL_MS);
        }
    }

    /**
     * Selects the largest video variant offered by the current media.
     *
     * <p>Measured through {@code /api/player}: a manifest offering 240p…1080p had the 320x184 variant
     * selected, so the picture was soft on a 1080p panel. ExoPlayer's own
     * {@code forceHighestSupportedBitrate} did not change that, hence the explicit override here.
     */
    private void forceBestVideoTrack(com.google.android.exoplayer2.Tracks tracks) {
        DefaultTrackSelector selector = trackSelector;
        if (!bestQualityForced || selector == null || tracks == null) return;
        com.google.android.exoplayer2.source.TrackGroup bestGroup = null;
        int bestTrack = -1;
        int bestPixels = 0;
        int bestBitrate = 0;
        for (com.google.android.exoplayer2.Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_VIDEO) continue;
            for (int i = 0; i < group.length; i++) {
                Format format = group.getTrackFormat(i);
                if (format.width <= 0 || format.height <= 0) continue;
                int pixels = format.width * format.height;
                int bitrate = format.bitrate == Format.NO_VALUE ? 0 : format.bitrate;
                if (pixels <= bestPixels && !(pixels == bestPixels && bitrate > bestBitrate)) continue;
                // Only variants this device can actually decode. Without this check a 1080p variant
                // was forced on a decoder limited to 720p and playback simply buffered forever
                // instead of reporting an error.
                if (!decoderSupports(format)) continue;
                bestPixels = pixels;
                bestBitrate = bitrate;
                bestGroup = group.getMediaTrackGroup();
                bestTrack = i;
            }
        }
        if (bestGroup == null || bestTrack < 0) return;
        if (bestGroup == forcedGroup && bestTrack == forcedTrack) return;
        try {
            // ExoPlayer 2.18 expresses overrides through the selector's parameters.
            selector.setParameters(selector.buildUponParameters()
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                    .addOverride(new com.google.android.exoplayer2.trackselection.TrackSelectionOverride(
                            bestGroup, bestTrack))
                    .build());
            forcedGroup = bestGroup;
            forcedTrack = bestTrack;
            AppLog.i("播放器", "已选择最高画质轨道：" + bestPixels + " 像素");
        } catch (Throwable error) {
            AppLog.d("播放器", "固定最高画质失败，保持自适应：" + error.getClass().getSimpleName());
        }
    }

    /** True when some codec on this device reports support for the format. */
    private static boolean decoderSupports(Format format) {
        try {
            java.util.List<com.google.android.exoplayer2.mediacodec.MediaCodecInfo> decoders =
                    com.google.android.exoplayer2.mediacodec.MediaCodecSelector.DEFAULT
                            .getDecoderInfos(format.sampleMimeType, false, false);
            for (com.google.android.exoplayer2.mediacodec.MediaCodecInfo info : decoders) {
                if (info.isFormatSupported(format)) return true;
            }
            return false;
        } catch (Throwable error) {
            // Unknown capabilities: better to keep ExoPlayer's own choice than to force anything.
            return false;
        }
    }

    /** True when the failure came from the video decoder rather than the source. */
    static boolean isDecoderFailure(PlaybackException failure) {
        return failure != null && isDecoderFailureCode(failure.errorCode);
    }

    /** Error codes that mean the decoder gave up, as opposed to the network or the container. */
    static boolean isDecoderFailureCode(int code) {
        return code == PlaybackException.ERROR_CODE_DECODING_FAILED
                || code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                || code == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED
                || code == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                || code == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES;
    }

    /** Decoder actually in use, reported to diagnostics so the choice is visible. */
    public String decoderName() {
        return decoderName;
    }

    /**
     * Gives up on the forced highest variant when it produces no picture.
     *
     * <p>Some decoders neither fail nor play a variant they cannot handle: they buffer forever. Eight
     * seconds without a single rendered frame is treated the same as a decoder error, and automatic
     * selection takes over so the user gets a picture.
     */
    private final Runnable qualityWatchdog = new Runnable() {
        @Override public void run() {
            synchronized (lock) {
                if (player == null || !bestQualityForced) return;
                if (mirroredPositionMs > 0 || state.equals("playing")) return;
            }
            AppLog.w("播放器", "最高画质长时间无画面，改回自动选择清晰度");
            bestQualityForced = false;
            forcedGroup = null;
            forcedTrack = -1;
            if (trackSelector != null) {
                trackSelector.setParameters(trackSelector.buildUponParameters().clearOverrides().build());
            }
            startPlayer(appContext, currentUrl, currentTitle, currentHeaders, 0);
        }
    };

    private void releasePlayer(String nextState) {
        reportProgress();
        synchronized (lock) {
            mainHandler.removeCallbacks(progressTicker);
            mainHandler.removeCallbacks(qualityWatchdog);
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
