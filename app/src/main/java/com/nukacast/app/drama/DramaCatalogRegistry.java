package com.nukacast.app.drama;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.nukacast.app.drama.model.DramaProviderConfig;
import com.nukacast.app.util.Digests;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.HttpUrl;

/**
 * Persists short-drama catalog providers. A provider is only ever added after an explicit user
 * action; removals stay removed and are never silently restored at startup.
 */
public final class DramaCatalogRegistry {
    public static final String SUGGESTED_NAME = "红果短剧榜";
    public static final String SUGGESTED_BASE_URL = "https://vote.252035.xyz";
    private static final String PREFS = "drama_catalogs";
    private static final String KEY_CATALOGS = "catalogs";
    private static final Type LIST_TYPE = new TypeToken<List<DramaProviderConfig>>() {}.getType();

    private final SharedPreferences preferences;
    private final Gson gson = new Gson();
    private final Map<String, DramaCatalog> catalogs = new ConcurrentHashMap<String, DramaCatalog>();

    public DramaCatalogRegistry(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized List<DramaProviderConfig> providers() {
        String json = preferences.getString(KEY_CATALOGS, "[]");
        List<DramaProviderConfig> providers;
        try {
            providers = gson.fromJson(json, LIST_TYPE);
        } catch (RuntimeException ignored) {
            providers = null;
        }
        if (providers == null) return Collections.emptyList();
        List<DramaProviderConfig> result = new ArrayList<DramaProviderConfig>();
        for (DramaProviderConfig provider : providers) {
            if (provider == null || provider.id == null || provider.id.isEmpty()) continue;
            if (provider.baseUrl == null) provider.baseUrl = "";
            if (provider.kind == null || provider.kind.isEmpty()) {
                provider.kind = DramaProviderConfig.KIND_VOTE_CATALOG;
            }
            if (provider.name == null || provider.name.isEmpty()) provider.name = provider.host();
            result.add(provider);
        }
        return result;
    }

    public List<DramaProviderConfig> enabledProviders() {
        List<DramaProviderConfig> result = new ArrayList<DramaProviderConfig>();
        for (DramaProviderConfig provider : providers()) {
            if (provider.enabled) result.add(provider);
        }
        return result;
    }

    public synchronized DramaProviderConfig add(String name, String baseUrl) {
        return add(name, baseUrl, detectKind(baseUrl), "", "");
    }

    /** Adds a provider of an explicit kind (used by the curated source list). */
    public synchronized DramaProviderConfig add(String name, String baseUrl, String kind,
                                                String categoryId, String note) {
        String normalized = normalizeBaseUrl(baseUrl);
        List<DramaProviderConfig> providers = providers();
        for (DramaProviderConfig provider : providers) {
            if (normalized.equalsIgnoreCase(provider.baseUrl)) {
                // Re-adding an existing URL updates its kind/class so a curated entry can be
                // upgraded from a metadata catalog to a playable provider without duplicate rows.
                if (kind != null && !kind.isEmpty() && !kind.equals(provider.kind)) {
                    provider.kind = kind;
                    provider.categoryId = categoryId == null ? "" : categoryId;
                    provider.note = note == null ? "" : note;
                    provider.updatedAt = System.currentTimeMillis();
                    save(providers);
                    catalogs.remove(provider.id);
                }
                return provider;
            }
        }
        DramaProviderConfig provider = new DramaProviderConfig();
        provider.id = Digests.sha256(normalized.getBytes(java.nio.charset.Charset.forName("UTF-8")))
                .substring(0, 16);
        provider.baseUrl = normalized;
        provider.name = name == null || name.trim().isEmpty()
                ? HttpUrl.parse(normalized).host() : name.trim();
        provider.kind = kind == null || kind.isEmpty() ? detectKind(normalized) : kind;
        provider.categoryId = categoryId == null ? "" : categoryId;
        provider.note = note == null ? "" : note;
        provider.builtin = normalized.equalsIgnoreCase(SUGGESTED_BASE_URL);
        provider.enabled = true;
        provider.updatedAt = System.currentTimeMillis();
        providers.add(provider);
        save(providers);
        catalogs.remove(provider.id);
        return provider;
    }

    /** Guesses the provider kind from a pasted URL so users do not have to pick one. */
    static String detectKind(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.toLowerCase(java.util.Locale.ROOT);
        if (value.contains("api.php/provide/vod") || value.contains("/provide/vod")) {
            return DramaProviderConfig.KIND_CMS_DRAMA;
        }
        return DramaProviderConfig.KIND_VOTE_CATALOG;
    }

    public synchronized boolean contains(String baseUrl) {
        if (baseUrl == null) return false;
        String value = baseUrl.trim();
        for (DramaProviderConfig provider : providers()) {
            if (value.equalsIgnoreCase(provider.baseUrl)) return true;
        }
        return false;
    }

    public DramaProviderConfig addSuggested() {
        return add(SUGGESTED_NAME, SUGGESTED_BASE_URL);
    }

    public synchronized boolean remove(String id) {
        List<DramaProviderConfig> providers = providers();
        List<DramaProviderConfig> remaining = new ArrayList<DramaProviderConfig>();
        boolean removed = false;
        for (DramaProviderConfig provider : providers) {
            if (provider.id.equals(id)) {
                removed = true;
                continue;
            }
            remaining.add(provider);
        }
        if (removed) {
            save(remaining);
            catalogs.remove(id);
        }
        return removed;
    }

    public synchronized boolean setEnabled(String id, boolean enabled) {
        List<DramaProviderConfig> providers = providers();
        for (DramaProviderConfig provider : providers) {
            if (!provider.id.equals(id)) continue;
            if (provider.enabled == enabled) return true;
            provider.enabled = enabled;
            provider.updatedAt = System.currentTimeMillis();
            save(providers);
            if (!enabled) catalogs.remove(id);
            return true;
        }
        return false;
    }

    public synchronized void recordError(String id, String error) {
        List<DramaProviderConfig> providers = providers();
        for (DramaProviderConfig provider : providers) {
            if (!provider.id.equals(id)) continue;
            provider.error = error == null ? "" : error;
            provider.updatedAt = System.currentTimeMillis();
            save(providers);
            return;
        }
    }

    public DramaCatalog catalog(String id) throws DramaException {
        DramaProviderConfig provider = find(id);
        if (provider == null) throw new DramaException("provider_not_found", "短剧目录不存在");
        if (!provider.enabled) throw new DramaException("provider_disabled", "短剧目录已停用");
        return catalog(provider);
    }

    public DramaCatalog catalog(DramaProviderConfig provider) throws DramaException {
        DramaCatalog cached = catalogs.get(provider.id);
        if (cached != null && provider.baseUrl.equalsIgnoreCase(cached.config().baseUrl)) {
            return cached;
        }
        DramaCatalog created;
        if (DramaProviderConfig.KIND_CMS_DRAMA.equals(provider.kind)) {
            created = new CmsDramaCatalog(provider);
        } else if (DramaProviderConfig.KIND_VOTE_CATALOG.equals(provider.kind)
                || provider.kind == null || provider.kind.isEmpty()) {
            created = new VoteDramaCatalog(provider);
        } else {
            throw new DramaException("provider_unsupported", "暂不支持的目录协议：" + provider.kind);
        }
        catalogs.put(provider.id, created);
        return created;
    }

    public synchronized DramaProviderConfig find(String id) {
        if (id == null) return null;
        for (DramaProviderConfig provider : providers()) {
            if (id.equals(provider.id)) return provider;
        }
        return null;
    }

    /** Validates and canonicalizes a provider base URL. Pure and unit-testable. */
    static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("请输入短剧目录地址");
        }
        String value = baseUrl.trim();
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            throw new IllegalArgumentException("目录地址必须使用 http 或 https");
        }
        HttpUrl parsed = HttpUrl.parse(value);
        if (parsed == null) throw new IllegalArgumentException("目录地址无效");
        if (parsed.username().length() > 0 || parsed.password().length() > 0) {
            throw new IllegalArgumentException("目录地址不能包含账号密码");
        }
        String result = parsed.toString();
        if (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private void save(List<DramaProviderConfig> providers) {
        preferences.edit().putString(KEY_CATALOGS, gson.toJson(providers)).apply();
    }
}
