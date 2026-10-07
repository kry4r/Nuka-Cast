package com.nukacast.app.tvbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.nukacast.app.tvbox.model.SearchQuery;
import com.nukacast.app.tvbox.model.TvBoxConfig;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * A 139-site config must not be searched in full on a 1 GB TV: every plugin site costs a JS runtime
 * or a dex-loaded spider in native memory, which is what made the platform kill the app.
 */
public class SearchFanOutTest {
    private static TvBoxConfig.Site site(String key, int type) {
        TvBoxConfig.Site site = new TvBoxConfig.Site();
        site.key = key;
        site.name = key;
        site.type = type;
        site.sourceId = "src";
        site.searchable = 1;
        return site;
    }

    private static List<TvBoxConfig.Site> mixed(int cms, int plugins) {
        List<TvBoxConfig.Site> sites = new ArrayList<TvBoxConfig.Site>();
        for (int i = 0; i < cms; i++) sites.add(site("cms" + i, 1));
        for (int i = 0; i < plugins; i++) sites.add(site("plugin" + i, 3));
        return sites;
    }

    @Test public void smallConfigsAreSearchedInFull() {
        List<TvBoxConfig.Site> sites = mixed(10, 5);
        assertSame(sites, SearchEngine.limitFanOut(sites, new SearchQuery()));
    }

    @Test public void largeConfigsAreCapped() {
        List<TvBoxConfig.Site> limited = SearchEngine.limitFanOut(mixed(100, 39), new SearchQuery());
        assertEquals(SearchEngine.MAX_SEARCH_SITES, limited.size());
    }

    @Test public void cheapSitesComeFirstAndPluginsUseTheLeftoverSlots() {
        List<TvBoxConfig.Site> limited = SearchEngine.limitFanOut(mixed(30, 50), new SearchQuery());
        int cms = 0;
        int plugins = 0;
        for (TvBoxConfig.Site site : limited) {
            if (site.type == 3) plugins++;
            else cms++;
        }
        // 30 cheap sites plus whatever the cap leaves over, never more than the cap in total.
        assertEquals(Math.min(30, SearchEngine.MAX_SEARCH_SITES), cms);
        assertEquals(SearchEngine.MAX_SEARCH_SITES - cms, plugins);
    }

    @Test public void anAllPluginConfigGetsTheWholeBudgetButStaysCapped() {
        List<TvBoxConfig.Site> limited = SearchEngine.limitFanOut(mixed(0, 60), new SearchQuery());
        assertEquals(SearchEngine.MAX_SEARCH_SITES, limited.size());
        for (TvBoxConfig.Site site : limited) assertEquals(3, site.type);
    }

    @Test public void anExplicitSiteSelectionIsNeverTrimmed() {
        List<TvBoxConfig.Site> sites = mixed(100, 39);
        SearchQuery query = new SearchQuery();
        query.siteKeys.add("plugin0");
        assertSame(sites, SearchEngine.limitFanOut(sites, query));
    }

    @Test public void selectionSkipsTypesThatCannotSearch() {
        List<TvBoxConfig.Site> available = new ArrayList<TvBoxConfig.Site>();
        available.add(site("ok", 1));
        available.add(site("no-type", 2));
        available.add(site("also-ok", 3));
        List<TvBoxConfig.Site> selected = SearchEngine.selectSites(available, new SearchQuery());
        assertEquals(2, selected.size());
        assertTrue(selected.get(0).key.equals("ok"));
        assertFalse(selected.get(0).key.equals("no-type"));
    }
}
