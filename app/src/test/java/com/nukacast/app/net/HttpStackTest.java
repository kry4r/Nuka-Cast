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

    /**
     * The bundled store must cover the roots that actually sign the sites users paste into the
     * source manager. A single DigiCert root produced "Trust anchor for certification path not
     * found" for every Let's Encrypt/Sectigo/GlobalSign host on Android 4.4, so the assertion is
     * about coverage, not about one specific certificate.
     */
    @Test
    public void bundlesAWideRootSetForLegacyDevices() throws Exception {
        X509TrustManager manager = HttpStack.bundledTrustManager();
        java.security.cert.X509Certificate[] issuers = manager.getAcceptedIssuers();

        assertTrue("内置根证书太少：" + issuers.length, issuers.length >= 100);
        java.util.Set<String> subjects = new java.util.HashSet<String>();
        for (java.security.cert.X509Certificate issuer : issuers) {
            subjects.add(issuer.getSubjectX500Principal().getName());
        }
        assertTrue("缺少 DigiCert Global Root G2",
                containsSubject(subjects, "DigiCert Global Root G2"));
        assertTrue("缺少 ISRG Root X1（Let's Encrypt）",
                containsSubject(subjects, "ISRG Root X1"));
        assertTrue("缺少 USERTrust/AAA（Sectigo）",
                containsSubject(subjects, "USERTrust") || containsSubject(subjects, "AAA Certificate Services"));
    }

    private static boolean containsSubject(java.util.Set<String> subjects, String needle) {
        for (String subject : subjects) {
            if (subject.contains(needle)) return true;
        }
        return false;
    }

    @Test
    public void alwaysProvidesAClientEvenWhenLegacyTlsSetupFails() {
        assertFalse(HttpStack.client() == null);
        assertFalse(HttpStack.fallbackClient() == null);
        assertTrue(HttpStack.fallbackClient().connectTimeoutMillis() > 0);
    }

    @Test
    public void reportsTheRootCauseOfAnInitializationFailure() {
        String described = HttpStack.describeInitFailure(new IllegalStateException(
                "无法初始化 Android 4.4 TLS 1.2", new UnsatisfiedLinkError("no conscrypt")));
        assertTrue(described.contains("UnsatisfiedLinkError"));
        assertTrue(described.contains("no conscrypt"));
        assertEquals("RuntimeException", HttpStack.describeInitFailure(
                new RuntimeException()));
    }
}
