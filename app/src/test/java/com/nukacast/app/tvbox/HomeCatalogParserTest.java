package com.nukacast.app.tvbox;

import com.nukacast.app.tvbox.model.SearchItem;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public final class HomeCatalogParserTest {
    @Test
    public void parsesSpiderHomeListAndAddsSiteIdentity() {
        String json = "{\"class\":[{\"type_id\":\"1\",\"type_name\":\"电影\"}]," +
                "\"list\":[{\"vod_id\":\"42\",\"vod_name\":\"沙丘\"," +
                "\"vod_pic\":\"https://img/42.jpg\",\"vod_remarks\":\"正片\"}]}";

        List<SearchItem> items = HomeCatalogParser.parse(json, "config", "site", "测试站");

        assertEquals(1, items.size());
        assertEquals("42", items.get(0).vodId);
        assertEquals("沙丘", items.get(0).name);
        assertEquals("config", items.get(0).sourceId);
        assertEquals("测试站", items.get(0).siteName);
    }

    @Test
    public void acceptsNestedCmsDataAndSkipsUnplayableRows() {
        String json = "{\"data\":{\"list\":[" +
                "{\"id\":\"a\",\"title\":\"可播放\"}," +
                "{\"title\":\"缺少ID\"}]}}";

        List<SearchItem> items = HomeCatalogParser.parse(json, "source", "cms", "CMS");

        assertEquals(1, items.size());
        assertEquals("可播放", items.get(0).name);
    }
}
