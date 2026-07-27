package com.nukacast.app.tvbox.search;

import com.nukacast.app.tvbox.model.SearchItem;
import com.nukacast.app.tvbox.model.SearchQuery;
import com.nukacast.app.tvbox.model.TvBoxConfig;

import java.util.List;

public interface SiteSearcher {
    List<SearchItem> search(TvBoxConfig.Site site, SearchQuery query) throws Exception;
}
