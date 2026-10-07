package com.nukacast.app.drama.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** A resolved, directly playable episode: what the player needs and what the UI shows. */
public final class DramaPlayResult {
    public String providerId = "";
    public String providerName = "";
    public String dramaId = "";
    public String title = "";
    public String episodeName = "";
    public int index;
    public String url = "";
    public final Map<String, String> headers = new LinkedHashMap<String, String>();

    public boolean isPlayable() {
        return com.nukacast.app.drama.DramaPlaybackUrls.isDirectMediaUrl(url);
    }
}
