package com.nukacast.app.drama.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One playable episode of a short drama. Providers either hand out a ready {@code playUrl}
 * (CMS / web scraping) or a {@code pageUrl} that has to be resolved lazily per episode.
 */
public final class DramaEpisode {
    public int index = 1;
    public String name = "";
    public String playUrl = "";
    public String pageUrl = "";
    public final Map<String, String> headers = new LinkedHashMap<String, String>();
    /** True when {@link #playUrl} is already a directly playable media URL. */
    public boolean direct;

    public boolean isPlayable() {
        return direct && com.nukacast.app.drama.DramaPlaybackUrls.isDirectMediaUrl(playUrl);
    }
}
