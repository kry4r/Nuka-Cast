package com.nukacast.app.tvbox;

import android.content.Context;

import com.google.gson.Gson;
import com.nukacast.app.net.HttpStack;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Request;
import okhttp3.Response;

public final class TvBoxRepository {
    public interface RefreshListener {
        void onRefreshComplete(int configs, int enabledSites);
    }

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private final Context context;
    private final SourceStore sourceStore;
    private final Gson gson = new Gson();
    private final ConfigDecoder decoder = new ConfigDecoder(gson);
    private final Map<String, TvBoxConfig> configs = new ConcurrentHashMap<String, TvBoxConfig>();
    private final ExecutorService refreshExecutor = Executors.newSingleThreadExecutor();

    public TvBoxRepository(Context context, SourceStore sourceStore) {
        this.context = context.getApplicationContext();
        this.sourceStore = sourceStore;
        restoreCache();
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

    public List<TvBoxConfig.LiveSource> getLiveSources() {
        List<TvBoxConfig.LiveSource> result = new ArrayList<TvBoxConfig.LiveSource>();
        for (ConfigSource source : sourceStore.getSources()) {
            if (!source.enabled) continue;
            TvBoxConfig config = configs.get(source.id);
            if (config != null) result.addAll(config.lives);
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
                    if (!source.enabled) {
                        continue;
                    }
                    try {
                        refresh(source);
                    } catch (Exception error) {
                        source.error = message(error);
                        source.updatedAt = System.currentTimeMillis();
                        sourceStore.update(source);
                    }
                }
                if (listener != null) {
                    listener.onRefreshComplete(configs.size(), getEnabledSites().size());
                }
            }
        });
    }

    public TvBoxConfig refresh(ConfigSource source) throws IOException {
        Request request = new Request.Builder()
                .url(source.url)
                .header("User-Agent", "NukaCast/0.1 TVBox/API17")
                .header("Accept", "application/json,text/plain,*/*")
                .build();
        byte[] bytes;
        try (Response response = HttpStack.client().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            bytes = response.body().bytes();
        }
        String content = new String(bytes, UTF_8);
        TvBoxConfig config;
        try {
            config = decoder.decode(content);
        } catch (RuntimeException error) {
            throw new IOException(error.getMessage(), error);
        }
        enrich(source, config);
        configs.put(source.id, config);
        source.contentHash = Digests.sha256(bytes);
        source.updatedAt = System.currentTimeMillis();
        source.error = "";
        sourceStore.update(source);
        saveCache(source.id, content);
        return config;
    }

    private void enrich(ConfigSource source, TvBoxConfig config) {
        config.sourceId = source.id;
        config.baseUrl = source.url;
        config.spider = Urls.resolve(source.url, config.spider);
        for (TvBoxConfig.Site site : config.sites) {
            site.sourceId = source.id;
            site.sourceName = source.name;
            site.configBaseUrl = source.url;
            site.globalSpider = config.spider;
            site.jar = Urls.resolve(source.url, site.jar);
            if (site.ext != null && (site.ext.startsWith("./") || site.ext.startsWith("../"))) {
                site.ext = Urls.resolve(source.url, site.ext);
            }
        }
        for (TvBoxConfig.LiveSource live : config.lives) {
            live.sourceId = source.id;
            live.url = Urls.resolve(source.url, live.url);
            live.epg = Urls.resolve(source.url, live.epg);
            live.logo = Urls.resolve(source.url, live.logo);
        }
    }

    private void restoreCache() {
        for (ConfigSource source : sourceStore.getSources()) {
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
        FileInputStream input = new FileInputStream(file);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try {
            int count;
            while ((count = input.read(buffer)) >= 0) {
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
