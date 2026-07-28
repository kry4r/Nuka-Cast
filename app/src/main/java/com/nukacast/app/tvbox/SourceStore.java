package com.nukacast.app.tvbox;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.nukacast.app.tvbox.model.ConfigSource;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SourceStore {
    private static final String PREFS = "tvbox_sources";
    private static final String KEY_SOURCES = "sources";
    private static final String KEY_DEFAULTS_MIGRATED = "defaults_migrated_v1";
    public static final String DEFAULT_SOURCE_NAME = "饭太硬";
    public static final String DEFAULT_SOURCE_URL = "http://www.饭太硬.com/tv";
    private static final Type SOURCE_LIST = new TypeToken<List<ConfigSource>>() {}.getType();

    private final SharedPreferences preferences;
    private final Gson gson = new Gson();

    public SourceStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ensureDefaults();
    }

    public synchronized List<ConfigSource> getSources() {
        String json = preferences.getString(KEY_SOURCES, "[]");
        List<ConfigSource> sources;
        try {
            sources = gson.fromJson(json, SOURCE_LIST);
        } catch (RuntimeException ignored) {
            sources = null;
        }
        if (sources == null) {
            return Collections.emptyList();
        }
        return new ArrayList<ConfigSource>(sources);
    }

    public synchronized ConfigSource add(String name, String url) {
        if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            throw new IllegalArgumentException("配置地址必须使用 http 或 https");
        }
        List<ConfigSource> sources = getSources();
        for (ConfigSource source : sources) {
            if (url.equals(source.url)) {
                return source;
            }
        }
        ConfigSource source = new ConfigSource(name == null || name.trim().isEmpty() ? "TVBox" : name.trim(), url);
        sources.add(source);
        save(sources);
        return source;
    }

    public synchronized ConfigSource restoreDefault() {
        for (ConfigSource source : getSources()) {
            if (DEFAULT_SOURCE_URL.equals(source.url)) {
                return source;
            }
        }
        return add(DEFAULT_SOURCE_NAME, DEFAULT_SOURCE_URL);
    }

    public synchronized void update(ConfigSource updated) {
        List<ConfigSource> sources = getSources();
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i).id.equals(updated.id)) {
                sources.set(i, updated);
                save(sources);
                return;
            }
        }
        sources.add(updated);
        save(sources);
    }

    public synchronized boolean remove(String id) {
        List<ConfigSource> sources = getSources();
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i).id.equals(id)) {
                sources.remove(i);
                save(sources);
                return true;
            }
        }
        return false;
    }

    private synchronized void ensureDefaults() {
        boolean migrated = preferences.getBoolean(KEY_DEFAULTS_MIGRATED, false);
        List<ConfigSource> sources = getSources();
        if (migrated) return;
        if (shouldRestoreDefault(migrated, sources)) restoreDefault();
        preferences.edit().putBoolean(KEY_DEFAULTS_MIGRATED, true).commit();
    }

    static boolean shouldRestoreDefault(boolean migrated, List<ConfigSource> sources) {
        return !migrated && (sources == null || sources.isEmpty());
    }

    private void save(List<ConfigSource> sources) {
        preferences.edit().putString(KEY_SOURCES, gson.toJson(sources)).commit();
    }
}
