package com.nukacast.app.tvbox.model;

import java.util.ArrayList;
import java.util.List;

public final class SearchQuery {
    public String keyword = "";
    public String sourceId = "";
    public String contentType = "";
    public String year = "";
    public String region = "";
    public List<String> siteKeys = new ArrayList<String>();
    /**
     * Ignores recorded site-health verdicts. Used by the sweep that produces those verdicts, which
     * must be able to test a site the store currently believes is broken.
     */
    public boolean forceSites;
    public int page = 1;
    public int pageSize = 60;
}
