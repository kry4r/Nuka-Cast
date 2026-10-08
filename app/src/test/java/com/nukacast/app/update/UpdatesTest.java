package com.nukacast.app.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The release feed is the only thing that can tell a sideloaded television app that a newer build exists,
 * so getting "newer" wrong is the difference between a useful hint and noise.
 */
public class UpdatesTest {

    private static final String FEED = "{\n"
            + "  \"tag_name\": \"v0.5.1\",\n"
            + "  \"html_url\": \"https://github.com/kry4r/Nuka-Cast/releases/tag/v0.5.1\",\n"
            + "  \"body\": \"- 修好了字幕\\n- 更快的首页\",\n"
            + "  \"assets\": [\n"
            + "    {\"name\": \"NukaCast-v0.5.1.apk.sha256\", \"browser_download_url\": \"https://x/sha256\"},\n"
            + "    {\"name\": \"NukaCast-v0.5.1.apk\", \"browser_download_url\": \"https://x/apk\"}\n"
            + "  ]\n"
            + "}";

    @Test
    public void aHigherVersionIsNewer() {
        assertTrue(Updates.compare("0.5.0", "0.5.1") < 0);
        assertTrue(Updates.compare("0.5.0", "0.6.0") < 0);
        assertTrue(Updates.compare("0.5.0", "1.0.0") < 0);
        // The mistake a string comparison makes: 0.10.0 is newer than 0.9.0.
        assertTrue(Updates.compare("0.9.0", "0.10.0") < 0);
        assertTrue(Updates.compare("v0.5.0", "0.5.1") < 0);
    }

    @Test
    public void theSameOrLowerVersionIsNotNewer() {
        assertEquals(0, Updates.compare("0.5.0", "0.5.0"));
        assertTrue(Updates.compare("0.5.0", "0.4.9") > 0);
        // A debug build's suffix must not make it look newer than the release it came from.
        assertEquals(0, Updates.compare("0.5.0-debug", "0.5.0"));
    }

    @Test
    public void theFeedIsReadIncludingTheApkAsset() {
        Updates.Release release = Updates.parse(FEED);
        assertEquals("v0.5.1", release.tag);
        assertEquals("0.5.1", release.version);
        assertTrue(release.pageUrl.endsWith("/v0.5.1"));
        assertTrue(release.notes.contains("字幕"));
        // The checksum file comes first in the feed; the download link must be the apk.
        assertEquals("https://x/apk", release.apkUrl);
    }

    @Test
    public void aReleaseWithoutAssetsStillReportsItsVersion() {
        Updates.Release release = Updates.parse("{\"tag_name\":\"v0.6.0\"}");
        assertEquals("0.6.0", release.version);
        assertEquals("", release.apkUrl);
        assertEquals(Updates.RELEASES_PAGE, release.pageUrl);
    }

    @Test
    public void theVerdictSaysNewerOrNot() {
        Updates.Release release = Updates.parse(FEED);
        Updates.Result older = Updates.evaluate("0.5.0", release);
        assertTrue(older.updateAvailable);
        assertEquals("0.5.1", older.latestVersion);
        assertTrue(older.summary.contains("发现新版本 0.5.1"));

        Updates.Result current = Updates.evaluate("0.5.1", release);
        assertFalse(current.updateAvailable);
        assertTrue(current.summary.contains("已是最新版本"));

        Updates.Result broken = Updates.evaluate("0.5.0", null);
        assertFalse(broken.updateAvailable);
        assertTrue(broken.summary.contains("失败"));
    }

    @Test
    public void aFailedCheckIsRetriedSoonerThanAGoodOne() {
        assertTrue(UpdateChecker.timeToLiveSeconds(false) < UpdateChecker.timeToLiveSeconds(true));
        assertTrue(UpdateChecker.timeToLiveSeconds(true) >= 3600);
    }
}
