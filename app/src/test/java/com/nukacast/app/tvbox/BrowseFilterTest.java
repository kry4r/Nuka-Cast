package com.nukacast.app.tvbox;

import com.nukacast.app.tvbox.model.SearchItem;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The browse filter runs on the device because the sites ignore the parameters, so the matching rules
 * are what decides whether a viewer sees any results at all.
 */
public class BrowseFilterTest {
    @Test
    public void anEmptyFilterMatchesEverything() {
        BrowseFilter filter = new BrowseFilter();
        assertTrue(filter.isEmpty());
        assertTrue(filter.matches(item("2024", "大陆", "国语")));
        assertEquals("", filter.label());
    }

    @Test
    public void filtersByYearAreaAndLanguage() {
        BrowseFilter filter = new BrowseFilter("2024", "大陆", "国语");
        assertTrue(filter.matches(item("2024", "中国大陆", "国语")));
        assertFalse(filter.matches(item("2023", "中国大陆", "国语")));
        assertFalse(filter.matches(item("2024", "美国", "国语")));
        assertFalse(filter.matches(item("2024", "中国大陆", "英语")));
        assertEquals("2024 · 大陆 · 国语", filter.label());
    }

    @Test
    public void regionMatchingToleratesWhatSitesActuallyWrite() {
        BrowseFilter mainland = new BrowseFilter("", "大陆", "");
        assertTrue(mainland.matches(item("2024", "大陆", "")));
        assertTrue(mainland.matches(item("2024", "中国大陆", "")));
        assertTrue(mainland.matches(item("2024", "内地", "")));
        assertTrue(mainland.matches(item("2024", "China", "")));
        assertFalse(mainland.matches(item("2024", "香港", "")));
        // Real sites write 中国香港 / 中国台湾: the 中国 prefix must not make them 大陆.
        assertFalse(mainland.matches(item("2024", "中国香港", "")));
        assertFalse(mainland.matches(item("2024", "中国台湾", "")));
        assertFalse(mainland.matches(item("2024", "中国澳门", "")));
        // An entry without a region cannot be claimed by any region filter.
        assertFalse(mainland.matches(item("2024", "", "")));

        BrowseFilter other = new BrowseFilter("", "其它", "");
        assertTrue(other.matches(item("2024", "西班牙", "")));
        assertFalse(other.matches(item("2024", "美国", "")));
    }

    @Test
    public void languageMatchingHandlesSynonyms() {
        BrowseFilter mandarin = new BrowseFilter("", "", "国语");
        assertTrue(mandarin.matches(item("2024", "", "国语")));
        assertTrue(mandarin.matches(item("2024", "", "普通话")));
        assertFalse(mandarin.matches(item("2024", "", "粤语")));
        assertFalse(mandarin.matches(item("2024", "", "")));
    }

    @Test
    public void copiesKeepTheOtherValues() {
        BrowseFilter base = new BrowseFilter("2023", "香港", "粤语");
        assertEquals("2024|香港|粤语", base.withYear("2024").key());
        assertEquals("2023|日本|粤语", base.withArea("日本").key());
        assertEquals("2023|香港|英语", base.withLang("英语").key());
        assertTrue(base.withYear("").isEmpty() == false);
    }

    private static SearchItem item(String year, String area, String lang) {
        SearchItem item = new SearchItem();
        item.name = "影片";
        item.year = year;
        item.area = area;
        item.lang = lang;
        return item;
    }
}
