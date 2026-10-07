package com.nukacast.app.net;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class UrlNormalizerTest {
    @Test public void addsTheMissingScheme() {
        assertEquals("https://example.com/tvbox.json",
                UrlNormalizer.normalize("example.com/tvbox.json"));
    }

    @Test public void keepsHttpAndHttps() {
        assertEquals("http://example.com/a", UrlNormalizer.normalize("http://example.com/a"));
        assertEquals("https://example.com/a", UrlNormalizer.normalize("https://example.com/a"));
    }

    @Test public void trimsWhitespaceAndNewlines() {
        assertEquals("https://example.com/a", UrlNormalizer.normalize("  https://example.com/a\n"));
    }

    @Test public void extractsTheUrlFromAPastedTitleLine() {
        assertEquals("https://example.com/a.json",
                UrlNormalizer.normalize("我的仓库 https://example.com/a.json"));
    }

    @Test public void convertsNonAsciiHostsToPunycode() {
        // The device log had a config URL whose host was non-ASCII; OkHttp rejected it outright.
        String normalized = UrlNormalizer.normalize("https://中文域名.中国/box.json");
        assertTrue(normalized.startsWith("https://xn--"));
        assertTrue(normalized.endsWith("/box.json"));
    }

    @Test public void rejectsMojibakeHostsWithAnExplanation() {
        try {
            UrlNormalizer.normalize("https://\ufffd\ufffd\ufffd\ufffd\ufffd.cc/api");
            fail("expected a rejection");
        } catch (IllegalArgumentException error) {
            assertTrue(error.getMessage().contains("乱码"));
        }
    }

    @Test public void rejectsEmptyInput() {
        try {
            UrlNormalizer.normalize("   ");
            fail("expected a rejection");
        } catch (IllegalArgumentException error) {
            assertEquals("地址为空", error.getMessage());
        }
    }

    @Test public void keepsPortsAndQueries() {
        assertEquals("https://example.com:8443/a?b=c",
                UrlNormalizer.normalize("example.com:8443/a?b=c"));
    }
}
