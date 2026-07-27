package com.nukacast.app.tvbox;

import com.google.gson.Gson;
import com.nukacast.app.tvbox.model.TvBoxConfig;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public final class ConfigDecoderTest {
    private final ConfigDecoder decoder = new ConfigDecoder(new Gson());

    @Test
    public void decodesPlainConfigAndInitializesMissingLists() {
        TvBoxConfig config = decoder.decode("{\"spider\":\"./spider.jar\"}");

        assertEquals("./spider.jar", config.spider);
        assertNotNull(config.sites);
        assertNotNull(config.lives);
        assertNotNull(config.parses);
    }

    @Test
    public void decodesImageBodyBase64Config() {
        String json = "{\"sites\":[{\"key\":\"demo\",\"name\":\"测试源\",\"type\":3}]}";
        String wrapped = "image/png;base64**" + Base64.getEncoder()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));

        TvBoxConfig config = decoder.decode(wrapped);

        assertEquals(1, config.sites.size());
        assertEquals("demo", config.sites.get(0).key);
    }

    @Test
    public void decodesBinaryImagePrefixBase64Config() {
        String json = "{\"sites\":[{\"key\":\"binary\",\"name\":\"图片源\",\"type\":3}]}";
        byte[] jpegPrefix = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00, 0x10};
        String wrapped = new String(jpegPrefix, StandardCharsets.UTF_8) + "**"
                + Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));

        TvBoxConfig config = decoder.decode(wrapped);

        assertEquals(1, config.sites.size());
        assertEquals("binary", config.sites.get(0).key);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownEnvelope() {
        decoder.decode("not-a-tvbox-config");
    }
}
