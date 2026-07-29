package com.nukacast.app.tvbox;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.nukacast.app.tvbox.model.ConfigSource;
import com.nukacast.app.util.Urls;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class SourceStore {
    private static final String PREFS = "tvbox_sources";
    private static final String KEY_SOURCES = "sources";
    private static final String KEY_USER_MANAGED_MIGRATED = "user_managed_sources_v1";
    private static final int MAX_WAREHOUSE_CHILDREN = 64;
    static final String REMOVED_BUILT_IN_URL = "http://xhztv.top/dc";
    private static final Type SOURCE_LIST = new TypeToken<List<ConfigSource>>() {}.getType();

    private final SharedPreferences preferences;
    private final Gson gson = new Gson();

    public SourceStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        removeLegacyBuiltIn();
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
        for (ConfigSource source : sources) normalize(source);
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
        normalize(updated);
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
        List<ConfigSource> remaining = removeTree(sources, id);
        if (remaining.size() == sources.size()) return false;
        save(remaining);
        return true;
    }

    public synchronized List<ConfigSource> synchronizeChildren(
            ConfigSource parent, List<ConfigDecoder.WarehouseEntry> entries) {
        List<ConfigSource> merged = mergeChildren(getSources(), parent, entries);
        save(merged);
        return merged;
    }

    private synchronized void removeLegacyBuiltIn() {
        if (preferences.getBoolean(KEY_USER_MANAGED_MIGRATED, false)) return;
        save(removeBuiltInTree(getSources(), REMOVED_BUILT_IN_URL));
        preferences.edit().putBoolean(KEY_USER_MANAGED_MIGRATED, true).apply();
    }

    static List<ConfigSource> removeBuiltInTree(List<ConfigSource> sources, String url) {
        if (sources == null || sources.isEmpty()) return Collections.emptyList();
        List<ConfigSource> result = new ArrayList<ConfigSource>(sources);
        for (ConfigSource source : sources) {
            if (url.equals(source.url) && !source.isChild()) result = removeTree(result, source.id);
        }
        return result;
    }

    static List<ConfigSource> mergeChildren(List<ConfigSource> current, ConfigSource parent,
                                            List<ConfigDecoder.WarehouseEntry> entries) {
        List<ConfigSource> result = new ArrayList<ConfigSource>();
        List<ConfigSource> oldChildren = new ArrayList<ConfigSource>();
        for (ConfigSource source : current) {
            normalize(source);
            if (parent.id.equals(source.parentId)) oldChildren.add(source);
            else result.add(source);
        }

        Set<String> added = new LinkedHashSet<String>();
        int count = 0;
        for (ConfigDecoder.WarehouseEntry entry : entries) {
            if (count >= MAX_WAREHOUSE_CHILDREN) break;
            String resolved = Urls.resolve(parent.url, entry.url);
            if (!isHttpUrl(resolved) || !added.add(resolved)) continue;
            ConfigSource child = findByUrl(oldChildren, resolved);
            if (child == null) child = new ConfigSource(entry.name, resolved);
            child.name = entry.name;
            child.url = resolved;
            child.kind = ConfigSource.KIND_SINGLE;
            child.parentId = parent.id;
            result.add(child);
            count++;
        }
        Set<String> retainedChildIds = new LinkedHashSet<String>();
        for (ConfigSource source : result) {
            if (parent.id.equals(source.parentId)) retainedChildIds.add(source.id);
        }
        for (ConfigSource oldChild : oldChildren) {
            if (!retainedChildIds.contains(oldChild.id)) result = removeTree(result, oldChild.id);
        }
        return result;
    }

    static List<ConfigSource> removeTree(List<ConfigSource> current, String id) {
        Set<String> removed = treeIds(current, id);
        List<ConfigSource> result = new ArrayList<ConfigSource>();
        for (ConfigSource source : current) {
            if (removed.contains(source.id)) continue;
            result.add(source);
        }
        return result;
    }

    static Set<String> treeIds(List<ConfigSource> current, String id) {
        Set<String> result = new LinkedHashSet<String>();
        result.add(id);
        boolean changed;
        do {
            changed = false;
            for (ConfigSource source : current) {
                if (result.contains(source.parentId) && result.add(source.id)) changed = true;
            }
        } while (changed);
        return result;
    }

    private static ConfigSource findByUrl(List<ConfigSource> values, String url) {
        for (ConfigSource value : values) if (url.equals(value.url)) return value;
        return null;
    }

    private static boolean isHttpUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private static void normalize(ConfigSource source) {
        if (source.kind == null || source.kind.isEmpty()) source.kind = ConfigSource.KIND_SINGLE;
        if (source.parentId == null) source.parentId = "";
        if (source.contentHash == null) source.contentHash = "";
        if (source.error == null) source.error = "";
        if (source.searchError == null) source.searchError = "";
    }

    private void save(List<ConfigSource> sources) {
        preferences.edit().putString(KEY_SOURCES, gson.toJson(sources)).apply();
    }
}
