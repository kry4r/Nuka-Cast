package com.nukacast.app.live;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;

/**
 * The programmes the viewer asked to be reminded about, kept across restarts.
 *
 * <p>A reminder that disappears when the app is closed is worse than none: it is exactly the case
 * ("come back at eight") that the viewer set it for.
 */
public final class ReminderStore {

    private static final String PREFS = "programme_reminders";
    private static final String KEY = "items";
    /** Twenty is more than a viewer ever sets, and keeps the stored blob small on a small device. */
    private static final int MAX = 20;
    /** Programmes are rarely longer than this; after it a fired reminder is forgotten. */
    private static final long KEEP_AFTER_START_MS = 4 * 3600_000L;

    private final SharedPreferences preferences;
    private final Gson gson = new Gson();

    public ReminderStore(Context context) {
        this.preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized List<ProgrammeReminder> all() {
        String stored = preferences.getString(KEY, "");
        if (stored == null || stored.isEmpty()) return new ArrayList<ProgrammeReminder>();
        try {
            List<ProgrammeReminder> parsed = gson.fromJson(stored,
                    new TypeToken<List<ProgrammeReminder>>() {}.getType());
            return parsed == null ? new ArrayList<ProgrammeReminder>() : parsed;
        } catch (Exception broken) {
            // A stored blob from an older shape is not worth failing over: start again.
            return new ArrayList<ProgrammeReminder>();
        }
    }

    /** The reminders still ahead, soonest first. */
    public synchronized List<ProgrammeReminder> upcoming() {
        return ReminderPolicy.upcoming(all(), System.currentTimeMillis());
    }

    /** Adds one, or replaces the one for the same channel and moment. */
    public synchronized void add(ProgrammeReminder reminder) {
        if (reminder == null || !reminder.isValid()) return;
        List<ProgrammeReminder> items = ReminderPolicy.pruned(all(), System.currentTimeMillis(),
                KEEP_AFTER_START_MS);
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).key().equals(reminder.key())) {
                items.set(i, reminder);
                save(items);
                return;
            }
        }
        items.add(reminder);
        while (items.size() > MAX) items.remove(0);
        save(items);
    }

    public synchronized boolean remove(String key) {
        List<ProgrammeReminder> items = all();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).key().equals(key)) {
                items.remove(i);
                save(items);
                return true;
            }
        }
        return false;
    }

    /** Forgets the reminders whose moment has long passed. */
    public synchronized void prune() {
        List<ProgrammeReminder> kept = ReminderPolicy.pruned(all(), System.currentTimeMillis(),
                KEEP_AFTER_START_MS);
        if (kept.size() != all().size()) save(kept);
    }

    private void save(List<ProgrammeReminder> items) {
        preferences.edit().putString(KEY, gson.toJson(items)).apply();
    }
}
