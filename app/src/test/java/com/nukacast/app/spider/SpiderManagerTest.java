package com.nukacast.app.spider;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class SpiderManagerTest {
    @Test
    public void requiresFullSha256ForExecutableJar() {
        String hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        assertEquals(hash, SpiderManager.requiredSha256(
                "https://example.com/spider.jar;sha256=" + hash));
    }

    @Test(expected = SecurityException.class)
    public void rejectsJarWithoutDigest() {
        SpiderManager.requiredSha256("https://example.com/spider.jar");
    }
}
