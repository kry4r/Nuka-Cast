package com.nukacast.app.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class AppStateContentVersionTest {
    @Test
    public void contentVersionChangesOnlyWhenSourceContentChanges() {
        AppState state = new AppState();

        state.updateService(AppState.ServiceState.READY, "ready");
        state.updateActiveMedia("movie");
        assertEquals(0L, state.getContentVersion());

        state.updateSources(2, 5);
        assertEquals(1L, state.getContentVersion());

        state.updateSourceHealth(2, 5);
        assertEquals(1L, state.getContentVersion());
    }
}
