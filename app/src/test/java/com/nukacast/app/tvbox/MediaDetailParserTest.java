package com.nukacast.app.tvbox;

import com.nukacast.app.tvbox.model.MediaDetail;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class MediaDetailParserTest {
    @Test
    public void parsesMultiplePlayLinesAndEpisodes() {
        String json = "{\"list\":[{" +
                "\"vod_id\":\"42\",\"vod_name\":\"测试剧\"," +
                "\"vod_play_from\":\"线路A$$$线路B\"," +
                "\"vod_play_url\":\"第1集$https://a/1.m3u8#第2集$https://a/2.m3u8$$$正片$https://b/movie.mp4\"}]}";

        MediaDetail detail = MediaDetailParser.parse(json, "site", "测试站");

        assertEquals("测试剧", detail.name);
        assertEquals(2, detail.playSources.size());
        assertEquals(2, detail.playSources.get(0).episodes.size());
        assertEquals("https://a/2.m3u8", detail.playSources.get(0).episodes.get(1).id);
        assertEquals("线路B", detail.playSources.get(1).name);
    }

    @Test
    public void keepsDollarCharactersInsideEpisodeUrl() {
        String json = "{\"list\":[{\"vod_name\":\"演示\",\"vod_play_from\":\"源\"," +
                "\"vod_play_url\":\"正片$https://example/play?id=a$b\"}]}";

        MediaDetail detail = MediaDetailParser.parse(json, "site", "站点");

        assertEquals("https://example/play?id=a$b", detail.playSources.get(0).episodes.get(0).id);
    }
}
