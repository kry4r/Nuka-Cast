package com.nukacast.app.net;

import org.junit.Test;

import java.nio.charset.Charset;

import static org.junit.Assert.assertEquals;

public final class ResponseTextDecoderTest {
    @Test
    public void decodesStrictUtf8ChineseWithoutCharset() throws Exception {
        String value = "摸鱼接口";

        assertEquals(value, ResponseTextDecoder.decode(
                value.getBytes("UTF-8"), "application/json"));
    }

    @Test
    public void stripsUtf8Bom() throws Exception {
        byte[] text = "中文源".getBytes("UTF-8");
        byte[] bytes = new byte[text.length + 3];
        bytes[0] = (byte) 0xef;
        bytes[1] = (byte) 0xbb;
        bytes[2] = (byte) 0xbf;
        System.arraycopy(text, 0, bytes, 3, text.length);

        assertEquals("中文源", ResponseTextDecoder.decode(bytes, "text/plain"));
    }

    @Test
    public void honorsDeclaredGbkCharset() throws Exception {
        String value = "中文片源";

        assertEquals(value, ResponseTextDecoder.decode(value.getBytes("GBK"),
                "application/json; charset=GBK"));
    }

    @Test
    public void fallsBackToGb18030WhenStrictUtf8IsInvalid() {
        String value = "中文源";

        assertEquals(value, ResponseTextDecoder.decode(
                value.getBytes(Charset.forName("GB18030")), "text/plain"));
    }
}
