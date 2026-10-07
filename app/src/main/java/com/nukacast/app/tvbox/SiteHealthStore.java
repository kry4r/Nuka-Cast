package com.nukacast.app.tvbox;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.nukacast.app.tvbox.model.TvBoxConfig;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which sites were last seen working, and why the others were not.
 *
 * <p>Community configs hand out 50-140 sites and many of them cannot possibly work on a given
 * device: plugin JARs needing Android 5+, hosts that no longer resolve, or APIs that answer 404.
 * Measured on a real Android 4.4 TV, a home load started a dozen spider sessions and most failed,
 * which is both why the first screen looked empty and why the process grew the way it did.
 *
 * <p>One sweep records the truth; afterwards search and home spend their budget on sites that
 * answered. Verdicts are kept on disk because a sweep costs several minutes, with a shorter
 * time-to-live for failures so a fixed site is picked up again the same day.
 */
public final class SiteHealthStore {
    /** A working site stays trusted for this long; the list of working sites changes slowly. */
    static final long HEALTHY_TTL_MS = 6 * 60 * 60 * 1000L;
    /** A failure is retried after this long: dead hosts come back. */
    static final long FAILED_TTL_MS = 30 * 60 * 1000L;
    private static final String PREFS = "nukacast-site-health";
    private static final String KEY = "verdicts";
    private static final int MAX_VERDICTS = 400;

    public static final class Verdict {
        public String siteKey = "";
        public String siteName = "";
        public String sourceId = "";
        public boolean ok;
        public String reason = "";
        public int itemCount;
        public long latencyMs;
        public long checkedAt;

        public boolean isFresh() {
            long age = System.currentTimeMillis() - checkedAt;
            return age < (ok ? HEALTHY_TTL_MS : FAILED_TTL_MS);
        }
    }

    private final SharedPreferences prefs;
    private final Gson gson = new Gson();
    private final Map<String, Verdict> verdicts = new LinkedHashMap<String, Verdict>();
    private boolean loaded;

    public SiteHealthStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void load() {
        if (loaded) return;
        loaded = true;
        String raw = prefs.getString(KEY, null);
        if (raw == null || raw.isEmpty()) return;
        try {
            Type type = new TypeToken<List<Verdict>>() { }.getType();
            List<Verdict> stored = gson.fromJson(raw, type);
            if (stored == null) return;
            for (Verdict verdict : stored) {
                if (verdict != null && verdict.siteKey != null && !verdict.siteKey.isEmpty()) {
                    verdicts.put(verdict.siteKey, verdict);
                }
            }
        } catch (Exception ignored) {
            verdicts.clear();
        }
    }

    public synchronized Verdict of(String siteKey) {
        load();
        Verdict verdict = verdicts.get(siteKey);
        return verdict != null && verdict.isFresh() ? verdict : null;
    }

    /** True when the site was recently measured and answered. */
    public synchronized boolean isKnownGood(TvBoxConfig.Site site) {
        Verdict verdict = of(key(site));
        return verdict != null && verdict.ok;
    }

    /** True when the site was recently measured and could not be used. */
    public synchronized boolean isKnownBad(TvBoxConfig.Site site) {
        Verdict verdict = of(key(site));
        return verdict != null && !verdict.ok;
    }

    public synchronized void record(TvBoxConfig.Site site, boolean ok, int itemCount,
                                    long latencyMs, String reason) {
        load();
        Verdict verdict = new Verdict();
        verdict.siteKey = key(site);
        verdict.siteName = site.name == null ? "" : site.name;
        verdict.sourceId = site.sourceId == null ? "" : site.sourceId;
        verdict.ok = ok;
        verdict.itemCount = itemCount;
        verdict.latencyMs = latencyMs;
        verdict.reason = reason == null ? "" : reason;
        verdict.checkedAt = System.currentTimeMillis();
        verdicts.put(verdict.siteKey, verdict);
        while (verdicts.size() > MAX_VERDICTS) {
            String oldest = null;
            for (Map.Entry<String, Verdict> entry : verdicts.entrySet()) {
                if (oldest == null || entry.getValue().checkedAt < verdicts.get(oldest).checkedAt) {
                    oldest = entry.getKey();
                }
            }
            if (oldest == null) break;
            verdicts.remove(oldest);
        }
        save();
    }

    public synchronized List<Verdict> snapshot() {
        load();
        List<Verdict> result = new ArrayList<Verdict>(verdicts.values());
        Collections.sort(result, new Comparator<Verdict>() {
            @Override public int compare(Verdict left, Verdict right) {
                if (left.ok != right.ok) return left.ok ? -1 : 1;
                return left.siteName.compareToIgnoreCase(right.siteName);
            }
        });
        return result;
    }

    /** Number of fresh verdicts, split into working and not working. */
    public synchronized int[] counts() {
        load();
        int good = 0;
        int bad = 0;
        for (Verdict verdict : verdicts.values()) {
            if (!verdict.isFresh()) continue;
            if (verdict.ok) good++;
            else bad++;
        }
        return new int[]{good, bad};
    }

    public synchronized long lastCheckedAt() {
        load();
        long newest = 0L;
        for (Verdict verdict : verdicts.values()) {
            if (!verdict.isFresh()) continue;
            newest = Math.max(newest, verdict.checkedAt);
        }
        return newest;
    }

    public synchronized void clear() {
        load();
        verdicts.clear();
        save();
    }

    /** Drops verdicts for sites that are no longer in any enabled config. */
    public synchronized void retainKeys(java.util.Collection<String> keys) {
        load();
        boolean changed = verdicts.keySet().retainAll(keys);
        if (changed) save();
    }

    private void save() {
        try {
            prefs.edit().putString(KEY, gson.toJson(new ArrayList<Verdict>(verdicts.values()))).apply();
        } catch (Exception ignored) {
            // Health data is an optimisation; losing it only costs a re-sweep.
        }
    }

    private static String key(TvBoxConfig.Site site) {
        return site.key == null ? "" : site.key;
    }
}
