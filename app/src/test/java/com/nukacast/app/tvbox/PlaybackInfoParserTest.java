package com.nukacast.app.tvbox;

import com.nukacast.app.tvbox.model.PlaybackInfo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PlaybackInfoParserTest {
    @Test
    public void convertsTvBoxProxySchemeToLocalSpiderEndpoint() {
        PlaybackInfo info = PlaybackInfoParser.parse(
                "{\"parse\":1,\"url\":\"proxy://go=SuperParse&flag=qq&url=YWJj\"}", "");

        assertEquals("http://127.0.0.1:9978/proxy?go=SuperParse&flag=qq&url=YWJj", info.url);
        assertFalse(info.direct);
        assertTrue(PlaybackInfoParser.isSpiderProxy(info.url));
    }

    @Test
    public void cmsHtmlEpisodeRequiresParserInsteadOfPretendingToBeDirect() {
        PlaybackInfo info = PlaybackInfoParser.episode("https://video.example/watch/123");
        assertFalse(info.direct);
        assertTrue(info.parse == 1);
    }

    @Test
    public void detectsMediaExtensionFromPathButNotEncodedParserQuery() {
        assertTrue(PlaybackInfoParser.isDirectMedia("https://cdn.example/v/one.m3u8?token=1"));
        assertFalse(PlaybackInfoParser.isDirectMedia(
                "https://parse.example/?url=https://cdn.example/v/one.m3u8"));
    }

    @Test
    public void toleratesHtmlParserResponseForSniffFallback() {
        PlaybackInfo info = PlaybackInfoParser.parse("<html><video></video></html>", "");
        assertFalse(info.direct);
    }
}
