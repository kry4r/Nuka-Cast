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


    @Test
    public void removesByVodIdNameOrStableKey() {
        LibraryItem byId = item("source", "site", "42", 1);
        byId.name = "影片甲";
        LibraryItem byName = item("source", "site", "43", 2);
        byName.name = "影片乙";
        List<LibraryItem> items = Arrays.asList(byId, byName);

        assertEquals(1, LibraryItems.countMatching(items, "42"));
        assertEquals(1, LibraryItems.removeMatching(items, "42").size());
        // The console may only know the title (drama episodes carry no site id).
        assertEquals(1, LibraryItems.removeMatching(items, "影片乙").size());
        assertEquals(1, LibraryItems.countMatching(items, byName.stableKey()));
        // Nothing matches: the list must come back untouched.
        assertEquals(2, LibraryItems.removeMatching(items, "nope").size());
        assertEquals(0, LibraryItems.countMatching(items, ""));
        assertEquals(0, LibraryItems.countMatching(items, null));
        assertEquals(0, LibraryItems.removeMatching(null, "42").size());
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
