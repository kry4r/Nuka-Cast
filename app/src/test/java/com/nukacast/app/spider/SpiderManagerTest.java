package com.nukacast.app.spider;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class SpiderManagerTest {
    @Test
    public void parsesFullSha256ForExecutableJar() {
        String hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        SpiderManager.JarSpec spec = SpiderManager.JarSpec.parse(
                "https://example.com/spider.jar;sha256=" + hash);

        assertEquals("https://example.com/spider.jar", spec.url);
        assertEquals("sha256", spec.algorithm);
        assertEquals(hash, spec.expectedHash);
    }

    @Test
    public void parsesCommonTvBoxMd5SyntaxAndVerifiesContent() {
        SpiderManager.JarSpec spec = SpiderManager.JarSpec.parse(
                "http://example.com/spider.jar;md5;5d41402abc4b2a76b9719d911017c592");

        assertEquals("md5", spec.algorithm);
        assertTrue(spec.matches("hello".getBytes()));
    }

    @Test
    public void allowsLegacyJarWithoutDeclaredDigest() {
        SpiderManager.JarSpec spec = SpiderManager.JarSpec.parse(
                "http://example.com/spider.jar");

        assertEquals("", spec.algorithm);
        assertEquals("", spec.expectedHash);
    }

    @Test(expected = SecurityException.class)
    public void rejectsMalformedDeclaredDigest() {
        SpiderManager.JarSpec.parse("https://example.com/spider.jar;md5;1234");
    }
}
