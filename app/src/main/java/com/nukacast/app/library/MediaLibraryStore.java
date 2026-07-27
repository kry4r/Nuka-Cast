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

    public synchronized void updateActiveProgress(int positionMs, int durationMs) {
        if (activeKey.isEmpty()) return;
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
