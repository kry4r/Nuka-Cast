package com.nukacast.app.tvbox;

import com.nukacast.app.tvbox.model.SearchItem;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class SearchResultMergerTest {
    @Test
    public void deduplicatesEquivalentChineseTitlesAndKeepsSourceOrder() {
        SearchItem first = item("庆余年 第二季", "2024", "site-a");
        SearchItem duplicate = item("庆余年·第二季", "2024", "site-b");
        SearchItem other = item("庆余年", "2019", "site-b");

        List<SearchItem> result = SearchResultMerger.merge(
                Arrays.asList(Arrays.asList(first, duplicate), Collections.singletonList(other)), 60);

        assertEquals(2, result.size());
        assertEquals("site-a", result.get(0).siteKey);
        assertEquals("2019", result.get(1).year);
    }

    @Test
    public void appliesPageSizeAfterDeduplication() {
        List<SearchItem> result = SearchResultMerger.merge(
                Collections.singletonList(Arrays.asList(
                        item("A", "2024", "one"),
                        item("B", "2024", "one"),
                        item("C", "2024", "one"))), 2);

        assertEquals(2, result.size());
        assertEquals("B", result.get(1).name);
    }

    private static SearchItem item(String name, String year, String siteKey) {
        SearchItem item = new SearchItem();
        item.name = name;
        item.year = year;
        item.siteKey = siteKey;
        return item;
    }
}
