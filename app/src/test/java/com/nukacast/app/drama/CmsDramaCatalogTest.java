package com.nukacast.app.drama;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nukacast.app.drama.model.DramaEpisode;
import com.nukacast.app.drama.model.DramaItem;

import org.junit.Test;

import java.util.List;

/**
 * The CMS contract is defined by real resource-site payloads: {@code vod_play_url} entries joined
 * with {@code #}, parallel play lines joined with {@code $$$}, and plenty of sites publishing links
 * that still need an external parser. Only direct media URLs may reach the player.
 */
public class CmsDramaCatalogTest {
    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test public void mapsCmsFieldsIntoDramaItem() {
        DramaItem item = CmsDramaCatalog.item(json("{\"vod_id\":\"172687\",\"vod_name\":\"苦尽甘来遇见你\","
                + "\"vod_pic\":\"https://pic.test/a.jpg\",\"vod_content\":\"简介\","
                + "\"vod_remarks\":\"全87集\",\"type_name\":\"短剧\",\"type_id\":36,"
                + "\"vod_total\":\"87\",\"vod_hits\":\"1200\"}"));
        assertEquals("172687", item.dramaId);
        assertEquals("苦尽甘来遇见你", item.title);
        assertEquals("https://pic.test/a.jpg", item.cover);
        assertEquals("简介", item.intro);
        assertEquals("全87集", item.remark);
        assertEquals("短剧", item.category);
        assertEquals(87, item.episodeCount);
    }

    @Test public void parsesDirectEpisodesWithNames() {
        List<DramaEpisode> episodes = CmsDramaCatalog.parseEpisodes(json("{\"vod_play_url\":"
                + "\"第01集$https://cdn.test/1/index.m3u8#第02集$https://cdn.test/2/index.m3u8\"}"),
                "42");
        assertEquals(2, episodes.size());
        assertEquals(1, episodes.get(0).index);
        assertEquals("第01集", episodes.get(0).name);
        assertTrue(episodes.get(0).direct);
        assertTrue(episodes.get(0).isPlayable());
        assertEquals(2, episodes.get(1).index);
        assertEquals("https://cdn.test/2/index.m3u8", episodes.get(1).playUrl);
    }

    @Test public void keepsTheLongestParallelLine() {
        List<DramaEpisode> episodes = CmsDramaCatalog.parseEpisodes(json(
                "{\"vod_play_url\":\"第1集$https://a.test/1.m3u8#第2集$https://a.test/2.m3u8$$$"
                        + "第1集$https://b.test/1.m3u8#第2集$https://b.test/2.m3u8#第3集$https://b.test/3.m3u8\"}"),
                "7");
        assertEquals(3, episodes.size());
        assertEquals("https://b.test/3.m3u8", episodes.get(2).playUrl);
    }

    @Test public void dropsLinesThatStillNeedAnExternalParser() {
        List<DramaEpisode> episodes = CmsDramaCatalog.parseEpisodes(json("{\"vod_play_url\":"
                + "\"第1集$https://jx.test/?url=https://cdn.test/1.m3u8\"}"), "7");
        assertTrue("解析型链接不能当作可播地址", episodes.isEmpty());
    }

    @Test public void acceptsMediaUrlsWithQueryParameters() {
        assertTrue(CmsDramaCatalog.isDirectMediaUrl("https://cdn.test/1/index.m3u8?auth=1&t=2"));
        assertTrue(CmsDramaCatalog.isDirectMediaUrl("HTTP://CDN.TEST/A.MP4"));
        assertFalse(CmsDramaCatalog.isDirectMediaUrl("https://jx.test/?url=https://cdn.test/1.m3u8"));
        assertFalse(CmsDramaCatalog.isDirectMediaUrl("https://cdn.test/1/index.m3u8/play"));
        assertFalse(CmsDramaCatalog.isDirectMediaUrl("magnet:?xt=urn:x"));
        assertFalse(CmsDramaCatalog.isDirectMediaUrl(""));
    }

    @Test public void ignoresEmptyAndMalformedEntries() {
        List<DramaEpisode> episodes = CmsDramaCatalog.parseEpisodes(json("{\"vod_play_url\":"
                + "\"#$#第3集$https://cdn.test/3.mp4###\"}"), "9");
        assertEquals(1, episodes.size());
        assertEquals(3, episodes.get(0).index);
        assertTrue(episodes.get(0).direct);
    }

    @Test public void toleratesMissingPlayUrl() {
        assertTrue(CmsDramaCatalog.parseEpisodes(json("{\"vod_name\":\"x\"}"), "1").isEmpty());
        assertTrue(CmsDramaCatalog.parseEpisodes(json("{\"vod_play_url\":\"\"}"), "1").isEmpty());
    }

    @Test public void readsNineteenDigitIdsAsStrings() {
        DramaItem item = CmsDramaCatalog.item(json(
                "{\"vod_id\":\"7690192663693233177\",\"vod_name\":\"重生\"}"));
        assertEquals("7690192663693233177", item.dramaId);
        assertFalse(item.title.isEmpty());
    }
}
