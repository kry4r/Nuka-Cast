package com.nukacast.app.drama;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.nukacast.app.drama.model.DramaDetail;
import com.nukacast.app.drama.model.DramaItem;
import com.nukacast.app.drama.model.DramaSearchResult;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Contract tests for the observed vote-style catalog shape. Fixtures are synthetic but keep the
 * 19-digit ids, string {@code heat}, tags array and {@code ok/total/warning} fields that the real
 * endpoints return.
 */
public class DramaCatalogParserTest {
    private static final String SEARCH_JSON = "{"
            + "\"items\":["
            + "{\"id\":\"7690192663693233177\",\"title\":\"重生2000：靠山吃山成首富\","
            + "\"cover\":\"https://example.test/cover.jpg\",\"intro\":\"重回2000年\","
            + "\"remark\":\"全115集\",\"episodeCount\":115,\"category\":\"脑洞\","
            + "\"tags\":[\"脑洞\",\"重生\",\"逆袭\"],\"heat\":43179826,\"status\":\"finished\"},"
            + "{\"id\":\"7681139703512321086\",\"title\":\"重生七零小辣媳第二季\","
            + "\"episodeCount\":197,\"tags\":[],\"heat\":\"47032287\"}"
            + "],\"total\":213,\"warning\":\"仅展示前 20 条\"}";

    @Test public void keepsNineteenDigitIdAsString() throws Exception {
        DramaSearchResult result = DramaCatalogParser.parseSearch(SEARCH_JSON, "p1", "重生");
        assertEquals(2, result.items.size());
        assertEquals("7690192663693233177", result.items.get(0).dramaId);
        assertEquals("7681139703512321086", result.items.get(1).dramaId);
        assertEquals("p1", result.items.get(0).providerId);
    }

    @Test public void mapsCatalogFields() throws Exception {
        DramaSearchResult result = DramaCatalogParser.parseSearch(SEARCH_JSON, "p1", "重生");
        DramaItem item = result.items.get(0);
        assertEquals("重生2000：靠山吃山成首富", item.title);
        assertEquals("https://example.test/cover.jpg", item.cover);
        assertEquals("重回2000年", item.intro);
        assertEquals("全115集", item.remark);
        assertEquals(115, item.episodeCount);
        assertEquals("脑洞", item.category);
        assertEquals(3, item.tags.size());
        assertEquals("43179826", item.heat);
        assertEquals("finished", item.status);
        assertEquals(213, result.total);
        assertEquals("仅展示前 20 条", result.warning);
        assertTrue(result.ok);
    }

    @Test public void rejectsOkFalseWithProviderMessage() {
        try {
            DramaCatalogParser.parseSearch("{\"ok\":false,\"error\":\"请输入搜索词\"}", "p1", "");
            fail("expected DramaException");
        } catch (DramaException error) {
            assertEquals("catalog_error", error.code);
            assertEquals("请输入搜索词", error.getMessage());
        }
    }

    @Test public void rejectsResponseWithoutItems() {
        try {
            DramaCatalogParser.parseSearch("{\"total\":0}", "p1", "重生");
            fail("expected DramaException");
        } catch (DramaException error) {
            assertEquals("parse_error", error.code);
        }
    }

    @Test public void rejectsNonJson() {
        try {
            DramaCatalogParser.parseSearch("<html>bad gateway</html>", "p1", "重生");
            fail("expected DramaException");
        } catch (DramaException error) {
            assertEquals("parse_error", error.code);
        }
    }

    @Test public void parsesDetailEnvelope() throws Exception {
        DramaDetail detail = DramaCatalogParser.parseDetail(
                "{\"ok\":true,\"item\":{\"id\":\"7690192663693233177\","
                        + "\"title\":\"重生2000：靠山吃山成首富\",\"episodeCount\":115}}",
                "p1", "7690192663693233177");
        assertEquals("7690192663693233177", detail.item.dramaId);
        assertEquals(115, detail.item.episodeCount);
        assertTrue(detail.related.isEmpty());
    }

    @Test public void parseDetailNeverFabricatesEpisodes() throws Exception {
        DramaDetail detail = DramaCatalogParser.parseDetail(
                "{\"ok\":true,\"item\":{\"id\":\"1\",\"title\":\"剧\",\"episodeCount\":80}}",
                "p1", "1");
        // The catalog contract has no episode list; the model exposes only metadata.
        assertEquals(80, detail.item.episodeCount);
        assertNotNull(detail.item.episodeCount);
    }

    @Test public void parsesRelatedItems() throws Exception {
        List<DramaItem> related = new ArrayList<DramaItem>();
        int count = DramaCatalogParser.parseRelated(
                "{\"items\":[{\"id\":\"2\",\"title\":\"重生七零\"}]}", "p1", related);
        assertEquals(1, count);
        assertEquals("2", related.get(0).dramaId);
    }

    @Test public void capsHugeItemLists() throws Exception {
        StringBuilder json = new StringBuilder("{\"items\":[");
        for (int i = 0; i < DramaCatalogParser.MAX_ITEMS + 12; i++) {
            if (i > 0) json.append(',');
            json.append("{\"id\":\"").append(i).append("\",\"title\":\"t").append(i).append("\"}");
        }
        json.append("]}");
        DramaSearchResult result = DramaCatalogParser.parseSearch(json.toString(), "p1", "t");
        assertEquals(DramaCatalogParser.MAX_ITEMS, result.items.size());
        assertTrue(result.partial);
    }

    @Test public void itemWithoutIdOrTitleIsSkipped() throws Exception {
        DramaSearchResult result = DramaCatalogParser.parseSearch(
                "{\"items\":[{\"episodeCount\":5},{\"id\":\"9\",\"title\":\"有效\"}]}",
                "p1", "x");
        assertEquals(1, result.items.size());
        assertEquals("9", result.items.get(0).dramaId);
        assertFalse(result.items.get(0).title.isEmpty());
    }
}
