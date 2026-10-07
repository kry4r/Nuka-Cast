package com.nukacast.app.sources;

/** One curated source from {@code assets/sources/recommended.json} plus its runtime state. */
public final class RecommendedSource {
    public static final String KIND_LIVE = "live";
    public static final String KIND_VOD = "vod";
    public static final String KIND_DRAMA = "drama";

    public String id = "";
    public String kind = KIND_VOD;
    public String group = "";
    public String name = "";
    public String url = "";
    public String note = "";
    /** Short-drama class id for CMS drama sources; empty for other kinds. */
    public String categoryId = "";
    public String verifiedAt = "";

    // Runtime state, never persisted.
    public boolean added;
    public Probe probe;

    public String kindLabel() {
        if (KIND_LIVE.equals(kind)) return "直播";
        if (KIND_DRAMA.equals(kind)) return "短剧";
        return "点播";
    }

    /** Result of one reachability probe, kept deliberately explicit about what was verified. */
    public static final class Probe {
        public String id = "";
        public boolean ok;
        public int httpStatus;
        public long latencyMs;
        public long bytes;
        /** Human-readable summary of what the response contained. */
        public String detail = "";
        public String errorCode = "";
        public String error = "";
        public long checkedAt;
    }
}
