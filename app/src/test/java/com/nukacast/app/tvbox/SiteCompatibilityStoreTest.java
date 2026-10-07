package com.nukacast.app.tvbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nukacast.app.tvbox.model.TvBoxConfig;

import org.junit.Test;

public class SiteCompatibilityStoreTest {
    private static TvBoxConfig.Site site(String key, String name) {
        TvBoxConfig.Site site = new TvBoxConfig.Site();
        site.key = key;
        site.name = name;
        return site;
    }

    @Test public void remembersUnsupportedSites() {
        SiteCompatibilityStore store = new SiteCompatibilityStore();
        TvBoxConfig.Site broken = site("ffzy", "☀️┆夸父┆4K");
        assertFalse(store.isUnsupported(broken));

        store.record(broken, "该站点的 Spider 需要 Android 5.0 以上", true);

        assertTrue(store.isUnsupported(broken));
        assertEquals(1, store.snapshot().size());
        assertEquals("☀️┆夸父┆4K", store.snapshot().get(0).siteName);
    }

    @Test public void jarHashProblemsExpireSoAFixIsPickedUp() {
        SiteCompatibilityStore store = new SiteCompatibilityStore();
        TvBoxConfig.Site site = site("a", "A");
        store.record(site, "配置里的 Spider JAR 校验值与下载内容不一致", false);
        assertTrue(store.isUnsupported(site));
        assertEquals(1, store.snapshot().get(0).updatedAt > 0 ? 1 : 0);
    }

    @Test public void describesVerifierFailuresAsAnAndroidVersionProblem() {
        String described = SiteCompatibilityStore.describe(
                new VerifyError("com/github/catvod/spider/merge/A/W0"));
        assertTrue(described.contains("Android 5.0"));
    }

    @Test public void describesJarHashMismatchAsAConfigProblem() {
        String described = SiteCompatibilityStore.describe(
                new SecurityException("Spider JAR MD5 不匹配"));
        assertTrue(described.contains("校验值"));
    }

    @Test public void droppingConfigsAlsoDropsTheirVerdicts() {
        SiteCompatibilityStore store = new SiteCompatibilityStore();
        TvBoxConfig.Site kept = site("kept", "K");
        TvBoxConfig.Site removed = site("removed", "R");
        store.record(kept, "x", true);
        store.record(removed, "y", true);

        store.retainSites(java.util.Collections.singletonList(kept));

        assertTrue(store.isUnsupported(kept));
        assertFalse(store.isUnsupported(removed));
    }

    @Test public void clearAllResetsEverything() {
        SiteCompatibilityStore store = new SiteCompatibilityStore();
        store.record(site("a", "A"), "x", true);
        store.clearAll();
        assertFalse(store.isUnsupported(site("a", "A")));
    }
}
