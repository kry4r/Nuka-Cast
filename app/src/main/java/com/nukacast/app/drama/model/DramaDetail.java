package com.nukacast.app.drama.model;

import java.util.ArrayList;
import java.util.List;

public final class DramaDetail {
    public DramaItem item = new DramaItem();
    public final List<DramaItem> related = new ArrayList<DramaItem>();
    /** Number of related entries returned by the catalog, before any local truncation. */
    public int relatedTotal = -1;
    public boolean relatedPartial;
}
