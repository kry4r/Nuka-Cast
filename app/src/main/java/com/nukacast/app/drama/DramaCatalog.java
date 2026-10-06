package com.nukacast.app.drama;

import com.nukacast.app.drama.model.DramaDetail;
import com.nukacast.app.drama.model.DramaProviderConfig;
import com.nukacast.app.drama.model.DramaSearchResult;

public interface DramaCatalog {
    DramaProviderConfig config();

    DramaSearchResult search(String keyword) throws Exception;

    DramaDetail detail(String dramaId) throws Exception;
}
