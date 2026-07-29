package com.nukacast.app.tvbox;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class ConfigLinkDiscoveryTest {
    @Test
    public void ranksLinkedJsonConfigAheadOfUnrelatedLinks() {
        String html = "<a href='https://example.com/releases'>Download</a>"
                + "<a href='https://6800.kstore.vip/fish.json'>Backup</a>";

        List<String> candidates = ConfigLinkDiscovery.candidates(
                html, "https://www.xn--ihq545aq7p.com/");

        assertEquals(1, candidates.size());
        assertEquals("https://6800.kstore.vip/fish.json", candidates.get(0));
    }

    @Test
    public void resolvesRelativeJsonAndAcceptsTvboxPhp() {
        String html = "<a href='/nested/config.json'>Config</a>"
                + "<a href='http://a.qiqiv.cn/tvbox.php'>TVBox</a>";

        List<String> candidates = ConfigLinkDiscovery.candidates(
                html, "https://example.com/home/index.html");

        assertEquals("https://example.com/nested/config.json", candidates.get(0));
        assertEquals("http://a.qiqiv.cn/tvbox.php", candidates.get(1));
    }

    @Test
    public void rejectsNonHttpAndDeduplicatesLinks() {
        String html = "<a href='javascript:alert(1)'>Bad</a>"
                + "<a href='file:///tmp/config.json'>File</a>"
                + "<a href='/config.json'>First</a>"
                + "<a href='https://example.com/config.json'>Duplicate</a>";

        List<String> candidates = ConfigLinkDiscovery.candidates(
                html, "https://example.com/");

        assertEquals(1, candidates.size());
        assertEquals("https://example.com/config.json", candidates.get(0));
    }

    @Test
    public void limitsCandidateCount() {
        StringBuilder html = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            html.append("<a href='/config-").append(i).append(".json'>Config</a>");
        }

        List<String> candidates = ConfigLinkDiscovery.candidates(
                html.toString(), "https://example.com/");

        assertEquals(8, candidates.size());
        assertFalse(candidates.contains("https://example.com/config-8.json"));
    }
}
