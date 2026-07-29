package com.nukacast.app.net;

import org.junit.Test;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.X509TrustManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public final class FallbackTrustManagerTest {
    private static final X509Certificate[] EMPTY_CHAIN = new X509Certificate[0];

    @Test
    public void keepsPlatformTrustAsTheFirstChoice() throws Exception {
        RecordingTrustManager platform = new RecordingTrustManager(true, 1);
        RecordingTrustManager bundled = new RecordingTrustManager(false, 2);

        new FallbackTrustManager(platform, bundled)
                .checkServerTrusted(EMPTY_CHAIN, "RSA");

        assertEquals(1, platform.serverChecks);
        assertEquals(0, bundled.serverChecks);
    }

    @Test
    public void triesBundledRootsAfterPlatformRejection() throws Exception {
        RecordingTrustManager platform = new RecordingTrustManager(false, 1);
        RecordingTrustManager bundled = new RecordingTrustManager(true, 2);

        FallbackTrustManager manager = new FallbackTrustManager(platform, bundled);
        manager.checkServerTrusted(EMPTY_CHAIN, "RSA");

        assertEquals(1, platform.serverChecks);
        assertEquals(1, bundled.serverChecks);
        assertEquals(3, manager.getAcceptedIssuers().length);
    }

    @Test
    public void rejectsAChainUnknownToBothStores() throws Exception {
        FallbackTrustManager manager = new FallbackTrustManager(
                new RecordingTrustManager(false, 0),
                new RecordingTrustManager(false, 0));

        try {
            manager.checkServerTrusted(EMPTY_CHAIN, "RSA");
            fail("Expected CertificateException");
        } catch (CertificateException expected) {
            assertEquals("rejected", expected.getMessage());
        }
    }

    private static final class RecordingTrustManager implements X509TrustManager {
        private final boolean accepts;
        private final X509Certificate[] issuers;
        int serverChecks;

        RecordingTrustManager(boolean accepts, int issuerCount) {
            this.accepts = accepts;
            this.issuers = new X509Certificate[issuerCount];
        }

        @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            if (!accepts) throw new CertificateException("rejected");
        }

        @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            serverChecks++;
            if (!accepts) throw new CertificateException("rejected");
        }

        @Override public X509Certificate[] getAcceptedIssuers() {
            return issuers;
        }
    }
}
