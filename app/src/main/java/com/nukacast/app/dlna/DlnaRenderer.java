package com.nukacast.app.dlna;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The transport state of the DLNA renderer.
 *
 * <p>Holds what a control point asks about: what is playing, in which state, how far along, and how
 * loud. Kept separate from the player so the AVTransport rules (which actions are legal in which
 * state, what the replies must contain) can be tested without a device.
 */
public final class DlnaRenderer {
    /** What the renderer needs from the app: actual playback. */
    public interface Sink {
        void play(String url, String title);

        void pause();

        void resume();

        void stop();

        void seekTo(int positionMs);

        /** Current position in milliseconds, or 0. */
        int positionMs();

        /** Duration in milliseconds, or 0 when unknown (live streams). */
        int durationMs();

        void setVolume(int volume0To100);
    }

    public static final String STOPPED = "STOPPED";
    public static final String PLAYING = "PLAYING";
    public static final String PAUSED = "PAUSED_PLAYBACK";
    public static final String TRANSITIONING = "TRANSITIONING";
    public static final String NO_MEDIA = "NO_MEDIA_PRESENT";

    private final Sink sink;
    private String currentUri = "";
    private String currentMetadata = "";
    private String nextUri = "";
    private String nextMetadata = "";
    private String state = NO_MEDIA;
    private int volume = 60;
    private boolean muted;
    /** When the last action was applied, so the position can be derived while playing. */
    private long positionAnchorAt;
    private int positionAnchorMs;
    private int durationHintMs;

    public DlnaRenderer(Sink sink) {
        this.sink = sink;
    }

    public synchronized String state() {
        return state;
    }

    public synchronized String currentUri() {
        return currentUri;
    }

    public synchronized String currentMetadata() {
        return currentMetadata;
    }

    public synchronized int volume() {
        return volume;
    }

    public synchronized boolean muted() {
        return muted;
    }

    /** Sets the media to play; playback starts when the control point sends Play. */
    public synchronized void setUri(String uri, String metadata) {
        currentUri = uri == null ? "" : uri.trim();
        currentMetadata = metadata == null ? "" : metadata;
        nextUri = "";
        nextMetadata = "";
        state = currentUri.isEmpty() ? NO_MEDIA : STOPPED;
        positionAnchorMs = 0;
        positionAnchorAt = System.currentTimeMillis();
        durationHintMs = 0;
        sink.stop();
    }

    public synchronized void play() {
        if (currentUri.isEmpty()) return;
        if (!isLoaded()) {
            state = PLAYING;
            anchor();
            sink.play(currentUri, titleFromMetadata(currentMetadata, currentUri));
            return;
        }
        state = PLAYING;
        anchor();
        sink.resume();
    }

    public synchronized void pause() {
        if (!PLAYING.equals(state)) return;
        anchor();
        state = PAUSED;
        sink.pause();
    }

    /** True while a URI is loaded (including while paused); false after Stop. */
    public synchronized boolean loaded() {
        return !currentUri.isEmpty() && !NO_MEDIA.equals(state);
    }

    public synchronized void stop() {
        state = currentUri.isEmpty() ? NO_MEDIA : STOPPED;
        positionAnchorMs = 0;
        positionAnchorAt = System.currentTimeMillis();
        sink.stop();
    }

    /** Absolute seek, in milliseconds. */
    public synchronized void seekTo(int positionMs) {
        if (currentUri.isEmpty()) return;
        positionAnchorMs = Math.max(0, positionMs);
        positionAnchorAt = System.currentTimeMillis();
        if (PLAYING.equals(state) || PAUSED.equals(state)) sink.seekTo(positionAnchorMs);
    }

    /** Relative seek by {@code deltaMs}; used by the "+30" buttons of control points. */
    public synchronized void seekBy(int deltaMs) {
        seekTo(positionMs() + deltaMs);
    }

    /** Current position: extrapolated while playing, so it advances between polls. */
    public synchronized int positionMs() {
        int base = positionAnchorMs;
        if (PLAYING.equals(state)) {
            base += (int) Math.max(0, System.currentTimeMillis() - positionAnchorAt);
        }
        int duration = durationMs();
        if (duration > 0 && base > duration) base = duration;
        return Math.max(0, base);
    }

    public synchronized int durationMs() {
        int reported = sink.durationMs();
        if (reported > 0) {
            durationHintMs = reported;
            return reported;
        }
        return durationHintMs;
    }

    public synchronized String title() {
        return titleFromMetadata(currentMetadata, currentUri);
    }

    public synchronized void setVolume(int value) {
        volume = Math.max(0, Math.min(100, value));
        muted = false;
        sink.setVolume(volume);
    }

    public synchronized void setMuted(boolean value) {
        muted = value;
        sink.setVolume(muted ? 0 : volume);
    }

    /** Notification that the player stopped by itself (end of media, or the user pressed back). */
    public synchronized void onPlaybackFinished() {
        state = currentUri.isEmpty() ? NO_MEDIA : STOPPED;
        positionAnchorMs = 0;
        positionAnchorAt = System.currentTimeMillis();
    }

    /** Same URI and not currently playing: the point expects Play to load it. */
    private boolean isLoaded() {
        return PLAYING.equals(state) || PAUSED.equals(state);
    }

    private void anchor() {
        positionAnchorMs = positionMs();
        positionAnchorAt = System.currentTimeMillis();
    }

    /** The title from DIDL-Lite metadata, falling back to the last path segment of the URL. */
    static String titleFromMetadata(String metadata, String uri) {
        Map<String, String> tags = new LinkedHashMap<String, String>();
        if (metadata != null && !metadata.isEmpty()) tags = SoapMessage.elements(metadata);
        String title = tags.containsKey("dc:title") ? tags.get("dc:title")
                : (tags.containsKey("title") ? tags.get("title") : "");
        if (title != null && !title.isEmpty()) return title;
        if (uri == null || uri.isEmpty()) return "";
        String path = uri;
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** Position in the {@code H:MM:SS} form UPnP requires. */
    public static String formatTime(int milliseconds) {
        int seconds = Math.max(0, milliseconds / 1000);
        return String.format(java.util.Locale.ROOT, "%d:%02d:%02d",
                seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }
}
