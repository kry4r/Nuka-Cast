package com.nukacast.app.net;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

public final class Tls12SocketFactoryTest {
    @Test
    public void api19AddsTls12AheadOfLegacyEnabledProtocols() {
        assertArrayEquals(new String[] {"TLSv1.2", "TLSv1"},
                Tls12SocketFactory.protocolsFor(19,
                        new String[] {"TLSv1", "TLSv1.1", "TLSv1.2"},
                        new String[] {"TLSv1"}));
    }

    @Test
    public void modernAndroidKeepsPlatformEnabledProtocols() {
        assertArrayEquals(new String[] {"TLSv1.3", "TLSv1.2"},
                Tls12SocketFactory.protocolsFor(29,
                        new String[] {"TLSv1", "TLSv1.2", "TLSv1.3"},
                        new String[] {"TLSv1.3", "TLSv1.2"}));
    }
}
