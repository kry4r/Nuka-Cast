package com.nukacast.app.tvbox.model;

import java.util.ArrayList;
import java.util.List;

public final class SearchResponse {
    public String keyword;
    public long elapsedMs;
    public int searchedSites;
    public int failedSites;
    public boolean partial;
    public final List<SearchItem> items = new ArrayList<SearchItem>();
    public final List<SiteError> errors = new ArrayList<SiteError>();

    public static final class SiteError {
        public String siteKey;
        public String siteName;
        public String message;

        public SiteError(String siteKey, String siteName, String message) {
            this.siteKey = siteKey;
            this.siteName = siteName;
            this.message = message;
        }
    }
}
