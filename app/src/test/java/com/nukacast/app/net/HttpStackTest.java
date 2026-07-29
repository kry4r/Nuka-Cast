package com.nukacast.app.net;

import org.junit.Test;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.X509TrustManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class HttpStackTest {
    @Test
    public void selectsBundledConscryptOnlyForLegacyAndroidRuntime() {
        assertFalse(HttpStack.usesBundledConscrypt(0));
        assertTrue(HttpStack.usesBundledConscrypt(16));
        assertTrue(HttpStack.usesBundledConscrypt(21));
        assertFalse(HttpStack.usesBundledConscrypt(22));
    }

    @Test
    public void keepsOnlyIpv4Addresses() throws Exception {
        InetAddress ipv6 = InetAddress.getByAddress(new byte[16]);
        InetAddress ipv4 = InetAddress.getByAddress(new byte[] {1, 1, 1, 1});

        List<InetAddress> result = HttpStack.ipv4Only(
                Arrays.asList(ipv6, ipv4), "example.com");

        assertEquals(1, result.size());
        assertEquals(ipv4, result.get(0));
    }

    @Test
    public void loadsBundledDigiCertGlobalRootG2() throws Exception {
        X509TrustManager manager = HttpStack.bundledTrustManager();

        assertEquals(1, manager.getAcceptedIssuers().length);
        assertEquals("CN=DigiCert Global Root G2,OU=www.digicert.com,O=DigiCert Inc,C=US",
                manager.getAcceptedIssuers()[0].getSubjectX500Principal().getName());
    }
}
