package com.nukacast.app.spider;

import android.content.Context;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderApi;
import com.nukacast.app.net.HttpStack;
import com.nukacast.app.net.ResponseBodies;
import com.nukacast.app.diagnostics.AppLog;
import com.nukacast.app.tvbox.model.TvBoxConfig;
import com.nukacast.app.util.Digests;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
    private static final int MAX_SESSIONS = 64;
    private static final long CALL_TIMEOUT_SECONDS = 10L;
    private final Context context;
    private final JarTrustStore trustStore;
    private final Map<String, SpiderSession> sessions = new HashMap<String, SpiderSession>();
    private final Map<String, LoadedJar> loadedJars = new HashMap<String, LoadedJar>();
    private final Map<String, Spider> siteSpiders = new HashMap<String, Spider>();
    private final Map<String, LoadedJar> siteJars = new HashMap<String, LoadedJar>();
    private final ExecutorService calls = Executors.newFixedThreadPool(4);
    private LoadedJar recentJar;
    private Spider recentSpider;

    public SpiderManager(Context context) {
        this.context = context.getApplicationContext();
        this.trustStore = new JarTrustStore(this.context);
        com.github.catvod.SpiderContext.set(this.context);
        com.github.catvod.Proxy.set(com.nukacast.app.core.NukaRuntime.CONTROL_PORT);
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
        return play(site, flag, id, Collections.<String>emptyList());
    }

    public String play(final TvBoxConfig.Site site, final String flag, final String id,
                       final List<String> vipFlags) throws Exception {
        return invoke(new Callable<String>() {
            @Override public String call() throws Exception {
                SpiderSession session = session(site);
                synchronized (session) {
                    String result = session.play(flag, id, vipFlags == null
                            ? Collections.<String>emptyList() : vipFlags);
                    pinProxy(site);
                    return result;
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
        siteSpiders.clear();
        siteJars.clear();
        loadedJars.clear();
        recentJar = null;
        recentSpider = null;
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
            if (entry.getValue() instanceof JavaSpiderSession) {
                removeSpider((JavaSpiderSession) entry.getValue());
            }
            try { entry.getValue().destroy(); } catch (RuntimeException ignored) {}
            iterator.remove();
        }
        LoadedJar removed = loadedJars.remove(spec);
        Iterator<Map.Entry<String, LoadedJar>> jars = siteJars.entrySet().iterator();
        while (jars.hasNext()) if (jars.next().getValue() == removed) jars.remove();
        if (recentJar == removed) recentJar = null;
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
        LoadedJar loaded = loadedJar(jarSpec);
        String sessionKey = jarSpec + "|" + safe(site.key) + "|" + className
                + "|" + site.extension();
        SpiderSession existing = sessions.get(sessionKey);
        if (existing != null) return existing;
        if (sessions.size() >= MAX_SESSIONS) throw new IllegalStateException("Spider 会话数已达上限");

        Class<?> type = loaded.loader.loadClass(className);
        Object instance = type.newInstance();
        if (!(instance instanceof Spider)) {
            throw new IllegalStateException(className + " 未继承 CatVod Spider");
        }
        Spider spider = (Spider) instance;
        spider.siteKey = safe(site.key);
        spider.initApi(new SpiderApi(context));
        spider.init(context, site.extension());
        SpiderSession created = new JavaSpiderSession(spider);
        sessions.put(sessionKey, created);
        siteSpiders.put(safe(site.key), spider);
        siteJars.put(siteIdentity(site), loaded);
        return created;
    }

    private synchronized void pinProxy(TvBoxConfig.Site site) {
        recentJar = siteJars.get(siteIdentity(site));
        recentSpider = siteSpiders.get(safe(site.key));
    }

    public synchronized Object[] proxy(Map<String, String> params) throws Exception {
        if (params == null) return null;
        if (params.containsKey("do")) {
            Spider spider = siteSpiders.get(safe(params.get("siteKey")));
            if (spider == null) spider = recentSpider;
            return spider == null ? null : spider.proxyLocal(params);
        }
        if (params.containsKey("go") && recentJar != null && recentJar.proxy != null) {
            try {
                return (Object[]) recentJar.proxy.invoke(null, params);
            } catch (InvocationTargetException error) {
                throw cause(error);
            }
        }
        return null;
    }

    private LoadedJar loadedJar(String jarSpec) throws Exception {
        LoadedJar existing = loadedJars.get(jarSpec);
        if (existing != null) return existing;
        File jar = obtainJar(jarSpec);
        AppLog.i("Spider", "Spider JAR 已就绪");
        if (!jar.setReadOnly() && jar.canWrite()) {
            throw new IOException("无法保护 Spider JAR");
        }
        File optimized = new File(context.getFilesDir(), "spider-dex");
        if (!optimized.exists() && !optimized.mkdirs()) {
            throw new IOException("无法创建 Spider DEX 目录");
        }
        DexClassLoader loader = new DexClassLoader(
                jar.getAbsolutePath(), optimized.getAbsolutePath(), null, context.getClassLoader());
        invokeJarInit(loader);
        AppLog.d("Spider", "Spider JAR 初始化完成");
        Method proxy = null;
        try {
            proxy = loader.loadClass("com.github.catvod.spider.Proxy")
                    .getMethod("proxy", Map.class);
        } catch (ClassNotFoundException ignored) {
        } catch (NoSuchMethodException ignored) {
        }
        LoadedJar loaded = new LoadedJar(loader, proxy);
        loadedJars.put(jarSpec, loaded);
        return loaded;
    }

    private void invokeJarInit(DexClassLoader loader) throws Exception {
        try {
            Class<?> init = loader.loadClass("com.github.catvod.spider.Init");
            Method method = init.getMethod("init", Context.class);
            method.invoke(null, context);
        } catch (ClassNotFoundException ignored) {
        } catch (NoSuchMethodException ignored) {
        } catch (InvocationTargetException error) {
            throw cause(error);
        }
    }

    private void removeSpider(JavaSpiderSession session) {
        Spider spider = session.spider();
        Iterator<Map.Entry<String, Spider>> entries = siteSpiders.entrySet().iterator();
        while (entries.hasNext()) {
            if (entries.next().getValue() == spider) entries.remove();
        }
        if (recentSpider == spider) recentSpider = null;
    }

    private static Exception cause(InvocationTargetException error) {
        Throwable cause = error.getCause();
        if (cause instanceof Exception) return (Exception) cause;
        if (cause instanceof Error) throw (Error) cause;
        return new Exception(cause);
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
        AppLog.i("Spider", "Spider JAR 下载完成，大小 " + content.length + " 字节");
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

    private static String siteIdentity(TvBoxConfig.Site site) {
        return safe(site.sourceId) + "|" + safe(site.key);
    }

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
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new IOException("Spider 执行超时", timeout);
        }
    }

    private static final class LoadedJar {
        final DexClassLoader loader;
        final Method proxy;

        LoadedJar(DexClassLoader loader, Method proxy) {
            this.loader = loader;
            this.proxy = proxy;
        }
    }
}
