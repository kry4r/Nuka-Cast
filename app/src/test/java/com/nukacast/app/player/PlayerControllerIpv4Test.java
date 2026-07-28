package com.nukacast.app.player;

import com.nukacast.app.net.HttpStack;

import org.junit.Test;

import static org.junit.Assert.assertSame;

public final class PlayerControllerIpv4Test {
    @Test
    public void usesSharedIpv4OnlyHttpClient() {
        assertSame(HttpStack.client(), PlayerController.httpClient());
    }
}
