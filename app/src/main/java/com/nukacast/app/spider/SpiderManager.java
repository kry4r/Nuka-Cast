package com.nukacast.app.spider;

import android.content.Context;

import com.github.catvod.crawler.Spider;
import com.nukacast.app.net.HttpStack;
import com.nukacast.app.tvbox.model.TvBoxConfig;
import com.nukacast.app.util.Digests;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import dalvik.system.DexClassLoader;
import okhttp3.Request;
import okhttp3.Response;

public final class SpiderManager {
    private static final long RECHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private final Context context;
    private final JarTrustStore trustStore;
    private final Map<String, SpiderSession> sessions = new HashMap<String, SpiderSession>();

    public SpiderManager(Context context) {
        this.context = context.getApplicationContext();
        this.trustStore = new JarTrustStore(this.context);
    }

    public String search(TvBoxConfig.Site site, String keyword, int page) throws Exception {
        SpiderSession session = session(site);
        synchronized (session) {
            return session.search(keyword, false, String.valueOf(Math.max(1, page)));
        }
    }

    public String home(TvBoxConfig.Site site, boolean filter) throws Exception {
        SpiderSession session = session(site);
        synchronized (session) { return session.home(filter); }
    }

    public String detail(TvBoxConfig.Site site, List<String> ids) throws Exception {
        SpiderSession session = session(site);
        synchronized (session) { return session.detail(ids); }
    }

    public String play(TvBoxConfig.Site site, String flag, String id) throws Exception {
        SpiderSession session = session(site);
        synchronized (session) { return session.play(flag, id, Collections.<String>emptyList()); }
    }

    public synchronized void destroy() {
        for (SpiderSession session : sessions.values()) {
            try {
                session.destroy();
            } catch (RuntimeException ignored) {}
        }
        sessions.clear();
    }

    private synchronized SpiderSession session(TvBoxConfig.Site site) throws Exception {
        if (QuickJsSpiderSession.supports(site)) {
            String sessionKey = "js|" + safe(site.api) + "|" + safe(site.ext);
            SpiderSession existing = sessions.get(sessionKey);
            if (existing != null) return existing;
            SpiderSession created = new QuickJsSpiderSession(site);
            sessions.put(sessionKey, created);
            return created;
        }
        String jarSpec = firstNonEmpty(site.jar, site.globalSpider);
        if (jarSpec.isEmpty()) {
            throw new IllegalStateException("站点未配置 Spider JAR");
        }
        String className = spiderClassName(site.api);
        String sessionKey = jarSpec + "|" + className + "|" + safe(site.ext);
        SpiderSession existing = sessions.get(sessionKey);
        if (existing != null) {
            return existing;
        }

        File jar = obtainJar(jarSpec);
        File optimized = new File(context.getFilesDir(), "spider-dex");
        if (!optimized.exists() && !optimized.mkdirs()) {
            throw new IOException("无法创建 Spider DEX 目录");
        }
        DexClassLoader loader = new DexClassLoader(
                jar.getAbsolutePath(), optimized.getAbsolutePath(), null, context.getClassLoader());
        Class<?> type = loader.loadClass(className);
        Object instance = type.newInstance();
        if (!(instance instanceof Spider)) {
            throw new IllegalStateException(className + " 未继承 CatVod Spider");
        }
        Spider spider = (Spider) instance;
        spider.init(context, safe(site.ext));
        SpiderSession created = new JavaSpiderSession(spider);
        sessions.put(sessionKey, created);
        return created;
    }

    private File obtainJar(String spec) throws IOException {
        String url = stripDigest(spec);
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IOException("当前只允许 HTTP(S) Spider JAR");
        }
        File directory = new File(context.getFilesDir(), "spider-jars");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("无法创建 Spider 缓存目录");
        }
        File target = new File(directory, Digests.sha256(url.getBytes()) + ".jar");
        if (target.isFile() && System.currentTimeMillis() - target.lastModified() < RECHECK_INTERVAL_MS) {
            return target;
        }

        Request request = new Request.Builder().url(url)
                .header("User-Agent", "NukaCast/0.1 SpiderLoader")
                .build();
        byte[] content;
        try (Response response = HttpStack.client().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Spider JAR HTTP " + response.code());
            }
            content = response.body().bytes();
        }
        String hash = Digests.sha256(content);
        JarTrustStore.Verdict verdict = trustStore.verify(url, hash);
        if (verdict == JarTrustStore.Verdict.CHANGED) {
            throw new SecurityException("Spider JAR 内容已变化，需要在控制台重新授权: " + hash);
        }
        File temporary = new File(directory, target.getName() + ".tmp");
        FileOutputStream output = new FileOutputStream(temporary);
        try {
            output.write(content);
            output.getFD().sync();
        } finally {
            output.close();
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("无法替换 Spider JAR");
        }
        if (!temporary.renameTo(target)) {
            throw new IOException("无法提交 Spider JAR");
        }
        return target;
    }

    private static String spiderClassName(String api) {
        if (api == null || api.trim().isEmpty()) {
            throw new IllegalArgumentException("Spider API 为空");
        }
        String value = api.trim();
        if (value.startsWith("csp_")) {
            value = value.substring(4);
        }
        if (value.indexOf('.') >= 0) {
            return value;
        }
        return "com.github.catvod.spider." + value;
    }

    private static String stripDigest(String spec) {
        int separator = spec.indexOf(';');
        return (separator < 0 ? spec : spec.substring(0, separator)).trim();
    }

    private static String firstNonEmpty(String first, String second) {
        return first != null && !first.trim().isEmpty() ? first.trim()
                : second == null ? "" : second.trim();
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
