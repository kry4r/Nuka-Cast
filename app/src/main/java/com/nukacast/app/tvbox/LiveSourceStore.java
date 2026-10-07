package com.nukacast.app.tvbox;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.nukacast.app.tvbox.model.LivePlaylist;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persists user-managed live playlists so IPTV m3u/txt lists can be added without crafting a TVBox
 * config. Removal stays removed: nothing here is silently restored or re-added.
 */
public final class LiveSourceStore {
    private static final String PREFS = "live_playlists";
    private static final String KEY_PLAYLISTS = "playlists";
    private static final int MAX_PLAYLISTS = 32;
    private static final Type TYPE = new TypeToken<List<LivePlaylist>>() {}.getType();

    private final SharedPreferences preferences;
    private final Gson gson = new Gson();

    public LiveSourceStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized List<LivePlaylist> all() {
        String json = preferences.getString(KEY_PLAYLISTS, "[]");
        List<LivePlaylist> playlists;
        try {
            playlists = gson.fromJson(json, TYPE);
        } catch (RuntimeException ignored) {
            playlists = null;
        }
        if (playlists == null) return Collections.emptyList();
        List<LivePlaylist> result = new ArrayList<LivePlaylist>();
        for (LivePlaylist playlist : playlists) {
            if (playlist == null || playlist.id == null || playlist.id.isEmpty()) continue;
            if (playlist.url == null) playlist.url = "";
            if (playlist.name == null || playlist.name.isEmpty()) playlist.name = playlist.host();
            result.add(playlist);
        }
        return result;
    }

    public List<LivePlaylist> enabled() {
        List<LivePlaylist> result = new ArrayList<LivePlaylist>();
        for (LivePlaylist playlist : all()) {
            if (playlist.enabled) result.add(playlist);
        }
        return result;
    }

    public synchronized LivePlaylist add(String name, String url) {
        LivePlaylist candidate = LivePlaylist.of(name, url);
        if (!candidate.looksLikePlaylist()) {
            throw new IllegalArgumentException("直播源必须是 http 或 https 地址");
        }
        try {
            candidate.url = com.nukacast.app.net.UrlNormalizer.normalize(candidate.url);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(error.getMessage());
        }
        List<LivePlaylist> playlists = all();
        for (LivePlaylist existing : playlists) {
            if (existing.url.equalsIgnoreCase(candidate.url)) return existing;
        }
        if (playlists.size() >= MAX_PLAYLISTS) {
            throw new IllegalStateException("直播源数量已达上限（" + MAX_PLAYLISTS + "）");
        }
        playlists.add(candidate);
        save(playlists);
        return candidate;
    }

    public synchronized boolean remove(String id) {
        List<LivePlaylist> playlists = all();
        List<LivePlaylist> remaining = new ArrayList<LivePlaylist>();
        boolean removed = false;
        for (LivePlaylist playlist : playlists) {
            if (playlist.id.equals(id)) {
                removed = true;
                continue;
            }
            remaining.add(playlist);
        }
        if (removed) save(remaining);
        return removed;
    }

    /**
     * Sets a playlist's EPG template ({@code {name}} / {@code {date}} are substituted per channel).
     *
     * <p>Playlists rarely declare one, so without this the TV has no programme list at all; the value
     * can also be empty to fall back to the built-in template.
     */
    public synchronized boolean setEpg(String id, String epg) {
        List<LivePlaylist> playlists = all();
        for (LivePlaylist playlist : playlists) {
            if (!playlist.id.equals(id)) continue;
            playlist.epg = epg == null ? "" : epg.trim();
            playlist.updatedAt = System.currentTimeMillis();
            save(playlists);
            return true;
        }
        return false;
    }

    public synchronized boolean setEnabled(String id, boolean enabled) {
        List<LivePlaylist> playlists = all();
        for (LivePlaylist playlist : playlists) {
            if (!playlist.id.equals(id)) continue;
            if (playlist.enabled == enabled) return true;
            playlist.enabled = enabled;
            playlist.updatedAt = System.currentTimeMillis();
            save(playlists);
            return true;
        }
        return false;
    }

    public synchronized void recordError(String id, String error) {
        List<LivePlaylist> playlists = all();
        for (LivePlaylist playlist : playlists) {
            if (!playlist.id.equals(id)) continue;
            playlist.error = error == null ? "" : error;
            playlist.updatedAt = System.currentTimeMillis();
            save(playlists);
            return;
        }
    }

    public synchronized boolean contains(String url) {
        if (url == null) return false;
        for (LivePlaylist playlist : all()) {
            if (url.equalsIgnoreCase(playlist.url)) return true;
        }
        return false;
    }

    private void save(List<LivePlaylist> playlists) {
        preferences.edit().putString(KEY_PLAYLISTS, gson.toJson(playlists)).apply();
    }
}
