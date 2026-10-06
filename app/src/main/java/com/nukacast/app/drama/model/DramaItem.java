package com.nukacast.app.drama.model;

import com.nukacast.app.tvbox.model.SearchItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Provider-neutral short-drama catalog entry.
 *
 * <p>The drama id is always a string: the reference catalog uses 19-digit ids that must not be
 * converted through JS numbers, Java {@code long} or floating point.
 */
public final class DramaItem {
    public String providerId = "";
    public String dramaId = "";
    public String title = "";
    public String cover = "";
    public String intro = "";
    public String remark = "";
    public String category = "";
    public final List<String> tags = new ArrayList<String>();
    public int episodeCount;
    public String heat = "";
    public String status = "";
    public String contentKind = "short_drama";

    public String dedupeKey() {
        String normalized = title == null ? ""
                : title.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        return normalized + "|" + (episodeCount > 0 ? String.valueOf(episodeCount) : "");
    }

    /**
     * Renders the catalog entry through the generic content model so TV and web grids can reuse
     * the existing card, detail and favorite plumbing. {@code sourceId} carries the
     * {@code drama:} scheme and is routed back to the drama service, never to a TVBox site.
     */
    public SearchItem toSearchItem() {
        SearchItem item = new SearchItem();
        item.sourceId = "drama:" + providerId;
        item.siteKey = providerId;
        item.siteName = "短剧目录";
        item.vodId = dramaId;
        item.name = title;
        item.poster = cover;
        item.remarks = remark;
        item.typeName = category;
        item.plot = intro;
        item.area = status;
        return item;
    }

    public boolean isPlayableCatalogEntry() {
        return dramaId != null && !dramaId.isEmpty();
    }
}
