package com.nukacast.app.tvbox;

import com.nukacast.app.tvbox.model.TvBoxConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sites that cannot work on this device, with the reason.
 *
 * <p>Two real cases from an Android 4.4 TV: a CatVod JAR whose classes fail Dalvik verification
 * ({@code VerifyError}) and a JAR that does not match the MD5 declared in the config. Both failed on
 * every home load and every search, producing hundreds of identical log lines and wasting the
 * network for sites that could never succeed. Recording the verdict once lets the app skip them and
 * tell the user why, instead of retrying forever.
 */
public final class SiteCompatibilityStore {
    /** A JAR compiled for a newer runtime can never load here; only a new build would change that. */
    private static final long PERMANENT_MS = 0L;

    public static final class Issue {
        public final String siteKey;
        public final String siteName;
        public final String reason;
        public final boolean permanent;
        public final long updatedAt;

        Issue(TvBoxConfig.Site site, String reason, boolean permanent) {
            this.siteKey = safe(site.key);
            this.siteName = safe(site.name);
            this.reason = reason;
            this.permanent = permanent;
            this.updatedAt = System.currentTimeMillis();
        }
    }

    private final Map<String, Issue> issues = new ConcurrentHashMap<String, Issue>();

    public void record(TvBoxConfig.Site site, String reason, boolean permanent) {
        issues.put(safe(site.key), new Issue(site, reason, permanent));
    }

    public void clear(TvBoxConfig.Site site) {
        issues.remove(safe(site.key));
    }

    /** True when this site is known to be unusable, so callers can skip it instead of retrying. */
    public boolean isUnsupported(TvBoxConfig.Site site) {
        Issue issue = issues.get(safe(site.key));
        if (issue == null) return false;
        if (issue.permanent) return true;
        return System.currentTimeMillis() - issue.updatedAt < 10 * 60 * 1000L;
    }

    public List<Issue> snapshot() {
        List<Issue> result = new ArrayList<Issue>(issues.values());
        Collections.sort(result, new Comparator<Issue>() {
            @Override public int compare(Issue left, Issue right) {
                return left.siteName.compareToIgnoreCase(right.siteName);
            }
        });
        return result;
    }

    public void retainSites(List<TvBoxConfig.Site> sites) {
        java.util.Set<String> retained = new java.util.HashSet<String>();
        for (TvBoxConfig.Site site : sites) retained.add(safe(site.key));
        for (String key : new ArrayList<String>(issues.keySet())) {
            if (!retained.contains(key)) issues.remove(key);
        }
    }

    public void clearAll() {
        issues.clear();
    }

    /** Message shown to the user; the raw verifier text is kept for diagnostics. */
    public static String describe(Throwable error) {
        String name = error == null ? "" : error.getClass().getName();
        if (name.endsWith("VerifyError") || name.endsWith("IncompatibleClassChangeError")
                || name.endsWith("NoClassDefFoundError")) {
            return "该站点的 Spider 需要 Android 5.0 以上，当前设备无法运行";
        }
        String message = error == null || error.getMessage() == null
                ? "" : error.getMessage();
        if (message.contains("不匹配") && message.contains("Spider JAR")) {
            return "配置里的 Spider JAR 校验值与下载内容不一致，已拒绝加载";
        }
        return message.isEmpty() ? "该站点不可用" : message;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
