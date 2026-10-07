package com.nukacast.app.sources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nukacast.app.drama.model.DramaProviderConfig;

import org.junit.Test;

public class RecommendedSourcesTest {
    @Test public void detectsCmsDramaProvidersFromTheirUrl() {
        RecommendedSource item = new RecommendedSource();
        item.kind = RecommendedSource.KIND_DRAMA;
        item.url = "https://api.ffzyapi.com/api.php/provide/vod";
        assertEquals(DramaProviderConfig.KIND_CMS_DRAMA, RecommendedSources.kindOf(item));

        item.url = "https://vote.252035.xyz";
        assertEquals(DramaProviderConfig.KIND_VOTE_CATALOG, RecommendedSources.kindOf(item));
    }

    @Test public void nonDramaKindsKeepTheirKind() {
        RecommendedSource item = new RecommendedSource();
        item.kind = RecommendedSource.KIND_LIVE;
        item.url = "https://example.test/live.m3u";
        assertEquals(RecommendedSource.KIND_LIVE, RecommendedSources.kindOf(item));
    }

    @Test public void countsChannelsWithTheRealPlaylistParser() {
        String m3u = "#EXTM3U\n#EXTINF:-1 group-title=\"央视\",CCTV-1\nhttp://a/1.m3u8\n"
                + "#EXTINF:-1 group-title=\"卫视\",湖南卫视\nhttp://a/2.m3u8\n";
        assertEquals(2, RecommendedSources.channelCount(m3u));
        assertEquals(0, RecommendedSources.channelCount("<html>404</html>"));
        assertEquals(0, RecommendedSources.channelCount(""));
    }

    @Test public void countsTxtPlaylistsToo() {
        String txt = "央视,#genre#\nCCTV-1,http://a/1.m3u8\nCCTV-2,http://a/2.m3u8\n";
        assertTrue(RecommendedSources.channelCount(txt) >= 2);
    }

    @Test public void searchesMetadataCatalogsInsteadOfProbingTheirHomepage() {
        RecommendedSource item = new RecommendedSource();
        item.kind = RecommendedSource.KIND_DRAMA;
        item.url = "https://vote.252035.xyz";
        assertEquals("https://vote.252035.xyz/api/search?q=%E9%87%8D%E7%94%9F",
                RecommendedSources.catalogSearchUrl(item, "重生"));
    }

    @Test public void stripsTrailingSlashesAndQueriesFromCatalogBases() {
        RecommendedSource item = new RecommendedSource();
        item.url = "https://vote.test/?debug=1";
        // '+' is the query-string form of a space and what the catalog endpoint expects.
        assertEquals("https://vote.test/api/search?q=a+b",
                RecommendedSources.catalogSearchUrl(item, "a b"));
    }

    @Test public void labelsKindsForTheUi() {
        RecommendedSource item = new RecommendedSource();
        item.kind = RecommendedSource.KIND_LIVE;
        assertEquals("直播", item.kindLabel());
        item.kind = RecommendedSource.KIND_DRAMA;
        assertEquals("短剧", item.kindLabel());
        item.kind = RecommendedSource.KIND_VOD;
        assertEquals("点播", item.kindLabel());
    }

    @Test public void probeDefaultsToNotVerified() {
        RecommendedSource.Probe probe = new RecommendedSource.Probe();
        assertFalse(probe.ok);
        assertEquals(0, probe.httpStatus);
        assertTrue(probe.error.isEmpty());
    }
}
