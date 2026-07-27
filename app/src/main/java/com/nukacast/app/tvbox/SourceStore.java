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
    private static final Type SOURCE_LIST = new TypeToken<List<ConfigSource>>() {}.getType();

    private final SharedPreferences preferences;
    private final Gson gson = new Gson();

    public SourceStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ensureDefaults();
    }

    public synchronized List<ConfigSource> getSources() {
        String json = preferences.getString(KEY_SOURCES, "[]");
        List<ConfigSource> sources = gson.fromJson(json, SOURCE_LIST);
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

    private void ensureDefaults() {
        if (!preferences.contains(KEY_SOURCES)) {
            List<ConfigSource> defaults = new ArrayList<ConfigSource>();
            defaults.add(new ConfigSource("巧技", "http://cdn.qiaoji8.com/tvbox.json"));
            save(defaults);
        }
    }

    private void save(List<ConfigSource> sources) {
        preferences.edit().putString(KEY_SOURCES, gson.toJson(sources)).commit();
    }
}
