package com.nukacast.app.drama.model;

/**
 * A playback candidate found by cross-searching the user's enabled TVBox sites for a drama title.
 * A line is only a suggestion: the caller must let the user confirm which provider to use, and
 * must never silently auto-play a different drama that merely shares a similar title.
 */
public final class DramaLine {
    public String sourceId = "";
    public String sourceName = "";
    public String siteKey = "";
    public String siteName = "";
    public String vodId = "";
    public String name = "";
    public String remarks = "";
    public String poster = "";
    public String year = "";
    public String typeName = "";
    /** 100 = normalized exact match, 80 = one title contains the other, 0 = not a candidate. */
    public int matchScore;
    /** {@code exact}, {@code prefix} or {@code contains}. */
    public String matchKind = "";
    public String episodeHint = "";

    public boolean isExact() {
        return "exact".equals(matchKind);
    }
}
