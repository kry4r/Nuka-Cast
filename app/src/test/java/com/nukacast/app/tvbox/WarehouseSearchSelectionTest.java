package com.nukacast.app.tvbox;

import com.nukacast.app.tvbox.model.SearchQuery;
import com.nukacast.app.tvbox.model.TvBoxConfig;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class WarehouseSearchSelectionTest {
    @Test
    public void filtersBySourceIdWhenDifferentWarehousesReuseSiteKeys() {
        TvBoxConfig.Site first = site("warehouse-a", "shared");
        TvBoxConfig.Site second = site("warehouse-b", "shared");
        SearchQuery query = new SearchQuery();
        query.sourceId = "warehouse-b";

        List<TvBoxConfig.Site> selected = SearchEngine.selectSites(
                Arrays.asList(first, second), query);

        assertEquals(1, selected.size());
        assertEquals("warehouse-b", selected.get(0).sourceId);
    }

    private static TvBoxConfig.Site site(String sourceId, String key) {
        TvBoxConfig.Site site = new TvBoxConfig.Site();
        site.sourceId = sourceId;
        site.key = key;
        site.name = sourceId;
        site.type = 3;
        site.searchable = 1;
        return site;
    }
}
