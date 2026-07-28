package com.nukacast.app.tvbox;

import com.nukacast.app.tvbox.model.TvBoxConfig;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class SiteFailureStoreTest {
    @Test
    public void recordsAndClearsAHomeFailureBySourceAndSite() {
        SiteFailureStore store = new SiteFailureStore();
        TvBoxConfig.Site site = new TvBoxConfig.Site();
        site.sourceId = "source";
        site.key = "site";
        site.name = "站点";

        store.failure(site, new IllegalStateException("Spider init failed"));
        assertEquals("Spider init failed", store.snapshot().get(0).error);

        store.success(site);
        assertTrue(store.snapshot().isEmpty());
    }

    @Test
    public void dropsFailuresForSitesRemovedByAWarehouseRefresh() {
        SiteFailureStore store = new SiteFailureStore();
        TvBoxConfig.Site removed = new TvBoxConfig.Site();
        removed.sourceId = "old-source";
        removed.key = "old-site";
        removed.name = "旧站点";
        store.failure(removed, "HTTP 404");

        store.retainSites(Collections.<TvBoxConfig.Site>emptyList());

        assertTrue(store.snapshot().isEmpty());
    }
}
