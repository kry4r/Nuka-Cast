package com.nukacast.app.spider;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 饭太硬/王二小 declare no per-site {@code jar}: the JAR (or .js) comes from the configuration's
 * global {@code spider}. Reading only {@code site.jar} classified all 53 of their plugin sites as
 * loadable, and the retry loop that followed is what killed the process on Android 4.4.
 */
public class JarSpiderDetectTest {
    @Test
    public void globalSpiderDecidesTheRuntime() {
        assertTrue(SpiderManager.isJarSpiderSite("", "./fty.jar", "csp_Ddrk"));
        assertTrue(SpiderManager.isJarSpiderSite("", "http://x/a.jpg;md5;abc", "csp_KaNQiu"));
        assertTrue(SpiderManager.isJarSpiderSite("./site.jar", "", "csp_X"));
        // A JS spider runs in the bundled engine and works on API 19.
        assertFalse(SpiderManager.isJarSpiderSite("", "./spider.js", "csp_WeX"));
        assertFalse(SpiderManager.isJarSpiderSite("", "http://cdn/x.js?t=1", "csp_WeX"));
        // A plain JSON site has no plugin at all.
        assertFalse(SpiderManager.isJarSpiderSite("", "", "http://api.example.com/vod"));
    }

    @Test
    public void platformThreshold() {
        assertFalse(SpiderManager.jarSpidersSupportedFor(19));
        assertTrue(SpiderManager.jarSpidersSupportedFor(21));
    }
}
