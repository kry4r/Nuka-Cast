package com.nukacast.app.tvbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Share pages are how several large CMS back ends publish episodes, and the exact HTML shape matters:
 * the address lives in a plain script assignment, sometimes relative, sometimes escaped.
 */
public class MaccmsShareResolverTest {
    /** Trimmed copy of a real 非凡资源 share page. */
    private static final String SHARE_PAGE =
            "<!DOCTYPE html>\n<html>\n<head>\n<title>某剧-01</title>\n<script>\n"
            + "        const vid = \"6916222172eb021e5d5f0043453078b9\";\n"
            + "        const url = \"/20260918/49391_69162221/index.m3u8?sign=b864478950353ef08a07e31a231d6fa4\";\n"
            + "        const pic = \"/20260918/49391_69162221/1.jpg\";\n"
            + "</script>\n</head>\n<body></body>\n</html>";

    @Test
    public void resolvesRelativePlaylistFromSharePage() {
        assertEquals(
                "https://vip.ffzy-play9.com/20260918/49391_69162221/index.m3u8?sign=b864478950353ef08a07e31a231d6fa4",
                MaccmsShareResolver.extract(SHARE_PAGE, "https://vip.ffzy-play9.com/share/6916222172eb021e5d5f0043453078b9"));
    }

    @Test
    public void readsPlayerAaaaJsonForm() {
        String page = "<script>var player_aaaa={\"flag\":\"play\",\"encrypt\":0,"
                + "\"url\":\"https:\\/\\/cdn.example.com\\/hls\\/1234\\/index.m3u8\"};</script>";
        assertEquals("https://cdn.example.com/hls/1234/index.m3u8",
                MaccmsShareResolver.extract(page, "https://movie.example.com/share/abc"));
    }

    @Test
    public void findsAbsoluteMediaLinkWithoutNamedVariable() {
        String page = "<video src=\"https://cdn.example.net/live/abcd.m3u8?token=1\"></video>";
        assertEquals("https://cdn.example.net/live/abcd.m3u8?token=1",
                MaccmsShareResolver.extract(page, "https://movie.example.com/play/1.html"));
    }

    @Test
    public void protocolRelativeAddressInheritsPageScheme() {
        assertEquals("https://cdn.example.com/a/b/index.m3u8",
                MaccmsShareResolver.absolute("//cdn.example.com/a/b/index.m3u8",
                        "https://movie.example.com/share/x"));
    }

    @Test
    public void ignoresPlayerScriptAndImages() {
        assertNull(MaccmsShareResolver.extract(
                "<script src=\"/frontend/players/js/hls.js\"></script><img src=\"/1.jpg\">",
                "https://movie.example.com/share/abc"));
    }

    @Test
    public void onlyShareLikeUrlsAreFetched() {
        assertTrue(MaccmsShareResolver.looksLikeSharePage(
                "https://vip.ffzy-play9.com/share/6916222172eb021e5d5f0043453078b9"));
        assertTrue(MaccmsShareResolver.looksLikeSharePage("https://x.test/play/1.html"));
        // Media URLs must never cost an extra request.
        assertFalse(MaccmsShareResolver.looksLikeSharePage("https://cdn.test/a/index.m3u8?sign=1"));
        assertFalse(MaccmsShareResolver.looksLikeSharePage("https://cdn.test/a/b.mp4"));
        assertFalse(MaccmsShareResolver.looksLikeSharePage(""));
        assertNull(MaccmsShareResolver.looksLikeSharePage(null) ? "x" : null);
    }

    @Test
    public void mediaUrlPassesThroughUnchanged() {
        assertEquals("https://cdn.test/a/index.m3u8",
                MaccmsShareResolver.resolve("https://cdn.test/a/index.m3u8", null));
    }
}
