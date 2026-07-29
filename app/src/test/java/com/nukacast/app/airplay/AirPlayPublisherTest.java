package com.nukacast.app.airplay;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AirPlayPublisherTest {
    @Test
    public void rejectsWildcardAndAcceptsAssignedLanAddress() {
        assertFalse(AirPlayPublisher.isUsableAddress(null));
        assertFalse(AirPlayPublisher.isUsableAddress("0.0.0.0"));
        assertTrue(AirPlayPublisher.isUsableAddress("192.168.1.8"));
    }
}
