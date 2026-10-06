package com.nukacast.app.airplay;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AirPlayIdentityTest {
    @Test
    public void generatedDeviceIdLooksLikeAUnicastMac() {
        String deviceId = AirPlayIdentity.generateDeviceId();
        assertTrue(deviceId.matches("(?i)02(:[0-9a-f]{2}){5}"));
    }

    @Test
    public void normalizesCaseAndCompactMac() {
        AirPlayIdentity identity = AirPlayIdentity.of(
                "02:aa:bb:cc:dd:ee", AirPlayIdentity.NAME, AirPlayIdentity.MODEL);
        assertEquals("02:AA:BB:CC:DD:EE", identity.deviceId);
        assertEquals("02AABBCCDDEE", identity.compactMac);
        assertEquals("NukaCast", identity.name);
        assertEquals("AppleTV3,2", identity.model);
    }

    @Test
    public void pairIdIsStableForTheSameIdentity() {
        AirPlayIdentity first = AirPlayIdentity.of(
                "02:AA:BB:CC:DD:EE", "NukaCast", "AppleTV3,2");
        AirPlayIdentity second = AirPlayIdentity.of(
                "02:AA:BB:CC:DD:EE", "NukaCast", "AppleTV3,2");
        assertEquals(first.pairId, second.pairId);
        assertFalse(first.pairId.isEmpty());
    }

    @Test
    public void pairIdChangesWithDeviceId() {
        AirPlayIdentity first = AirPlayIdentity.of(
                "02:AA:BB:CC:DD:EE", "NukaCast", "AppleTV3,2");
        AirPlayIdentity second = AirPlayIdentity.of(
                "02:AA:BB:CC:DD:EF", "NukaCast", "AppleTV3,2");
        assertFalse(first.pairId.equals(second.pairId));
    }

    @Test
    public void rejectsMalformedIdentity() {
        assertFalse(AirPlayIdentity.of("not-a-mac", "NukaCast", "AppleTV3,2").isUsable());
        assertFalse(AirPlayIdentity.of("02:AA:BB:CC:DD:EE", "", "AppleTV3,2").isUsable());
    }
}
