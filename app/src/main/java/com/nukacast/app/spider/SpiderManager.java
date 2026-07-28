package com.nukacast.app.spider;

import android.content.Context;

import com.github.catvod.crawler.Spider;
import com.nukacast.app.net.HttpStack;
import com.nukacast.app.net.ResponseBodies;
import com.nukacast.app.tvbox.model.TvBoxConfig;
import com.nukacast.app.util.Digests;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import dalvik.system.DexClassLoader;
import okhttp3.Request;
import okhttp3.Response;

public final class SpiderManager {
    private static final long RECHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private static final int MAX_JAR_BYTES = 20 * 1024 * 1024;
    private static final int MAX_SESSIONS = 16;
    private static final long CALL_TIMEOUT_SECONDS = 10L;
    private final Context context;
    private final JarTrustStore trustStore;
    private final Map<String, SpiderSession> sessions = new HashMap<String, SpiderSession>();
    private final ExecutorService calls = Executors.newFixedThreadPool(4);

    public SpiderManager(Context context) {
        this.context = context.getApplicationContext();
        this.trustStore = new JarTrustStore(this.context);
    }

    public String search(final TvBoxConfig.Site site, final String keyword, final int page)
            throws Exception {
        return invoke(new Callable<String>() {
            @Override public String call() throws Exception {
                SpiderSession session = session(site);
                synchronized (session) {
                    return session.search(keyword, false, String.valueOf(Math.max(1, page)));
                }
            }
        });
    }

    public String home(final TvBoxConfig.Site site, final boolean filter) throws Exception {
        return invoke(new Callable<String>() {
            @Override public String call() throws Exception {
                SpiderSession session = session(site);
                synchronized (session) { return session.home(filter); }
            }
        });
    }

    public String detail(final TvBoxConfig.Site site, final List<String> ids) throws Exception {
        return invoke(new Callable<String>() {
            @Override public String call() throws Exception {
                SpiderSession session = session(site);
                synchronized (session) { return session.detail(ids); }
            }
        });
    }

    public String play(final TvBoxConfig.Site site, final String flag, final String id)
            throws Exception {
        return invoke(new Callable<String>() {
            @Override public String call() throws Exception {
                SpiderSession session = session(site);
                synchronized (session) {
                    return session.play(flag, id, Collections.<String>emptyList());
                }
            }
        });
    }

    public synchronized void destroy() {
        for (SpiderSession session : sessions.values()) {
            try {
                session.destroy();
            } catch (RuntimeException ignored) {}
        }
        sessions.clear();
        calls.shutdownNow();
    }

    public synchronized void forgetForConfig(TvBoxConfig config) {
        Set<String> specs = new HashSet<String>();
        if (config.spider != null && !config.spider.isEmpty()) specs.add(config.spider);
        for (TvBoxConfig.Site site : config.sites) {
            if (site.jar != null && !site.jar.isEmpty()) specs.add(site.jar);
            if (site.globalSpider != null && !site.globalSpider.isEmpty()) {
                specs.add(site.globalSpider);
            }
        }
        for (String spec : specs) forgetJar(spec);
    }

    private void forgetJar(String spec) {
        JarSpec parsed;
        try {
            parsed = JarSpec.parse(spec);
        } catch (RuntimeException ignored) {
            return;
        }
        trustStore.forget(parsed.url);
        String prefix = Digests.sha256(parsed.url.getBytes()) + "-";
        File directory = new File(context.getFilesDir(), "spider-jars");
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) if (file.getName().startsWith(prefix)) file.delete();
        }
        Iterator<Map.Entry<String, SpiderSession>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, SpiderSession> entry = iterator.next();
            if (!entry.getKey().startsWith(spec + "|")) continue;
            try { entry.getValue().destroy(); } catch (RuntimeException ignored) {}
            iterator.remove();
        }
    }

    private synchronized SpiderSession session(TvBoxConfig.Site site) throws Exception {
        if (QuickJsSpiderSession.supports(site)) {
            String sessionKey = "js|" + safe(site.api) + "|" + site.extension();
            SpiderSession existing = sessions.get(sessionKey);
            if (existing != null) return existing;
            if (sessions.size() >= MAX_SESSIONS) throw new IllegalStateException("Spider 会话数已达上限");
            SpiderSession created = new QuickJsSpiderSession(site);
            sessions.put(sessionKey, created);
            return created;
        }
        String jarSpec = firstNonEmpty(site.jar, site.globalSpider);
        if (jarSpec.isEmpty()) {
            throw new IllegalStateException("站点未配置 Spider JAR");
        }
        String className = spiderClassName(site.api);
        String sessionKey = jarSpec + "|" + className + "|" + site.extension();
        SpiderSession existing = sessions.get(sessionKey);
        if (existing != null) {
            return existing;
        }
        if (sessions.size() >= MAX_SESSIONS) throw new IllegalStateException("Spider 会话数已达上限");

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
        spider.init(context, site.extension());
        SpiderSession created = new JavaSpiderSession(spider);
        sessions.put(sessionKey, created);
        return created;
    }

    private File obtainJar(String spec) throws IOException {
        JarSpec jarSpec = JarSpec.parse(spec);
        String url = jarSpec.url;
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new SecurityException("Spider JAR 必须使用 HTTP(S): " + url);
        }
        File directory = new File(context.getFilesDir(), "spider-jars");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("无法创建 Spider 缓存目录");
        }
        String fingerprint = jarSpec.expectedHash.isEmpty()
                ? Digests.sha256(url.getBytes()).substring(0, 16)
                : jarSpec.expectedHash.substring(0, 16);
        File target = new File(directory,
                Digests.sha256(url.getBytes()) + "-" + fingerprint + ".jar");
        if (target.isFile() && System.currentTimeMillis() - target.lastModified() < RECHECK_INTERVAL_MS) {
            byte[] cached = readLimited(target, MAX_JAR_BYTES);
            if (jarSpec.matches(cached) && trustedIfNeeded(jarSpec, cached)) return target;
            if (!target.delete()) throw new IOException("无法删除损坏的 Spider JAR");
        }

        Request request = new Request.Builder().url(url)
                .header("User-Agent", "NukaCast/0.1 SpiderLoader")
                .build();
        byte[] content;
        try (Response response = HttpStack.client().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Spider JAR HTTP " + response.code());
            }
            content = ResponseBodies.bytes(response.body(), MAX_JAR_BYTES);
        }
        if (!jarSpec.matches(content)) {
            throw new SecurityException("Spider JAR " + jarSpec.algorithm.toUpperCase(
                    java.util.Locale.US) + " 不匹配");
        }
        if (!trustedIfNeeded(jarSpec, content)) {
            throw new SecurityException("Spider JAR 内容已变化，需要删除后重新添加源");
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

    private boolean trustedIfNeeded(JarSpec spec, byte[] content) {
        if (!spec.expectedHash.isEmpty()) return true;
        return trustStore.verify(spec.url, Digests.sha256(content)) != JarTrustStore.Verdict.CHANGED;
    }

    private static byte[] readLimited(File file, int maximumBytes) throws IOException {
        if (file.length() > maximumBytes) throw new IOException("Spider JAR 缓存过大");
        java.io.FileInputStream input = new java.io.FileInputStream(file);
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream((int) file.length());
        byte[] buffer = new byte[8192];
        int total = 0;
        try {
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maximumBytes) throw new IOException("Spider JAR 缓存过大");
                output.write(buffer, 0, count);
            }
        } finally {
            input.close();
        }
        return output.toByteArray();
    }

    private static String firstNonEmpty(String first, String second) {
        return first != null && !first.trim().isEmpty() ? first.trim()
                : second == null ? "" : second.trim();
    }

    private static String safe(String value) { return value == null ? "" : value; }

    static final class JarSpec {
        final String url;
        final String algorithm;
        final String expectedHash;

        private JarSpec(String url, String algorithm, String expectedHash) {
            this.url = url;
            this.algorithm = algorithm;
            this.expectedHash = expectedHash;
        }

        static JarSpec parse(String value) {
            if (value == null || value.trim().isEmpty()) {
                throw new SecurityException("Spider JAR 地址为空");
            }
            String[] parts = value.trim().split(";", -1);
            String url = parts[0].trim();
            if (parts.length == 1) return new JarSpec(url, "", "");

            String algorithm;
            String hash;
            if (parts.length == 3) {
                algorithm = parts[1].trim().toLowerCase(java.util.Locale.US);
                hash = parts[2].trim();
            } else if (parts.length == 2) {
                String declaration = parts[1].trim();
                int separator = Math.max(declaration.indexOf('='), declaration.indexOf(':'));
                if (separator <= 0) throw new SecurityException("Spider JAR 摘要格式无效");
                algorithm = declaration.substring(0, separator).trim()
                        .toLowerCase(java.util.Locale.US);
                hash = declaration.substring(separator + 1).trim();
            } else {
                throw new SecurityException("Spider JAR 摘要格式无效");
            }
            int length = "md5".equals(algorithm) ? 32 : "sha256".equals(algorithm) ? 64 : -1;
            if (length < 0 || !hash.matches("(?i)[0-9a-f]{" + length + "}")) {
                throw new SecurityException("Spider JAR " + algorithm + " 摘要格式无效");
            }
            return new JarSpec(url, algorithm, hash.toLowerCase(java.util.Locale.US));
        }

        boolean matches(byte[] content) {
            if (expectedHash.isEmpty()) return true;
            String actual = "md5".equals(algorithm)
                    ? Digests.md5(content) : Digests.sha256(content);
            return expectedHash.equalsIgnoreCase(actual);
        }
    }

    private <T> T invoke(Callable<T> operation) throws Exception {
        Future<T> future = calls.submit(operation);
        try {
            return future.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new IOException("Spider 执行超时", timeout);
        }
    }
}
