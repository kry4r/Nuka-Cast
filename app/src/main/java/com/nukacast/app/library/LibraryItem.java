package com.nukacast.app.library;

import com.nukacast.app.tvbox.model.MediaDetail;
import com.nukacast.app.tvbox.model.SearchItem;

public final class LibraryItem {
    public String sourceId = "";
    public String siteKey = "";
    public String siteName = "";
    public String vodId = "";
    public String name = "";
    public String poster = "";
    public String remarks = "";
    public String year = "";
    public String typeName = "";
    public String playSource = "";
    public String episodeId = "";
    public String episodeName = "";
    public int positionMs;
    public int durationMs;
    public long updatedAt;

    public String stableKey() {
        return safe(sourceId) + "|" + safe(siteKey) + "|" + safe(vodId);
    }

    public SearchItem toSearchItem() {
        SearchItem item = new SearchItem();
        item.sourceId = safe(sourceId);
        item.siteKey = safe(siteKey);
        item.siteName = safe(siteName);
        item.vodId = safe(vodId);
        item.name = safe(name);
        item.poster = safe(poster);
        item.remarks = safe(remarks);
        item.year = safe(year);
        item.typeName = safe(typeName);
        return item;
    }

    public static LibraryItem from(SearchItem item) {
        LibraryItem result = new LibraryItem();
        result.sourceId = safe(item.sourceId);
        result.siteKey = safe(item.siteKey);
        result.siteName = safe(item.siteName);
        result.vodId = safe(item.vodId);
        result.name = safe(item.name);
        result.poster = safe(item.poster);
        result.remarks = safe(item.remarks);
        result.year = safe(item.year);
        result.typeName = safe(item.typeName);
        return result;
    }

    public static LibraryItem from(MediaDetail detail) {
        LibraryItem result = new LibraryItem();
        result.sourceId = safe(detail.sourceId);
        result.siteKey = safe(detail.siteKey);
        result.siteName = safe(detail.siteName);
        result.vodId = safe(detail.vodId);
        result.name = safe(detail.name);
        result.poster = safe(detail.poster);
        result.remarks = safe(detail.remarks);
        result.year = safe(detail.year);
        result.typeName = safe(detail.typeName);
        return result;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
