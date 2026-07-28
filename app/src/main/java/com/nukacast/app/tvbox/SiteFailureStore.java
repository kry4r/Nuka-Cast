package com.nukacast.app.tvbox;

import com.nukacast.app.tvbox.model.TvBoxConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SiteFailureStore {
    public static final class Failure {
        public final String sourceId;
        public final String siteKey;
        public final String siteName;
        public final String error;
        public final long updatedAt;

        Failure(TvBoxConfig.Site site, String error) {
            this.sourceId = safe(site.sourceId);
            this.siteKey = safe(site.key);
            this.siteName = safe(site.name);
            this.error = error;
            this.updatedAt = System.currentTimeMillis();
        }
    }

    private final Map<String, Failure> failures = new ConcurrentHashMap<String, Failure>();

    void failure(TvBoxConfig.Site site, Throwable error) {
        failure(site, message(error));
    }

    void failure(TvBoxConfig.Site site, String error) {
        failures.put(key(site), new Failure(site, error));
    }

    void success(TvBoxConfig.Site site) {
        failures.remove(key(site));
    }

    List<Failure> snapshot() {
        List<Failure> result = new ArrayList<Failure>(failures.values());
        Collections.sort(result, new Comparator<Failure>() {
            @Override public int compare(Failure left, Failure right) {
                return left.siteName.compareToIgnoreCase(right.siteName);
            }
        });
        return result;
    }

    void retainSites(List<TvBoxConfig.Site> sites) {
        Set<String> retained = new HashSet<String>();
        for (TvBoxConfig.Site site : sites) retained.add(key(site));
        for (String existing : new ArrayList<String>(failures.keySet())) {
            if (!retained.contains(existing)) failures.remove(existing);
        }
    }

    private static String key(TvBoxConfig.Site site) {
        return safe(site.sourceId) + "|" + safe(site.key);
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
