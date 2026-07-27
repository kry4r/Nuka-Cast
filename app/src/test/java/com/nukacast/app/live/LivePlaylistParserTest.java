package com.nukacast.app.live;

import com.nukacast.app.live.model.LiveCatalog;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class LivePlaylistParserTest {
    @Test
    public void parsesM3uAttributesAndAlternativeUrls() {
        String body = "#EXTM3U\n" +
                "#EXTINF:-1 tvg-id=\"cctv1\" tvg-logo=\"https://img/c1.png\" group-title=\"央视\",CCTV-1 综合\n" +
                "https://one/live.m3u8#https://backup/live.m3u8\n";

        LiveCatalog catalog = LivePlaylistParser.parse(body);

        assertEquals(1, catalog.groups.size());
        assertEquals("央视", catalog.groups.get(0).name);
        assertEquals("cctv1", catalog.groups.get(0).channels.get(0).epgId);
        assertEquals(2, catalog.groups.get(0).channels.get(0).urls.size());
    }

    @Test
    public void parsesTvBoxTextGroups() {
        String body = "央视频道,#genre#\nCCTV-1,http://one\nCCTV-2,http://two\n" +
                "卫视频道,#genre#\n湖南卫视,http://hunan\n";

        LiveCatalog catalog = LivePlaylistParser.parse(body);

        assertEquals(2, catalog.groups.size());
        assertEquals(2, catalog.groups.get(0).channels.size());
        assertEquals("湖南卫视", catalog.groups.get(1).channels.get(0).name);
    }
}
