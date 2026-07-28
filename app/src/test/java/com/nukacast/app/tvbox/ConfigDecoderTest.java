package com.nukacast.app.tvbox;

import com.google.gson.Gson;
import com.nukacast.app.tvbox.model.TvBoxConfig;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void decodesSingleConfigAfterBomAndLineComments() {
        String content = "\ufeff// shared for testing\n// second line\n"
                + "{\"spider\":\"http://example.com/spider.jar;md5;"
                + "0123456789abcdef0123456789abcdef\","
                + "\"sites\":[{\"key\":\"demo\",\"name\":\"Demo\",\"type\":3}]}";

        ConfigDecoder.Document document = decoder.decodeDocument(content);

        assertFalse(document.isWarehouse());
        assertEquals(1, document.config.sites.size());
        assertEquals("demo", document.config.sites.get(0).key);
    }

    @Test
    public void decodesWarehouseUrlsWithoutTreatingItAsEmptyConfig() {
        String content = "\ufeff{\"urls\":["
                + "{\"name\":\"Fast\",\"url\":\"http://example.com/a\"},"
                + "{\"name\":\"Backup\",\"url\":\"./b.json\"}]}";

        ConfigDecoder.Document document = decoder.decodeDocument(content);

        assertTrue(document.isWarehouse());
        assertEquals(2, document.warehouses.size());
        assertEquals("Fast", document.warehouses.get(0).name);
        assertEquals("./b.json", document.warehouses.get(1).url);
    }

    @Test
    public void decodesObjectExtensionUsedByRealXhztvSites() {
        String content = "{\"sites\":[{\"key\":\"xbpq\",\"name\":\"XBPQ\","
                + "\"type\":3,\"searchable\":1,\"ext\":{\"searchUrl\":\"/s/{wd}\","
                + "\"headers\":{\"User-Agent\":\"TVBox\"}}}]}";

        ConfigDecoder.Document document = decoder.decodeDocument(content);

        assertEquals(1, document.config.sites.size());
        assertEquals("/s/{wd}", document.config.sites.get(0).ext.getAsJsonObject()
                .get("searchUrl").getAsString());
    }

    @Test(expected = IllegalArgumentException.class)
    public void decodeRejectsWarehouseWhenSingleConfigIsRequired() {
        decoder.decode("{\"urls\":[{\"name\":\"One\",\"url\":\"https://example.com/tv\"}]}");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptyJsonObject() {
        decoder.decodeDocument("{}");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownEnvelope() {
        decoder.decode("not-a-tvbox-config");
    }
}
