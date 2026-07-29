package com.nukacast.app.tvbox;

import com.google.gson.Gson;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class ConfigPayloadResolverTest {
    private final ConfigPayloadResolver resolver = new ConfigPayloadResolver(
            new ConfigDecoder(new Gson()));

    @Test
    public void returnsPlainConfigWithoutDiscovery() throws Exception {
        FakeFetcher fetcher = new FakeFetcher().add("https://example.com/config.json",
                "{\"sites\":[{\"key\":\"one\",\"name\":\"中文源\",\"type\":3}]}",
                "application/json");

        ConfigPayloadResolver.Resolved resolved = resolver.resolve(
                "https://example.com/config.json", fetcher);

        assertEquals(1, fetcher.requests);
        assertEquals("中文源", resolved.document.config.sites.get(0).name);
        assertEquals("https://example.com/config.json", resolved.url);
    }

    @Test
    public void skipsInvalidCandidateAndStopsAtFirstValidConfig() throws Exception {
        FakeFetcher fetcher = new FakeFetcher()
                .add("https://example.com/", "<html><a href='/bad.json'>Bad</a>"
                                + "<a href='/good.json'>Good</a><a href='/later.json'>Later</a></html>",
                        "text/html; charset=UTF-8")
                .add("https://example.com/bad.json", "not json", "text/plain")
                .add("https://example.com/good.json", "{\"spider\":\"./spider.jar\"}",
                        "application/json")
                .add("https://example.com/later.json", "{\"spider\":\"never\"}",
                        "application/json");

        ConfigPayloadResolver.Resolved resolved = resolver.resolve("https://example.com/", fetcher);

        assertEquals(3, fetcher.requests);
        assertEquals("https://example.com/good.json", resolved.url);
        assertFalse(fetcher.wasFetched("https://example.com/later.json"));
    }

    @Test
    public void doesNotFollowHtmlCandidatesRecursively() throws Exception {
        FakeFetcher fetcher = new FakeFetcher()
                .add("https://example.com/", "<a href='/second.json'>Second</a>", "text/html")
                .add("https://example.com/second.json",
                        "<a href='/third.json'>Third</a>", "text/html")
                .add("https://example.com/third.json", "{\"spider\":\"too-deep\"}",
                        "application/json");

        try {
            resolver.resolve("https://example.com/", fetcher);
        } catch (IOException expected) {
            assertEquals(2, fetcher.requests);
            assertFalse(fetcher.wasFetched("https://example.com/third.json"));
            return;
        }
        throw new AssertionError("Expected HTML discovery failure");
    }

    @Test
    public void reportsCandidateUrlWhenLinkedConfigCannotBeFetched() throws Exception {
        FakeFetcher fetcher = new FakeFetcher().add("https://example.com/",
                "<a href='https://cdn.example.com/config.json'>Config</a>", "text/html");

        try {
            resolver.resolve("https://example.com/", fetcher);
        } catch (IOException expected) {
            org.junit.Assert.assertTrue(expected.getMessage().contains(
                    "https://cdn.example.com/config.json"));
            org.junit.Assert.assertTrue(expected.getMessage().contains("Missing test payload"));
            return;
        }
        throw new AssertionError("Expected linked config failure");
    }

    private static final class FakeFetcher implements ConfigPayloadResolver.Fetcher {
        private final Map<String, ConfigPayloadResolver.Payload> payloads =
                new LinkedHashMap<String, ConfigPayloadResolver.Payload>();
        private final Map<String, Boolean> fetched = new LinkedHashMap<String, Boolean>();
        int requests;

        FakeFetcher add(String url, String content, String contentType) {
            payloads.put(url, new ConfigPayloadResolver.Payload(url,
                    content.getBytes(Charset.forName("UTF-8")), contentType));
            return this;
        }

        @Override public ConfigPayloadResolver.Payload fetch(String url) throws IOException {
            requests++;
            fetched.put(url, true);
            ConfigPayloadResolver.Payload payload = payloads.get(url);
            if (payload == null) throw new IOException("Missing test payload: " + url);
            return payload;
        }

        boolean wasFetched(String url) {
            return fetched.containsKey(url);
        }
    }
}
