package com.nukacast.app.tvbox;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class RefreshProgressTest {
    @Test
    public void notifiesContentProgressBeforeTheWholeBatchCompletes() {
        final int[] progress = {0};
        TvBoxRepository.RefreshListener listener = new TvBoxRepository.RefreshListener() {
            @Override public void onSourceRefreshed(int configs, int sites) { progress[0]++; }
            @Override public void onRefreshComplete(int configs, int sites) {}
        };

        TvBoxRepository.notifySourceRefreshed(listener, 3, 8);

        assertEquals(1, progress[0]);
    }
}
