package com.nukacast.app.airplay;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AirPlaySessionStateTest {
    @Test
    public void disconnectRejectsOldPacketsAndRestartAllowsSecondConnection() {
        AirPlaySessionState state = new AirPlaySessionState();
        assertEquals(AirPlaySessionState.PACKET_REJECTED, state.recordPacket(100L));

        state.receiverStarted();
        assertEquals(AirPlaySessionState.PACKET_STARTED, state.recordPacket(200L));
        assertEquals(AirPlaySessionState.PACKET_CONTINUING, state.recordPacket(300L));
        assertTrue(state.isActive());

        state.disconnect();
        assertFalse(state.isActive());
        assertEquals(AirPlaySessionState.PACKET_REJECTED, state.recordPacket(400L));

        state.receiverStarted();
        assertEquals(AirPlaySessionState.PACKET_STARTED, state.recordPacket(500L));
        assertTrue(state.isActive());
    }

    @Test
    public void mediaSilenceDoesNotOverrideNativeSessionState() {
        AirPlaySessionState state = new AirPlaySessionState();
        state.receiverStarted();
        assertFalse(state.isIdle(5000L, 2000L));

        assertEquals(AirPlaySessionState.PACKET_STARTED, state.nativeConnected(1000L));
        assertFalse(state.isIdle(600000L, 2000L));
        assertTrue(state.isActive());

        state.disconnect();
        assertFalse(state.isActive());
        assertEquals(AirPlaySessionState.PACKET_REJECTED, state.recordPacket(600001L));
    }
}
