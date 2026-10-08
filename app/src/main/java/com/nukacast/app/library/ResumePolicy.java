package com.nukacast.app.library;

/**
 * Where a half-watched episode continues from.
 *
 * <p>Mainstream players carry on where the viewer stopped. This app did not: only the 片库 cards resumed,
 * so opening a series again and pressing 播放 restarted the episode from the beginning.
 *
 * <p>The two thresholds exist so that resuming never gets in the way: a few seconds in is not "watching",
 * and an episode that is nearly over should start again rather than drop the viewer on the credits.
 */
public final class ResumePolicy {

    /** Below this the episode counts as not really started. */
    static final int MIN_RESUME_MS = 30_000;
    /** Within this of the end the episode counts as finished. */
    static final int END_MARGIN_MS = 30_000;

    private ResumePolicy() {}

    /**
     * The position to start at: the remembered one, or 0 to start from the beginning.
     *
     * @param positionMs where the viewer stopped
     * @param durationMs the length of the episode, 0 when it is not known
     */
    public static int resumeFrom(int positionMs, int durationMs) {
        if (positionMs < MIN_RESUME_MS) return 0;
        if (durationMs > 0 && positionMs > durationMs - END_MARGIN_MS) return 0;
        return positionMs;
    }

    /** "12:34" for a position, for the notice shown when playback continues. */
    public static String clock(int positionMs) {
        int totalSeconds = Math.max(0, positionMs) / 1000;
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        if (hours > 0) return hours + ":" + two(minutes) + ":" + two(seconds);
        return minutes + ":" + two(seconds);
    }

    private static String two(int value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }
}
