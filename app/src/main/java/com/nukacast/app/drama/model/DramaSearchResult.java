package com.nukacast.app.drama.model;

import java.util.ArrayList;
import java.util.List;

public final class DramaSearchResult {
    public String providerId = "";
    public String providerName = "";
    public String keyword = "";
    public boolean ok = true;
    /** Human-readable failure for {@code ok=false}; empty when the search succeeded. */
    public String error = "";
    public String errorCode = "";
    public String rootCauseClass = "";
    /** {@code total} reported by the catalog, or -1 when the provider does not report one. */
    public int total = -1;
    /** Catalog warning preserved verbatim; never replaced by a local guess. */
    public String warning = "";
    public long elapsedMs;
    public boolean partial;
    public final List<DramaItem> items = new ArrayList<DramaItem>();

    public static DramaSearchResult failure(String providerId, String keyword, String errorCode,
                                            String rootCauseClass, String error) {
        DramaSearchResult result = new DramaSearchResult();
        result.providerId = providerId;
        result.keyword = keyword;
        result.ok = false;
        result.errorCode = errorCode;
        result.rootCauseClass = rootCauseClass;
        result.error = error;
        return result;
    }
}
