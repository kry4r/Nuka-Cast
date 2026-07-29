package com.nukacast.app.server;

import org.junit.Test;

import static org.junit.Assert.assertFalse;

public final class ControlServerAccessTest {
    @Test
    public void managementApisAllowDirectLanAccess() {
        assertFalse(ControlServer.requiresAuthentication("/api/device"));
        assertFalse(ControlServer.requiresAuthentication("/api/sources"));
        assertFalse(ControlServer.requiresAuthentication("/api/player"));
    }
}
