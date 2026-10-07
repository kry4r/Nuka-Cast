package com.nukacast.app.tvbox;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.JsonPrimitive;
import com.nukacast.app.BuildConfig;
import com.nukacast.app.diagnostics.AppLog;
import com.nukacast.app.diagnostics.StageTrace;
import com.nukacast.app.net.HttpStack;
import com.nukacast.app.net.ResponseBodies;
import com.nukacast.app.tvbox.model.ConfigSource;
import com.nukacast.app.tvbox.model.TvBoxConfig;
import com.nukacast.app.util.Digests;
import com.nukacast.app.util.Urls;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Request;
import okhttp3.Response;

public final class TvBoxRepository {
    private static final int MAX_CONFIG_BYTES = 2 * 1024 * 1024;
    /** Above this, a config is treated as "too many sites for this class of device" and said so. */
    static final int LARGE_CONFIG_SITES = 60;
    public interface RefreshListener {
        void onSourceRefreshed(int configs, int enabledSites);
        void onRefreshComplete(int configs, int enabledSites);
    }

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private final Context context;
    private final SourceStore sourceStore;
    private final LiveSourceStore liveSourceStore;
    private final Gson gson = new Gson();
    private final ConfigDecoder decoder = new ConfigDecoder(gson);
    private final ConfigPayloadResolver payloadResolver = new ConfigPayloadResolver(decoder);
    private final Map<String, TvBoxConfig> configs = new ConcurrentHashMap<String, TvBoxConfig>();
    private final ExecutorService refreshExecutor = Executors.newSingleThreadExecutor();

    public TvBoxRepository(Context context, SourceStore sourceStore) {
        this.context = context.getApplicationContext();
        this.sourceStore = sourceStore;
        this.liveSourceStore = new LiveSourceStore(this.context);
        restoreCache();
    }

    public LiveSourceStore getLiveSourceStore() {
        return liveSourceStore;
    }

    public Context getContext() {
        return context;
    }

    public List<TvBoxConfig.Site> getEnabledSites() {
        List<TvBoxConfig.Site> result = new ArrayList<TvBoxConfig.Site>();
        for (ConfigSource source : sourceStore.getSources()) {
            if (!source.enabled) {
                continue;
            }
            TvBoxConfig config = configs.get(source.id);
            if (config != null) {
                result.addAll(config.sites);
            }
        }
        Collections.sort(result, new Comparator<TvBoxConfig.Site>() {
            @Override public int compare(TvBoxConfig.Site left, TvBoxConfig.Site right) {
                return safe(left.name).compareToIgnoreCase(safe(right.name));
            }
        });
        return result;
    }

    public List<ConfigSource> getRankedLeafSources() {
        return WarehouseRanking.rankLeaves(sourceStore.getSources());
    }

    public void recordSearchOutcome(String sourceId, long elapsedMs,
                                    int successfulSites, int attemptedSites) {
        for (ConfigSource source : sourceStore.getSources()) {
            if (!source.id.equals(sourceId)) continue;
            if (successfulSites > 0) {
                long measured = Math.max(1L, elapsedMs);
                source.latencyMs = source.latencyMs <= 0
                        ? measured : (source.latencyMs * 3L + measured) / 4L;
                source.searchError = "";
            } else {
                source.searchError = attemptedSites == 0
                        ? "仓库没有可搜索站点" : "最近搜索全部失败";
            }
            sourceStore.update(source);
            return;
        }
    }

    public List<TvBoxConfig.LiveSource> getLiveSources() {
        List<TvBoxConfig.LiveSource> result = new ArrayList<TvBoxConfig.LiveSource>();
        for (ConfigSource source : sourceStore.getSources()) {
            if (!source.enabled) continue;
            TvBoxConfig config = configs.get(source.id);
            if (config != null) result.addAll(config.lives);
        }
        // User playlists come last and stay independent of every TVBox config.
        for (com.nukacast.app.tvbox.model.LivePlaylist playlist : liveSourceStore.enabled()) {
            TvBoxConfig.LiveSource live = new TvBoxConfig.LiveSource();
            live.name = playlist.name;
            live.url = playlist.url;
            live.epg = playlist.epg;
            live.logo = playlist.logo;
            live.sourceId = "user:" + playlist.id;
            result.add(live);
        }
        return result;
    }

    public TvBoxConfig getConfig(String sourceId) {
        return configs.get(sourceId);
    }

    public TvBoxConfig.Site findSite(String sourceId, String siteKey) {
        for (TvBoxConfig.Site site : getEnabledSites()) {
            if (siteKey != null && !siteKey.equals(site.key)) continue;
            if (sourceId != null && !sourceId.isEmpty() && !sourceId.equals(site.sourceId)) continue;
            return site;
        }
        return null;
    }

    public void refreshAllAsync(final RefreshListener listener) {
        refreshExecutor.execute(new Runnable() {
            @Override public void run() {
                for (ConfigSource source : sourceStore.getSources()) {
                    if (!source.enabled || source.isChild()) continue;
                    refreshSafely(source);
                    notifySourceRefreshed(listener, sourceStore.getSources().size(),
                            getEnabledSites().size());
                }
                // Parent refreshes may add or remove children, so take a fresh snapshot.
                for (ConfigSource source : sourceStore.getSources()) {
                    if (!source.enabled || !source.isChild()) continue;
                    refreshSafely(source);
                    notifySourceRefreshed(listener, sourceStore.getSources().size(),
                            getEnabledSites().size());
                }
                if (listener != null) {
                    listener.onRefreshComplete(sourceStore.getSources().size(), getEnabledSites().size());
                }
            }
        });
    }

    public void refreshChildrenAsync(final String parentId, final RefreshListener listener) {
        refreshExecutor.execute(new Runnable() {
            @Override public void run() {
                for (ConfigSource source : sourceStore.getSources()) {
                    if (!source.enabled || !parentId.equals(source.parentId)) continue;
                    refreshSafely(source);
                    notifySourceRefreshed(listener, sourceStore.getSources().size(),
                            getEnabledSites().size());
                }
                if (listener != null) {
                    listener.onRefreshComplete(sourceStore.getSources().size(), getEnabledSites().size());
                }
            }
        });
    }

    public void refreshSourceTreeAsync(final ConfigSource source, final RefreshListener listener) {
        refreshExecutor.execute(new Runnable() {
            @Override public void run() {
                refreshSafely(source);
                notifySourceRefreshed(listener, sourceStore.getSources().size(),
                        getEnabledSites().size());
                if (source.isWarehouse()) {
                    for (ConfigSource child : sourceStore.getSources()) {
                        if (!child.enabled || !source.id.equals(child.parentId)) continue;
                        refreshSafely(child);
                        notifySourceRefreshed(listener, sourceStore.getSources().size(),
                                getEnabledSites().size());
                    }
                }
                if (listener != null) {
                    listener.onRefreshComplete(sourceStore.getSources().size(),
                            getEnabledSites().size());
                }
            }
        });
    }

    public TvBoxConfig refresh(ConfigSource source) throws IOException {
        long startedAt = System.currentTimeMillis();
        StageTrace.Trace trace = StageTrace.start("source", safe(source.name));
        try {
            trace.stage("fetch_config");
            ConfigPayloadResolver.Resolved resolved = payloadResolver.resolve(source.url,
                    new ConfigPayloadResolver.Fetcher() {
                @Override public ConfigPayloadResolver.Payload fetch(String url) throws IOException {
                    return fetchConfig(url);
                }
            });
            trace.stage("decode");
            ConfigDecoder.Document document = resolved.document;
            trace.stage("persist");
            source.resolvedUrl = resolved.url;
            source.contentHash = Digests.sha256(resolved.bytes);
            source.updatedAt = System.currentTimeMillis();
            source.latencyMs = Math.max(1L, source.updatedAt - startedAt);
            source.error = "";
            if (document.isWarehouse()) {
                source.kind = ConfigSource.KIND_WAREHOUSE;
                source.siteCount = 0;
                source.searchableSiteCount = 0;
                source.liveCount = 0;
                configs.remove(source.id);
                sourceStore.update(source);
                sourceStore.synchronizeChildren(source, document.warehouses);
                deleteCache(source.id);
                pruneConfigsAndCaches();
                AppLog.i("片源", "仓库刷新成功 [" + safe(source.name) + "]");
                trace.success();
                return null;
            }

            TvBoxConfig config = document.config;
            boolean wasWarehouse = source.isWarehouse();
            source.kind = ConfigSource.KIND_SINGLE;
            enrich(source, config);
            configs.put(source.id, config);
            source.siteCount = config.sites.size();
            source.searchableSiteCount = searchableSiteCount(config);
            source.liveCount = config.lives.size();
            sourceStore.update(source);
            if (wasWarehouse) {
                sourceStore.synchronizeChildren(source,
                        Collections.<ConfigDecoder.WarehouseEntry>emptyList());
                pruneConfigsAndCaches();
            }
            saveCache(source.id, resolved.content);
            AppLog.i("片源", "配置刷新成功 [" + safe(source.name) + "]："
                    + config.sites.size() + " 个站点");
            if (config.sites.size() > LARGE_CONFIG_SITES) {
                // Public repos hand out 100+ sites, most of them plugin sites that cost native memory
                // and rarely answer. Say so once instead of letting the user wonder why it is slow.
                AppLog.w("片源", "该配置有 " + config.sites.size() + " 个站点，插件站点会被自动限制"
                        + "（搜索最多 " + com.nukacast.app.tvbox.SearchEngine.MAX_SEARCH_SITES
                        + " 个，首页最多 2 个插件站点）。建议改用“NukaCast 精简片源”");
            }
            trace.success();
            return config;
        } catch (Exception error) {
            trace.failure(error);
            source.error = message(error);
            source.updatedAt = System.currentTimeMillis();
            source.latencyMs = Math.max(1L, source.updatedAt - startedAt);
            sourceStore.update(source);
            AppLog.w("片源", "配置刷新失败 [" + safe(source.name) + "]：" + source.error);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException(source.error, error);
        }
    }

    public List<TvBoxConfig> configsForTree(String id) {
        Set<String> ids = SourceStore.treeIds(sourceStore.getSources(), id);
        List<TvBoxConfig> result = new ArrayList<TvBoxConfig>();
        for (String sourceId : ids) {
            TvBoxConfig config = configs.get(sourceId);
            if (config != null) result.add(config);
        }
        return result;
    }

    public boolean remove(String id) {
        boolean removed = sourceStore.remove(id);
        if (removed) pruneConfigsAndCaches();
        return removed;
    }

    static void notifySourceRefreshed(RefreshListener listener, int configs, int sites) {
        if (listener != null) listener.onSourceRefreshed(configs, sites);
    }

    private void refreshSafely(ConfigSource source) {
        try {
            refresh(source);
        } catch (Exception ignored) {
            // refresh() persists the source-specific failure for diagnostics.
        } catch (Throwable failure) {
            // A LinkageError/VerifyError from a plugin or the TLS stack used to escape the single
            // refresh thread and kill the process on Android; contain it, keep the record and leave
            // the rest of the UI alive.
            StageTrace.componentFailure("source", safe(source.name), "refresh", failure);
            AppLog.e("片源", "配置刷新抛出 " + failure.getClass().getSimpleName()
                    + " [" + safe(source.name) + "]", failure);
        }
    }

    /** Turns a bare status code into something a user can act on. */
    static String describeHttpFailure(int code) {
        if (code == 401 || code == 403) {
            return "HTTP " + code + "：该配置需要授权，请更换可公开访问的地址";
        }
        if (code == 404) return "HTTP 404：地址不存在，配置可能已下线";
        if (code == 429) return "HTTP 429：请求过于频繁，请稍后再试";
        if (code >= 500) return "HTTP " + code + "：对方服务异常";
        return "HTTP " + code;
    }

    private ConfigPayloadResolver.Payload fetchConfig(String url) throws IOException {
        if (com.nukacast.app.net.BundledAssets.isBundled(url)) {
            // Ships inside the APK: no network, no repository, no plugin JARs.
            byte[] bytes = com.nukacast.app.net.BundledAssets.read(context, url);
            return new ConfigPayloadResolver.Payload(url, bytes, "application/json");
        }
        String normalized;
        try {
            normalized = com.nukacast.app.net.UrlNormalizer.normalize(url);
        } catch (IllegalArgumentException error) {
            throw new IOException("配置地址无效：" + error.getMessage(), error);
        }
        Request request = new Request.Builder()
                .url(normalized)
                .header("User-Agent", "NukaCast/" + BuildConfig.VERSION_NAME + " TVBox/API17")
                .header("Accept", "application/json,text/plain,text/html,*/*")
                .build();
        try (Response response = HttpStack.client().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException(describeHttpFailure(response.code()));
            }
            String contentType = response.header("Content-Type", "");
            byte[] bytes = ResponseBodies.bytes(response.body(), MAX_CONFIG_BYTES);
            return new ConfigPayloadResolver.Payload(
                    response.request().url().toString(), bytes, contentType);
        }
    }

    private void enrich(ConfigSource source, TvBoxConfig config) {
        String baseUrl = source.configUrl();
        config.sourceId = source.id;
        config.baseUrl = baseUrl;
        config.spider = Urls.resolve(baseUrl, config.spider);
        for (TvBoxConfig.Site site : config.sites) {
            site.sourceId = source.id;
            site.sourceName = source.name;
            site.configBaseUrl = baseUrl;
            site.globalSpider = config.spider;
            site.jar = Urls.resolve(baseUrl, site.jar);
            String extension = site.extension();
            if (extension.startsWith("./") || extension.startsWith("../")) {
                site.ext = new JsonPrimitive(Urls.resolve(baseUrl, extension));
            }
        }
        for (TvBoxConfig.LiveSource live : config.lives) {
            live.sourceId = source.id;
            live.url = Urls.resolve(baseUrl, live.url);
            live.epg = Urls.resolve(baseUrl, live.epg);
            live.logo = Urls.resolve(baseUrl, live.logo);
        }
    }

    private static int searchableSiteCount(TvBoxConfig config) {
        int count = 0;
        for (TvBoxConfig.Site site : config.sites) {
            if (site.canSearch() && (site.type == 0 || site.type == 1 || site.type == 3)) count++;
        }
        return count;
    }

    private void pruneConfigsAndCaches() {
        Set<String> retained = new HashSet<String>();
        for (ConfigSource source : sourceStore.getSources()) retained.add(source.id);
        for (String id : new ArrayList<String>(configs.keySet())) {
            if (!retained.contains(id)) configs.remove(id);
        }
        File directory = new File(context.getFilesDir(), "tvbox-configs");
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            int suffix = name.lastIndexOf('.');
            String id = suffix > 0 ? name.substring(0, suffix) : name;
            if (!retained.contains(id)) file.delete();
        }
    }

    private void deleteCache(String sourceId) {
        File file = cacheFile(sourceId);
        if (file.isFile()) file.delete();
    }

    private void restoreCache() {
        for (ConfigSource source : sourceStore.getSources()) {
            if (source.isWarehouse()) continue;
            File cache = cacheFile(source.id);
            if (!cache.isFile()) {
                continue;
            }
            try {
                TvBoxConfig config = decoder.decode(readFile(cache));
                enrich(source, config);
                configs.put(source.id, config);
            } catch (Exception ignored) {
                // A corrupt cache is replaced on the next successful refresh.
            }
        }
    }

    private void saveCache(String sourceId, String content) throws IOException {
        File directory = new File(context.getFilesDir(), "tvbox-configs");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("无法创建配置缓存目录");
        }
        File output = cacheFile(sourceId);
        File temporary = new File(directory, sourceId + ".tmp");
        FileOutputStream stream = new FileOutputStream(temporary);
        try {
            stream.write(content.getBytes(UTF_8));
            stream.getFD().sync();
        } finally {
            stream.close();
        }
        if (output.exists() && !output.delete()) {
            throw new IOException("无法替换旧配置缓存");
        }
        if (!temporary.renameTo(output)) {
            throw new IOException("无法提交配置缓存");
        }
    }

    private File cacheFile(String sourceId) {
        return new File(new File(context.getFilesDir(), "tvbox-configs"), sourceId + ".json");
    }

    private static String readFile(File file) throws IOException {
        if (file.length() > MAX_CONFIG_BYTES) throw new IOException("配置缓存过大");
        FileInputStream input = new FileInputStream(file);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (output.size() + count > MAX_CONFIG_BYTES) throw new IOException("配置缓存过大");
                output.write(buffer, 0, count);
            }
        } finally {
            input.close();
        }
        return new String(output.toByteArray(), UTF_8);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
