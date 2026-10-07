package com.nukacast.app.net;

import org.junit.Test;

import java.nio.charset.Charset;

import okhttp3.MediaType;
import okhttp3.ResponseBody;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Chinese IPTV playlists are often GBK. Decoding them as UTF-8 produced mojibake channel names on
 * the TV, which is what this guards against.
 */
public class ResponseBodiesCharsetTest {
    @Test
    public void decodesGbkPlaylistWhenHeadersSayNothing() throws Exception {
        String text = "#EXTM3U\n#EXTINF:-1,CCTV-1 综合\nhttp://example.test/cctv1.m3u8\n";
        ResponseBody body = ResponseBody.create(
                MediaType.parse("application/octet-stream"),
                text.getBytes(Charset.forName("GBK")));
        assertEquals(text, ResponseBodies.text(body, 64 * 1024));
    }

    @Test
    public void prefersUtf8WhenTheBytesAreValid() throws Exception {
        String text = "#EXTM3U\n#EXTINF:-1,凤凰中文\nhttp://example.test/fh.m3u8\n";
        ResponseBody body = ResponseBody.create(
                MediaType.parse("application/octet-stream"), text.getBytes("UTF-8"));
        // "凤凰" is not valid GBK, but if the bytes were valid GBK the strict UTF-8 check would fail
        // and the GBK fallback would kick in - which is exactly the behaviour under test.
        assertEquals(text, ResponseBodies.text(body, 64 * 1024));
    }

    @Test
    public void honoursTheDeclaredCharset() throws Exception {
        String text = "#EXTM3U\n#EXTINF:-1,湖北卫视\nhttp://example.test/hb.m3u8\n";
        ResponseBody body = ResponseBody.create(
                MediaType.parse("application/x-mpegurl; charset=gb2312"),
                text.getBytes(Charset.forName("GBK")));
        assertEquals(text, ResponseBodies.text(body, 64 * 1024));
    }
}
