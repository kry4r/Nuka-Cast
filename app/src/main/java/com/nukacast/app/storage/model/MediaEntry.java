package com.nukacast.app.storage.model;

import com.nukacast.app.tvbox.model.SearchItem;

public final class MediaEntry {
    public String id = "";
    public String mountId = "";
    public String mountName = "";
    public String title = "";
    public String fileName = "";
    public String uri = "";
    public String poster = "";
    public String typeName = "电影";
    public String year = "";
    public int season;
    public int episode;
    public long size;
    public long modifiedAt;

    public SearchItem toSearchItem() {
        SearchItem item = new SearchItem();
        item.sourceId = "storage:" + safe(mountId);
        item.siteKey = "storage";
        item.siteName = safe(mountName);
        item.vodId = safe(id);
        item.name = safe(title);
        item.poster = safe(poster);
        item.remarks = episode > 0 ? "第 " + episode + " 集" : "片库";
        item.year = safe(year);
        item.typeName = safe(typeName);
        return item;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
