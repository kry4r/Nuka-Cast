package com.nukacast.app.drama.model;

import java.util.ArrayList;
import java.util.List;

public final class DramaDetail {
    public DramaItem item = new DramaItem();
    public final List<DramaItem> related = new ArrayList<DramaItem>();
    /** Number of related entries returned by the catalog, before any local truncation. */
    public int relatedTotal = -1;
    public boolean relatedPartial;
    /** Episodes with playable URLs; empty for metadata-only catalogs. */
    public final List<DramaEpisode> episodes = new ArrayList<DramaEpisode>();
    /** True when this catalog can play directly and needs no TVBox line matching. */
    public boolean directPlayable;
    /** Provider hint shown when nothing could be resolved (never a fabricated success). */
    public String note = "";
}
