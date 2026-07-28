package com.nukacast.app.tvbox;

import com.nukacast.app.tvbox.model.ConfigSource;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class WarehouseRankingTest {
    @Test
    public void ranksHealthyLeafSourcesByLatencyAndLeavesParentsOut() {
        ConfigSource warehouse = source("parent", 1, "");
        warehouse.kind = ConfigSource.KIND_WAREHOUSE;
        ConfigSource fast = source("fast", 120, "");
        fast.siteCount = 3;
        ConfigSource slow = source("slow", 900, "");
        slow.siteCount = 4;
        ConfigSource failed = source("failed", 10, "TLS handshake failed");
        failed.siteCount = 5;
        ConfigSource empty = source("empty", 20, "");
        empty.searchableSiteCount = 0;

        List<ConfigSource> ranked = WarehouseRanking.rankLeaves(
                Arrays.asList(failed, warehouse, slow, empty, fast));

        assertEquals(3, ranked.size());
        assertEquals("fast", ranked.get(0).id);
        assertEquals("slow", ranked.get(1).id);
        assertEquals("failed", ranked.get(2).id);
    }

    @Test
    public void excludesLiveOnlyWarehousesFromSearchRanking() {
        ConfigSource searchable = source("searchable", 100, "");
        searchable.searchableSiteCount = 2;
        ConfigSource liveOnly = source("live", 5, "");
        liveOnly.searchableSiteCount = 0;
        liveOnly.liveCount = 3;

        List<ConfigSource> ranked = WarehouseRanking.rankLeaves(
                Arrays.asList(liveOnly, searchable));

        assertEquals(1, ranked.size());
        assertEquals("searchable", ranked.get(0).id);
    }

    @Test
    public void ranksACompletelyFailedSearchAsUnhealthyWithoutRewardingFastFailure() {
        ConfigSource working = source("working", 800, "");
        working.searchableSiteCount = 2;
        ConfigSource fastFailure = source("fast-failure", 10, "");
        fastFailure.searchableSiteCount = 2;
        fastFailure.searchError = "最近搜索全部失败";

        List<ConfigSource> ranked = WarehouseRanking.rankLeaves(
                Arrays.asList(fastFailure, working));

        assertEquals("working", ranked.get(0).id);
        assertEquals("fast-failure", ranked.get(1).id);
    }

    private static ConfigSource source(String id, long latencyMs, String error) {
        ConfigSource source = new ConfigSource(id, "https://example.com/" + id);
        source.id = id;
        source.latencyMs = latencyMs;
        source.error = error;
        source.searchableSiteCount = 1;
        return source;
    }
}
