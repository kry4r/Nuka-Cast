package com.nukacast.app.library;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class LibraryItemsTest {
    @Test
    public void replacesSameTitleAndKeepsNewestFirst() {
        LibraryItem old = item("source", "site", "42", 10);
        LibraryItem other = item("source", "site", "7", 20);
        LibraryItem updated = item("source", "site", "42", 30);
        updated.episodeName = "第 2 集";

        List<LibraryItem> result = LibraryItems.upsert(Arrays.asList(old, other), updated, 10);

        assertEquals(2, result.size());
        assertEquals("42", result.get(0).vodId);
        assertEquals("第 2 集", result.get(0).episodeName);
        assertEquals("7", result.get(1).vodId);
    }

    @Test
    public void usesSourceSiteAndVodForStableIdentity() {
        LibraryItem first = item("a", "site", "42", 1);
        LibraryItem second = item("b", "site", "42", 2);

        List<LibraryItem> result = LibraryItems.upsert(Arrays.asList(first), second, 10);

        assertEquals(2, result.size());
    }

    private static LibraryItem item(String source, String site, String vod, long updatedAt) {
        LibraryItem item = new LibraryItem();
        item.sourceId = source;
        item.siteKey = site;
        item.vodId = vod;
        item.updatedAt = updatedAt;
        return item;
    }
}
