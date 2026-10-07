package com.nukacast.app.tvbox.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LivePlaylistTest {
    @Test public void derivesNameFromHostWhenMissing() {
        LivePlaylist playlist = LivePlaylist.of("", "https://cdn.jsdelivr.net/gh/bestK/iptv@main/iptv.m3u");
        assertEquals("cdn.jsdelivr.net", playlist.name);
        assertEquals("https://cdn.jsdelivr.net/gh/bestK/iptv@main/iptv.m3u", playlist.url);
        assertTrue(playlist.looksLikePlaylist());
    }

    @Test public void keepsExplicitName() {
        LivePlaylist playlist = LivePlaylist.of("  央视源  ", "http://a.test/live.txt");
        assertEquals("央视源", playlist.name);
        assertEquals("a.test", playlist.host());
    }

    @Test public void idsAreUniqueAndStableInShape() {
        LivePlaylist first = LivePlaylist.of("a", "http://a.test/1.m3u");
        LivePlaylist second = LivePlaylist.of("a", "http://a.test/1.m3u");
        assertEquals(16, first.id.length());
        assertNotEquals(first.id, second.id);
    }

    @Test public void rejectsNonHttpUrls() {
        assertFalse(LivePlaylist.of("local", "/sdcard/live.m3u").looksLikePlaylist());
        assertFalse(LivePlaylist.of("empty", "").looksLikePlaylist());
    }
}
