package com.nukacast.app.spider;

import android.content.Context;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderApi;
import com.nukacast.app.net.HttpStack;
import com.nukacast.app.net.ResponseBodies;
import com.nukacast.app.diagnostics.AppLog;
import com.nukacast.app.diagnostics.ProcessMemory;
import com.nukacast.app.diagnostics.SessionMarker;
import com.nukacast.app.diagnostics.StageTrace;

import java.util.LinkedHashMap;
import java.util.Map;
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
    /**
     * Upper bound on live plugin sessions. A session is either a QuickJS runtime (several MB of
     * native memory for a drpy bundle) or a DexClassLoader-owned spider instance, and none of that
     * shows up in the Java heap. The old fixed cap of 64 was reachable with a 139-site config and
     * pushed a 1 GB TV into the platform's memory killer; the limit is now derived from the device.
     */
    private static final int MIN_SESSIONS = 4;
    private static final int MAX_SESSIONS_LIMIT = 16;
    /** Plugin initialisation is serialised: starting twelve JS engines at once spikes memory. */
    private static final int MAX_CONCURRENT_INITIALISATIONS = 2;
    /** A session untouched for this long is destroyed instead of cached. */
    private static final long SESSION_IDLE_MS = 60_000L;
    /** After hitting the memory budget, plugin work stays paused for this long. */
    private static final long SHED_COOLDOWN_MS = 60_000L;
    private static final long CALL_TIMEOUT_SECONDS = 10L;
    private final Context context;
    private final JarTrustStore trustStore;
    private final int maxSessions;
    private final int maxJsSessions;
    private volatile long shedUntilMs;
    private final java.util.concurrent.Semaphore initialisationSlots =
            new java.util.concurrent.Semaphore(MAX_CONCURRENT_INITIALISATIONS, true);
    private final Map<String, SpiderSession> sessions = new HashMap<String, SpiderSession>();
    private final Map<String, Long> sessionUsedAt = new HashMap<String, Long>();
    private final Map<String, LoadedJar> loadedJars = new HashMap<String, LoadedJar>();
    /**
     * Failed JAR loads, keyed the same way as {@link #loadedJars}. Six sites sharing one JAR used to
     * download and re-validate it six times per refresh, and a JAR with a stale MD5 failed on every
     * one of them.
     */
    private final Map<String, JarFailure> jarFailures = new HashMap<String, JarFailure>();
    private final Map<String, Spider> siteSpiders = new HashMap<String, Spider>();
    private final Map<String, LoadedJar> siteJars = new HashMap<String, LoadedJar>();
    private final ExecutorService calls = Executors.newFixedThreadPool(4);
    private LoadedJar recentJar;
    private Spider recentSpider;

    private final com.nukacast.app.tvbox.SiteCompatibilityStore compatibility =
            new com.nukacast.app.tvbox.SiteCompatibilityStore();

    /** Sites known to be unusable here, so search and home can skip them with a reason. */
    public com.nukacast.app.tvbox.SiteCompatibilityStore compatibility() {
        return compatibility;
    }

    /** Structured session counts for the debug API; {@link #sessionSummary()} is the UI text. */
    public Map<String, Object> sessionDetail() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        synchronized (this) {
            long now = System.currentTimeMillis();
            int jar = 0;
            int js = 0;
            int idle = 0;
            for (Map.Entry<String, SpiderSession> entry : sessions.entrySet()) {
                if (entry.getValue() instanceof JavaSpiderSession) jar++;
                else js++;
                Long usedAt = sessionUsedAt.get(entry.getKey());
                if (usedAt != null && now - usedAt > SESSION_IDLE_MS) idle++;
            }
            summary.put("total", sessions.size());
            summary.put("jarSessions", jar);
            summary.put("jsSessions", js);
            summary.put("idleSessions", idle);
            summary.put("maxSessions", maxSessions);
            summary.put("maxJsSessions", maxJsSessions);
            summary.put("pausedForMemory", pausedForMemory());
            summary.put("downloadedJars", loadedJars.size());
            summary.put("failedJars", jarFailures.size());
        }
        return summary;
    }

    /** Device-specific plugin failures (Dalvik verifier rejections, JAR hash mismatches). */
    public List<com.nukacast.app.tvbox.SiteCompatibilityStore.Issue> compatibilitySnapshot() {
        return compatibility.snapshot();
    }

    public SpiderManager(Context context) {
        this.context = context.getApplicationContext();
        this.maxSessions = sessionBudget(context);
        this.maxJsSessions = Math.max(2, this.maxSessions / 3);
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

    public synchronized void clearCompatibility() {
        compatibility.clearAll();
    }

    public synchronized void destroy() {
        for (SpiderSession session : sessions.values()) {
            try {
                session.destroy();
            } catch (RuntimeException ignored) {}
        }
        sessions.clear();
        sessionUsedAt.clear();
        siteSpiders.clear();
        siteJars.clear();
        loadedJars.clear();
        jarFailures.clear();
        compatibility.clearAll();
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

    /** Key that ignores site-specific extension parameters: the same URL must load once. */
    private static String jarKey(JarSpec spec) {
        return spec.url + "|" + spec.expectedHash + "|" + spec.algorithm;
    }

    private static final long JAR_FAILURE_RETRY_MS = 10 * 60 * 1000L;

    private static final class JarFailure {
        final String message;
        final long at;

        JarFailure(String message) {
            this.message = message;
            this.at = System.currentTimeMillis();
        }

        boolean fresh() {
            return System.currentTimeMillis() - at < JAR_FAILURE_RETRY_MS;
        }
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
            sessionUsedAt.remove(entry.getKey());
            iterator.remove();
        }
        LoadedJar removed = loadedJars.remove(spec);
        jarFailures.remove(spec);
        Iterator<Map.Entry<String, LoadedJar>> jars = siteJars.entrySet().iterator();
        while (jars.hasNext()) if (jars.next().getValue() == removed) jars.remove();
        if (recentJar == removed) recentJar = null;
    }

    /**
     * Refuses to start more plugin work when the process is already at its budget.
     *
     * <p>The failure mode being prevented: a 139-site config makes the app create JS runtimes and
     * dex-loaded spiders until the platform's killer ends the process with no Java trace at all.
     * Here the app sheds everything it can, then lets CMS sites keep working instead of gambling on
     * the killer's threshold.
     */
    private void requireMemoryHeadroom() throws IllegalStateException {
        long budget = ProcessMemory.pluginBudgetBytes(context);
        long rss = ProcessMemory.rssBytes();
        if (rss <= 0L) return;
        if (rss >= budget) {
            dropSessions("超过内存预算");
            shedUntilMs = System.currentTimeMillis() + SHED_COOLDOWN_MS;
            throw new IllegalStateException("插件已暂停：本进程 " + ProcessMemory.megabytes(rss)
                    + " 超过预算 " + ProcessMemory.megabytes(budget));
        }
        if (rss >= budget * 8L / 10L) {
            // Close to the limit: release the cache now so the current request still fits.
            dropSessions("接近内存预算");
        }
    }

    /** True while plugin work is paused after hitting the budget. */
    public boolean pausedForMemory() {
        return System.currentTimeMillis() < shedUntilMs;
    }

    private synchronized SpiderSession session(TvBoxConfig.Site site) throws Exception {
        requireMemoryHeadroom();
        StageTrace.Trace trace = StageTrace.start("spider", siteIdentity(site));
        try {
            if (QuickJsSpiderSession.supports(site)) {
                trace.stage("js_session");
                String sessionKey = "js|" + siteIdentity(site) + "|" + safe(site.api)
                        + "|" + site.extension();
                SpiderSession existing = sessions.get(sessionKey);
                if (existing != null) {
                    touch(sessionKey);
                    trace.success();
                    return existing;
                }
                ensureSessionSlot(true);
                SpiderSession created = createJsSession(site);
                rememberSession(sessionKey, created);
                trace.success();
                return created;
            }
            trace.stage("jar_session");
            String jarSpec = firstNonEmpty(site.jar, site.globalSpider);
            if (jarSpec.isEmpty()) {
                throw new IllegalStateException("站点未配置 Spider JAR");
            }
            if (isJarSpiderSite(site.jar, site.globalSpider, site.api) && !jarSpidersSupported()) {
                // Measured on the affected TV: a config whose sites are all JAR spiders (饭太硬,
                // 王二小) makes the app load DexClassLoader plugins until the process dies ~50s after
                // launch, with no Java exception and no log. On API < 21 the JARs cannot work anyway
                // — Dalvik rejects their bytecode with VerifyError — so the honest answer is to refuse
                // them once, record why, and let every later load and search skip the site.
                String reason = "该站点需要 Android 5.0 以上的插件运行环境（本机 "
                        + android.os.Build.VERSION.SDK_INT + "，JAR 无法加载）";
                compatibility.record(site, reason, true);
                IllegalStateException refused = new IllegalStateException(reason);
                trace.failure(refused);
                throw refused;
            }
            String className = spiderClassName(site.api);
            LoadedJar loaded = loadedJar(jarSpec);
            String sessionKey = jarSpec + "|" + siteIdentity(site) + "|" + className
                    + "|" + site.extension();
            SpiderSession existing = sessions.get(sessionKey);
            if (existing != null) {
                touch(sessionKey);
                trace.success();
                return existing;
            }
            ensureSessionSlot(false);
            trace.stage("plugin_init");
            Class<?> type = loadClass(loaded, className);
            Object instance = type.newInstance();
            if (!(instance instanceof Spider)) {
                throw new IllegalStateException(className + " 未继承 CatVod Spider");
            }
            Spider spider = (Spider) instance;
            spider.siteKey = safe(site.key);
            spider.initApi(new SpiderApi(context));
            spider.init(context, site.extension());
            SpiderSession created = new JavaSpiderSession(spider);
            rememberSession(sessionKey, created);
            siteSpiders.put(siteIdentity(site), spider);
            siteJars.put(siteIdentity(site), loaded);
            trace.success();
            return created;
        } catch (Throwable error) {
            trace.failure(error);
            recordIfUnusable(site, error);
            throw error;
        }
    }

    /**
     * Turns a plugin load failure into a recorded verdict. A Dalvik verifier rejection or a JAR
     * hash mismatch cannot be fixed by retrying, and retrying it on every home load floods the log
     * while never succeeding.
     */
    private void recordIfUnusable(TvBoxConfig.Site site, Throwable error) {
        if (site == null || error == null) return;
        String name = error.getClass().getName();
        boolean permanent = name.endsWith("VerifyError")
                || name.endsWith("IncompatibleClassChangeError")
                || name.endsWith("NoClassDefFoundError");
        boolean jarProblem = error instanceof SecurityException
                || (error.getMessage() != null && error.getMessage().contains("Spider JAR"));
        if (!permanent && !jarProblem) return;
        boolean first = !compatibility.isUnsupported(site);
        String reason = com.nukacast.app.tvbox.SiteCompatibilityStore.describe(error);
        compatibility.record(site, reason, permanent);
        if (first) {
            AppLog.w("Spider", "站点不可用 [" + safe(site.name) + "]：" + reason);
        }
    }

    private void rememberSession(String key, SpiderSession session) {
        sessions.put(key, session);
        touch(key);
        SessionMarker.publishPluginSessions("插件会话 " + sessions.size() + "/" + maxSessions);
    }

    /**
     * Destroys every live plugin session. Called when the platform reports memory pressure: the
     * sessions are rebuildable, and dropping them is what actually returns native memory to the
     * device — a Java {@code System.gc()} would not touch a QuickJS runtime.
     */
    public synchronized void dropSessions(String reason) {
        if (sessions.isEmpty()) return;
        String summary = sessionSummary();
        for (SpiderSession session : sessions.values()) {
            if (session instanceof JavaSpiderSession) removeSpider((JavaSpiderSession) session);
            try {
                session.destroy();
            } catch (RuntimeException ignored) {
                // Already broken; nothing to preserve.
            }
        }
        sessions.clear();
        sessionUsedAt.clear();
        SessionMarker.publishPluginSessions(sessionSummary());
        AppLog.i("Spider", "释放插件会话（" + reason + "）：" + summary);
    }

    /**
     * Creates a JS session with the initialisation slot held, so a bulk search cannot start a dozen
     * QuickJS runtimes at the same moment.
     */
    private SpiderSession createJsSession(TvBoxConfig.Site site) throws Exception {
        boolean acquired = false;
        try {
            acquired = initialisationSlots.tryAcquire(20, java.util.concurrent.TimeUnit.SECONDS);
            return new QuickJsSpiderSession(site);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("会话初始化被中断", interrupted);
        } finally {
            if (acquired) initialisationSlots.release();
        }
    }

    /** Loads the plugin class with the same guard, because dex verification is the expensive part. */
    private Class<?> loadClass(LoadedJar loaded, String className) throws Exception {
        boolean acquired = false;
        try {
            acquired = initialisationSlots.tryAcquire(20, java.util.concurrent.TimeUnit.SECONDS);
            return loaded.loader.loadClass(className);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("插件初始化被中断", interrupted);
        } finally {
            if (acquired) initialisationSlots.release();
        }
    }

    private void touch(String key) {
        sessionUsedAt.put(key, System.currentTimeMillis());
    }

    /**
     * Evicts the least recently used session when the cache is full instead of failing every
     * later request, which keeps the API 19 resource budget bounded without a hard error wall.
     */
    private void ensureSessionSlot(boolean forJsSession) {
        // Idle first: a session untouched for a minute is worth more as free memory than as a cache.
        evictIdle();
        // JS runtimes first: they cost the most native memory per session.
        while (forJsSession && countJsSessions() >= maxJsSessions && !sessions.isEmpty()) {
            if (!evictOldest("js|")) break;
        }
        while (sessions.size() >= maxSessions && !sessions.isEmpty()) {
            String oldestKey = null;
            long oldest = Long.MAX_VALUE;
            for (String key : sessions.keySet()) {
                Long used = sessionUsedAt.get(key);
                long value = used == null ? 0L : used;
                if (value < oldest) {
                    oldest = value;
                    oldestKey = key;
                }
            }
            if (oldestKey == null) return;
            SpiderSession victim = sessions.remove(oldestKey);
            sessionUsedAt.remove(oldestKey);
            if (victim instanceof JavaSpiderSession) removeSpider((JavaSpiderSession) victim);
            try {
                victim.destroy();
            } catch (RuntimeException ignored) {}
            AppLog.d("Spider", "会话缓存已满，释放最久未用的会话：" + sessionSummary());
        }
    }

    private int countJsSessions() {
        int count = 0;
        for (String key : sessions.keySet()) {
            if (key.startsWith("js|")) count++;
        }
        return count;
    }

    /** Sessions cost native memory even while idle, so old ones are released proactively. */
    private void evictIdle() {
        long now = System.currentTimeMillis();
        java.util.List<String> idle = new java.util.ArrayList<String>();
        for (Map.Entry<String, Long> entry : sessionUsedAt.entrySet()) {
            if (now - entry.getValue() >= SESSION_IDLE_MS) idle.add(entry.getKey());
        }
        for (String key : idle) {
            SpiderSession session = sessions.remove(key);
            sessionUsedAt.remove(key);
            if (session == null) continue;
            if (session instanceof JavaSpiderSession) removeSpider((JavaSpiderSession) session);
            try {
                session.destroy();
            } catch (RuntimeException ignored) {
                // Nothing to preserve.
            }
        }
        if (!idle.isEmpty()) {
            SessionMarker.publishPluginSessions(sessionSummary());
            AppLog.d("Spider", "释放 " + idle.size() + " 个空闲会话：" + sessionSummary());
        }
    }

    /** Evicts the least recently used session whose key starts with {@code prefix}. */
    private boolean evictOldest(String prefix) {
        String oldestKey = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<String, SpiderSession> entry : sessions.entrySet()) {
            if (prefix != null && !entry.getKey().startsWith(prefix)) continue;
            Long used = sessionUsedAt.get(entry.getKey());
            long value = used == null ? 0L : used;
            if (value < oldest) {
                oldest = value;
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey == null) return false;
        SpiderSession victim = sessions.remove(oldestKey);
        sessionUsedAt.remove(oldestKey);
        if (victim instanceof JavaSpiderSession) removeSpider((JavaSpiderSession) victim);
        try {
            victim.destroy();
        } catch (RuntimeException ignored) {}
        return true;
    }

    private synchronized void pinProxy(TvBoxConfig.Site site) {
        recentJar = siteJars.get(siteIdentity(site));
        recentSpider = siteSpiders.get(siteIdentity(site));
    }

    public synchronized Object[] proxy(Map<String, String> params) throws Exception {
        if (params == null) return null;
        if (params.containsKey("do")) {
            Spider spider = findSiteSpider(safe(params.get("siteKey")));
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
        JarSpec parsed = JarSpec.parse(jarSpec);
        String key = jarKey(parsed);
        LoadedJar existing = loadedJars.get(key);
        if (existing != null) return existing;
        JarFailure previous = jarFailures.get(key);
        if (previous != null && previous.fresh()) {
            // Same JAR, same verdict: fail fast instead of downloading it again for every site.
            throw new SecurityException(previous.message);
        }
        File jar;
        try {
            jar = obtainJar(jarSpec);
        } catch (Exception error) {
            jarFailures.put(key, new JarFailure(error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage()));
            throw error;
        }
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
        loadedJars.put(key, loaded);
        jarFailures.remove(key);
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

    /**
     * Proxy requests only carry a site key, and keys can repeat across sources. Prefer the spider
     * most recently pinned for an active request, then any spider whose site key matches, so a
     * different source's instance is never mixed into the request when a collision exists.
     */
    private Spider findSiteSpider(String siteKey) {
        if (recentSpider != null && siteKey.equals(safe(recentSpider.siteKey))) {
            return recentSpider;
        }
        for (Spider candidate : siteSpiders.values()) {
            if (siteKey.equals(safe(candidate.siteKey))) return candidate;
        }
        return recentSpider;
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

    /**
     * Session budget from the device's total RAM: plugins are native-memory heavy, so a small box
     * gets a small budget. Read from {@code MemoryInfo.totalMem} (API 16+) rather than the Java heap
     * limit, which says nothing about the QuickJS runtimes.
     */
    static int sessionBudget(Context context) {
        long totalBytes = 0L;
        try {
            android.app.ActivityManager manager =
                    (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo info = new android.app.ActivityManager.MemoryInfo();
            if (manager != null) manager.getMemoryInfo(info);
            totalBytes = info.totalMem;
        } catch (Throwable ignored) {
            totalBytes = 0L;
        }
        long totalMb = totalBytes / (1024L * 1024L);
        int budget;
        if (totalMb <= 0L) budget = 8;            // unknown device: stay conservative
        else if (totalMb <= 512L) budget = MIN_SESSIONS;
        else if (totalMb <= 1024L) budget = 8;
        else if (totalMb <= 2048L) budget = 12;
        else budget = MAX_SESSIONS_LIMIT;
        return Math.max(MIN_SESSIONS, Math.min(MAX_SESSIONS_LIMIT, budget));
    }

    /** Live session counts, for diagnostics: JS runtimes are the ones that hurt. */
    public synchronized String sessionSummary() {
        int js = 0;
        int jar = 0;
        for (String key : sessions.keySet()) {
            if (key.startsWith("js|")) js++;
            else jar++;
        }
        return "插件会话 " + sessions.size() + "/" + maxSessions + "（JS " + js + "/" + maxJsSessions
                + " · JAR " + jar + "）";
    }

    private static String safe(String value) { return value == null ? "" : value; }

    /**
     * JAR spiders need a runtime that can verify modern bytecode. Android 4.4 (Dalvik) cannot, and
     * repeatedly trying costs the whole process, so they are refused on this platform.
     */
    public static boolean jarSpidersSupported() {
        return jarSpidersSupportedFor(android.os.Build.VERSION.SDK_INT);
    }

    /**
     * Whether a site is a JAR spider, i.e. needs a runtime that Dalvik cannot provide.
     *
     * <p>Decided from the spec the site would actually load: sites often carry no {@code jar} of their
     * own and inherit the configuration's global {@code spider} (饭太硬's 53 sites do exactly that),
     * so looking at {@code site.jar} alone classified them as loadable and they were retried on every
     * home load — the loop that killed the process on Android 4.4.
     */
    public static boolean isJarSpiderSite(String siteJar, String globalSpider, String siteApi) {
        String spec = firstNonEmpty(siteJar, globalSpider);
        if (spec == null || spec.trim().isEmpty()) return false;
        String lower = spec.trim().toLowerCase(java.util.Locale.ROOT);
        // A .js spider runs in the bundled engine and works on every supported Android version.
        return !(lower.endsWith(".js") || lower.contains(".js?"));
    }

    /** Threshold logic, separated so it can be tested on any runtime. */
    static boolean jarSpidersSupportedFor(int sdkInt) {
        return sdkInt >= 21;
    }

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
