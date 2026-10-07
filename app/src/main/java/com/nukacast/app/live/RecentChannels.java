package com.nukacast.app.live;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * The channels watched most recently, per live source.
 *
 * <p>Zapping across a playlist of thousands of channels means the handful someone actually watches gets
 * lost again immediately; keeping the last few per source gives the live page a "常看" list, the way
 * set-top boxes and Fongmi do.
 *
 * <p>Stored as one newline-separated string per source. The ordering rules are pure static methods so
 * they can be unit-tested without a device (and so the file stays readable with adb).
 */
public final class RecentChannels {

    /** How many channels are remembered per source. */
    public static final int LIMIT = 12;

    private static final String KEY_PREFIX = "recent|";

    private RecentChannels() {}

    /** Moves {@code channelId} to the front of the source's list. */
    public static void remember(SharedPreferences prefs, String sourceId, String channelId) {
        if (prefs == null || sourceId == null || sourceId.isEmpty()
                || channelId == null || channelId.isEmpty()) {
            return;
        }
        String stored = prefs.getString(KEY_PREFIX + sourceId, "");
        prefs.edit().putString(KEY_PREFIX + sourceId, update(stored, channelId, LIMIT)).apply();
    }

    /** The remembered channel ids, newest first. */
    public static List<String> list(SharedPreferences prefs, String sourceId) {
        if (prefs == null || sourceId == null || sourceId.isEmpty()) return new ArrayList<String>();
        return parse(prefs.getString(KEY_PREFIX + sourceId, ""));
    }

    /** Forgets the list of one source (e.g. when that source is removed). */
    public static void forget(SharedPreferences prefs, String sourceId) {
        if (prefs == null || sourceId == null) return;
        prefs.edit().remove(KEY_PREFIX + sourceId).apply();
    }

    /**
     * The stored form after watching {@code channelId}: it moves to the front, duplicates collapse and
     * the list is capped at {@code limit}.
     */
    public static String update(String stored, String channelId, int limit) {
        List<String> ids = parse(stored);
        ids.remove(channelId);
        ids.add(0, channelId);
        while (ids.size() > limit) ids.remove(ids.size() - 1);
        StringBuilder text = new StringBuilder();
        for (String id : ids) {
            if (text.length() > 0) text.append('\n');
            text.append(id);
        }
        return text.toString();
    }

    /** Channel ids out of their stored form, in order, without duplicates. */
    public static List<String> parse(String stored) {
        List<String> ids = new ArrayList<String>();
        if (stored == null || stored.isEmpty()) return ids;
        for (String id : stored.split("\n")) {
            if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
        }
        return ids;
    }
}
