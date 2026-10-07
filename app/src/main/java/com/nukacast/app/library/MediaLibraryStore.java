package com.nukacast.app.library;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.nukacast.app.tvbox.model.MediaDetail;
import com.nukacast.app.tvbox.model.SearchItem;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public final class MediaLibraryStore {
    private static final String PREFS = "media_library";
    private static final String HISTORY = "history";
    private static final String FAVORITES = "favorites";
    private static final int HISTORY_LIMIT = 60;
    private static final int FAVORITES_LIMIT = 100;
    private static final Type ITEM_LIST = new TypeToken<List<LibraryItem>>() {}.getType();

    private final SharedPreferences preferences;
    private final Gson gson = new Gson();
    private String activeKey = "";

    public MediaLibraryStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized List<LibraryItem> history() { return read(HISTORY); }

    public synchronized List<LibraryItem> favorites() { return read(FAVORITES); }

    public synchronized void start(SearchItem item, String playSource, String episodeId,
                                   String episodeName) {
        start(LibraryItem.from(item), playSource, episodeId, episodeName);
    }

    public synchronized void start(MediaDetail detail, String playSource, String episodeId,
                                   String episodeName) {
        start(LibraryItem.from(detail), playSource, episodeId, episodeName);
    }

    public synchronized void start(LibraryItem item, String playSource, String episodeId,
                                   String episodeName) {
        item.playSource = safe(playSource);
        item.episodeId = safe(episodeId);
        item.episodeName = safe(episodeName);
        item.updatedAt = System.currentTimeMillis();
        activeKey = item.stableKey();
        write(HISTORY, LibraryItems.upsert(read(HISTORY), item, HISTORY_LIMIT));
    }

    /** Position jumps smaller than this are not worth a disk write. */
    private static final int PROGRESS_WRITE_STEP_MS = 5000;
    private int lastWrittenPosition;

    /**
     * Records the position of the item being watched.
     *
     * <p>Called once a second, but only writes when the position moved by a few seconds: the rewrite
     * used to happen on every call, i.e. a JSON file write per second for the whole session.
     */
    public synchronized void updateActiveProgress(int positionMs, int durationMs) {
        if (activeKey.isEmpty()) return;
        if (Math.abs(positionMs - lastWrittenPosition) < PROGRESS_WRITE_STEP_MS
                && positionMs != 0 && positionMs < durationMs) {
            return;
        }
        lastWrittenPosition = positionMs;
        List<LibraryItem> history = read(HISTORY);
        for (LibraryItem item : history) {
            if (!activeKey.equals(item.stableKey())) continue;
            item.positionMs = Math.max(0, positionMs);
            item.durationMs = Math.max(0, durationMs);
            item.updatedAt = System.currentTimeMillis();
            write(HISTORY, LibraryItems.upsert(history, item, HISTORY_LIMIT));
            return;
        }
    }

    public synchronized void clearActive() { activeKey = ""; }

    public synchronized boolean toggleFavorite(SearchItem item) {
        return toggleFavorite(LibraryItem.from(item));
    }

    public synchronized boolean toggleFavorite(MediaDetail detail) {
        return toggleFavorite(LibraryItem.from(detail));
    }

    public synchronized boolean isFavorite(String sourceId, String siteKey, String vodId) {
        String key = key(sourceId, siteKey, vodId);
        for (LibraryItem item : read(FAVORITES)) {
            if (key.equals(item.stableKey())) return true;
        }
        return false;
    }

    /**
     * Removes entries from the history or the favourites.
     *
     * @param kind    "history" or "favorite"
     * @param key     a vodId (or a title) to match; ignored when {@code all} is true
     * @param all     true to clear the whole list
     * @return how many entries were removed
     */
    public synchronized int remove(String kind, String key, boolean all) {
        String store = "favorite".equals(kind) || "favorites".equals(kind) ? FAVORITES : HISTORY;
        List<LibraryItem> items = read(store);
        if (all) {
            int size = items.size();
            write(store, new ArrayList<LibraryItem>());
            if (HISTORY.equals(store)) clearActive();
            return size;
        }
        if (key == null || key.isEmpty()) return 0;
        int removed = LibraryItems.countMatching(items, key);
        if (removed == 0) return 0;
        write(store, LibraryItems.removeMatching(items, key));
        if (HISTORY.equals(store) && removed > 0) {
            // The entry being watched may have just been deleted; the progress ticker must stop
            // writing to it.
            for (LibraryItem item : items) {
                if (LibraryItems.matches(item, key) && key.equals(activeKey)) {
                    clearActive();
                    break;
                }
            }
        }
        return removed;
    }

    private boolean toggleFavorite(LibraryItem item) {
        List<LibraryItem> favorites = read(FAVORITES);
        String key = item.stableKey();
        for (LibraryItem existing : favorites) {
            if (!key.equals(existing.stableKey())) continue;
            write(FAVORITES, LibraryItems.remove(favorites, key));
            return false;
        }
        item.updatedAt = System.currentTimeMillis();
        write(FAVORITES, LibraryItems.upsert(favorites, item, FAVORITES_LIMIT));
        return true;
    }

    private List<LibraryItem> read(String key) {
        try {
            List<LibraryItem> result = gson.fromJson(preferences.getString(key, "[]"), ITEM_LIST);
            return result == null ? new ArrayList<LibraryItem>() : new ArrayList<LibraryItem>(result);
        } catch (RuntimeException ignored) {
            return new ArrayList<LibraryItem>();
        }
    }

    private void write(String key, List<LibraryItem> value) {
        preferences.edit().putString(key, gson.toJson(value)).apply();
    }

    private static String key(String sourceId, String siteKey, String vodId) {
        return safe(sourceId) + "|" + safe(siteKey) + "|" + safe(vodId);
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
