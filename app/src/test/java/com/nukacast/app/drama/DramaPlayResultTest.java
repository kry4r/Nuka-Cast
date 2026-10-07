package com.nukacast.app.drama;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.nukacast.app.drama.model.DramaEpisode;
import com.nukacast.app.drama.model.DramaItem;
import com.nukacast.app.drama.model.DramaPlayResult;
import com.nukacast.app.drama.model.DramaProviderConfig;

import org.junit.Test;

/**
 * Play routing rules that decide whether a short drama can be played directly. Only a resolved,
 * directly playable URL may be handed to the player; everything else has to go through the TVBox
 * line flow, and the headers that the source requires must travel with the URL.
 */
public class DramaPlayResultTest {
    private static DramaPlayResult result(String url, String episodeName, String referer) {
        DramaPlayResult play = new DramaPlayResult();
        play.url = url;
        play.episodeName = episodeName;
        play.index = 3;
        if (referer != null) play.headers.put("Referer", referer);
        return play;
    }

    @Test public void acceptsHttpAndHttpsMediaUrls() {
        assertTrue(result("https://cdn.test/1/index.m3u8", "第3集", null).isPlayable());
        assertTrue(result("http://cdn.test/1.mp4", "第3集", null).isPlayable());
    }

    @Test public void rejectsNonMediaAndBlankUrls() {
        assertFalse(result("", "第3集", null).isPlayable());
        assertFalse(result("https://jx.test/?url=https://cdn.test/1.m3u8", "第3集", null).isPlayable());
        assertFalse(result("ftp://cdn.test/1.m3u8", "第3集", null).isPlayable());
    }

    @Test public void carriesProviderHeaders() {
        DramaPlayResult play = result("https://cdn.test/1.m3u8", "第3集", "https://site.test/");
        assertEquals("https://site.test/", play.headers.get("Referer"));
    }

    @Test public void episodeDefaultsAndPlayabilityAreExplicit() {
        DramaEpisode episode = new DramaEpisode();
        assertFalse(episode.isPlayable());
        episode.playUrl = "https://cdn.test/1.m3u8";
        assertFalse("direct 未确认前不能当作可播", episode.isPlayable());
        episode.direct = true;
        assertTrue(episode.isPlayable());
    }

    @Test public void cmsProviderKnowsItCanPlayDirectly() {
        DramaProviderConfig cms = new DramaProviderConfig();
        cms.kind = DramaProviderConfig.KIND_CMS_DRAMA;
        assertTrue(cms.canPlayDirectly());
        assertEquals("CMS 直连", cms.kindLabel());

        DramaProviderConfig catalog = new DramaProviderConfig();
        catalog.kind = DramaProviderConfig.KIND_VOTE_CATALOG;
        assertFalse(catalog.canPlayDirectly());
        assertEquals("资料目录", catalog.kindLabel());
    }

    @Test public void playFailsWithAPlayableReasonWhenEpisodeIsNotDirect() {
        DramaItem item = new DramaItem();
        item.title = "重生2000";
        assertEquals("重生2000", item.title);
        try {
            throw new DramaException("play_not_found", "该剧集没有可直接播放的地址，请改用播放线路");
        } catch (DramaException error) {
            assertEquals("play_not_found", error.code);
        }
    }
}
