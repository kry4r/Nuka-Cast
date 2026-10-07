package com.nukacast.app.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Bundled configs are how the app avoids the 139-site public repos on a small TV, so their URL form
 * has to survive normalisation and be recognised everywhere a network URL is handled.
 */
public class BundledAssetsTest {
    @Test public void recognisesBundledUrls() {
        assertTrue(BundledAssets.isBundled("asset://sources/starter.json"));
        assertTrue(BundledAssets.isBundled("ASSET://sources/starter.json"));
        assertFalse(BundledAssets.isBundled("https://example.test/a.json"));
        assertFalse(BundledAssets.isBundled(null));
    }

    @Test public void extractsAssetPath() {
        assertEquals("sources/starter.json",
                BundledAssets.pathOf("asset://sources/starter.json"));
        assertEquals("sources/starter.json",
                BundledAssets.pathOf("asset:///sources/starter.json"));
        assertEquals("", BundledAssets.pathOf("https://example.test/a.json"));
    }

    @Test public void normalizerKeepsBundledUrlsIntact() {
        assertEquals("asset://sources/starter.json",
                UrlNormalizer.normalize("asset://sources/starter.json"));
        assertEquals("asset://sources/starter.json",
                UrlNormalizer.normalize("  asset:///sources/starter.json  "));
    }

    @Test public void normalizerStillHandlesNetworkUrls() {
        assertEquals("https://example.test/a.json",
                UrlNormalizer.normalize("example.test/a.json"));
        assertEquals("https://example.test/a.json",
                UrlNormalizer.normalize("我的仓库 https://example.test/a.json"));
    }

    @Test public void missingBundledFileIsReportedAsEmptyPath() {
        assertEquals("", BundledAssets.pathOf("asset://"));
    }
}
