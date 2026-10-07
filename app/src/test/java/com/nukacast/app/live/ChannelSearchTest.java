package com.nukacast.app.live;

import com.nukacast.app.live.model.LiveCatalog;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Finding a channel in an 11k-entry playlist is the difference between a usable live page and an
 * unusable one, so the matching rules are pinned here.
 */
public class ChannelSearchTest {
    @Test
    public void matchesByNameSubstring() {
        assertTrue(ChannelSearch.matches("湖南卫视", "湖南"));
        assertTrue(ChannelSearch.matches("CCTV-1 综合", "cctv1"));
        assertTrue(ChannelSearch.matches("Phoenix Chinese", "phoenix"));
    }

    @Test
    public void matchesByPinyinInitials() {
        assertTrue(ChannelSearch.matches("湖南卫视", "hnws"));
        assertTrue(ChannelSearch.matches("浙江卫视", "zj"));
        assertTrue(ChannelSearch.matches("东方卫视", "dfws"));
    }

    @Test
    public void rejectsUnrelatedNames() {
        assertEquals(false, ChannelSearch.matches("湖南卫视", "beijing"));
        assertEquals(false, ChannelSearch.matches("CCTV-5 体育", "hnws"));
        assertEquals(false, ChannelSearch.matches(null, "any"));
    }

    @Test
    public void returnsHitsInPlaylistOrderAndStampsTheGroup() {
        LiveCatalog catalog = new LiveCatalog();
        LiveCatalog.Group news = new LiveCatalog.Group();
        news.name = "新闻";
        addChannel(news, "CCTV-1 综合");
        addChannel(news, "CCTV-2 财经");
        LiveCatalog.Group local = new LiveCatalog.Group();
        local.name = "地方";
        addChannel(local, "湖南卫视");
        catalog.groups.add(news);
        catalog.groups.add(local);

        assertEquals(1, ChannelSearch.find(catalog, "湖南").size());
        assertEquals("地方", ChannelSearch.find(catalog, "湖南").get(0).group);
        assertEquals(2, ChannelSearch.find(catalog, "cctv").size());
        assertEquals(0, ChannelSearch.find(catalog, "").size());
        assertEquals(0, ChannelSearch.find(null, "x").size());
    }

    private static void addChannel(LiveCatalog.Group group, String name) {
        LiveCatalog.Channel channel = new LiveCatalog.Channel();
        channel.name = name;
        channel.urls.add("http://example.com/" + name + ".m3u8");
        group.channels.add(channel);
    }
}
