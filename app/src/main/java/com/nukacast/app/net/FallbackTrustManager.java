package com.nukacast.app.net;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.X509TrustManager;

final class FallbackTrustManager implements X509TrustManager {
    private final X509TrustManager primary;
    private final X509TrustManager fallback;

    FallbackTrustManager(X509TrustManager primary, X509TrustManager fallback) {
        if (primary == null || fallback == null) {
            throw new IllegalArgumentException("Trust managers must not be null");
        }
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        try {
            primary.checkClientTrusted(chain, authType);
        } catch (CertificateException rejected) {
            fallback.checkClientTrusted(chain, authType);
        }
    }

    @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        try {
            primary.checkServerTrusted(chain, authType);
        } catch (CertificateException rejected) {
            fallback.checkServerTrusted(chain, authType);
        }
    }

    @Override public X509Certificate[] getAcceptedIssuers() {
        X509Certificate[] first = primary.getAcceptedIssuers();
        X509Certificate[] second = fallback.getAcceptedIssuers();
        int firstLength = first == null ? 0 : first.length;
        int secondLength = second == null ? 0 : second.length;
        X509Certificate[] combined = new X509Certificate[firstLength + secondLength];
        if (firstLength > 0) System.arraycopy(first, 0, combined, 0, firstLength);
        if (secondLength > 0) {
            System.arraycopy(second, 0, combined, firstLength, secondLength);
        }
        return combined;
    }
}
