package com.nukacast.app.drama.model;

import java.util.ArrayList;
import java.util.List;

public final class DramaLineResult {
    public final List<DramaLine> lines = new ArrayList<DramaLine>();
    public int searchedSites;
    public int failedSites;
    /** False when no enabled, searchable source existed at all. */
    public boolean searched;
    public String error = "";
    public long elapsedMs;

    public boolean hasExactMatch() {
        for (DramaLine line : lines) {
            if (line.isExact()) return true;
        }
        return false;
    }
}
