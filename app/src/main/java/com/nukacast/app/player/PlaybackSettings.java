package com.nukacast.app.player;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The playback choices a viewer can make on the TV's settings page.
 *
 * <p>Kept as a plain preference wrapper with static readers so the values can be read where they are
 * used (the player and the activity) without threading a dependency through both, and so a unit test
 * can check the parsing of stored values.
 */
public final class PlaybackSettings {
    private static final String PREFS = "playback_settings";
    private static final String KEY_AUTO_NEXT = "autoNextEpisode";
    private static final String KEY_PREFER_SOFTWARE = "preferSoftwareDecoder";
    private static final String KEY_QUALITY = "qualityPreference";
    private static final String KEY_SKIP_INTRO = "skipIntroSeconds";
    private static final String KEY_SKIP_OUTRO = "skipOutroSeconds";

    /** Automatic next-episode playback, the behaviour a series viewer expects. */
    public static final boolean DEFAULT_AUTO_NEXT = true;
    public static final boolean DEFAULT_PREFER_SOFTWARE = false;
    /** "auto" | "highest" | "lowest" — what the player asks the stream for. */
    public static final String DEFAULT_QUALITY = "auto";
    /** Steps the 跳过片头 button walks through, in seconds; 0 is off. */
    public static final int[] INTRO_STEPS = {0, 30, 60, 90, 120};
    /** Steps the 跳过片尾 button walks through: the credit roll is usually shorter than an opening. */
    public static final int[] OUTRO_STEPS = {0, 30, 60, 90};

    private final SharedPreferences preferences;

    public PlaybackSettings(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean autoNextEpisode() {
        return preferences.getBoolean(KEY_AUTO_NEXT, DEFAULT_AUTO_NEXT);
    }

    public void setAutoNextEpisode(boolean value) {
        preferences.edit().putBoolean(KEY_AUTO_NEXT, value).apply();
    }

    public boolean preferSoftwareDecoder() {
        return preferences.getBoolean(KEY_PREFER_SOFTWARE, DEFAULT_PREFER_SOFTWARE);
    }

    public void setPreferSoftwareDecoder(boolean value) {
        preferences.edit().putBoolean(KEY_PREFER_SOFTWARE, value).apply();
    }

    public String quality() {
        return normaliseQuality(preferences.getString(KEY_QUALITY, DEFAULT_QUALITY));
    }

    public void setQuality(String value) {
        preferences.edit().putString(KEY_QUALITY, normaliseQuality(value)).apply();
    }

    /** Cycles 自动 → 最高 → 最低 → 自动, which is how the settings button behaves. */
    public String nextQuality() {
        String current = quality();
        String next = "auto".equals(current) ? "highest" : "highest".equals(current) ? "lowest" : "auto";
        setQuality(next);
        return next;
    }

    /**
     * Seconds to jump past at the start of every episode; 0 turns it off.
     *
     * <p>Most Chinese series open with a 1–2 minute recap, and hopping over it on each episode is what
     * every mainstream player offers.
     */
    public int skipIntroSeconds() {
        return Math.max(0, preferences.getInt(KEY_SKIP_INTRO, 0));
    }

    public void setSkipIntroSeconds(int seconds) {
        preferences.edit().putInt(KEY_SKIP_INTRO, Math.max(0, seconds)).apply();
    }

    /** Seconds before the end to treat the episode as finished; 0 turns it off. */
    public int skipOutroSeconds() {
        return Math.max(0, preferences.getInt(KEY_SKIP_OUTRO, 0));
    }

    public void setSkipOutroSeconds(int seconds) {
        preferences.edit().putInt(KEY_SKIP_OUTRO, Math.max(0, seconds)).apply();
    }

    /** The next value of a stepping button. */
    public static int nextStep(int[] steps, int current) {
        for (int index = 0; index < steps.length; index++) {
            if (steps[index] == current) return steps[(index + 1) % steps.length];
        }
        return steps[0];
    }

    /** "关" or "跳过 60 秒" — the wording both UIs and the debug API show. */
    public static String skipLabel(int seconds) {
        return seconds <= 0 ? "关" : "跳过 " + seconds + " 秒";
    }

    /** Anything unexpected falls back to automatic selection. */
    static String normaliseQuality(String value) {
        if ("highest".equals(value) || "lowest".equals(value)) return value;
        return "auto";
    }

    /** Chinese label for a stored value, used by the settings page and the debug API. */
    public static String qualityLabel(String value) {
        if ("highest".equals(normaliseQuality(value))) return "最高";
        if ("lowest".equals(normaliseQuality(value))) return "最低";
        return "自动";
    }
}
