package com.nukacast.app.net;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.X509TrustManager;

/**
 * Validates a server chain, dropping trailing certificates the old validator cannot digest.
 *
 * <p>Measured on Android 4.4: {@code https://api.github.com} presents
 * {@code *.github.com ← Sectigo … DV E36 ← Sectigo … Root E46 (cross-signed by USERTrust ECC)}, and the
 * 2013 {@code CertPathValidator} answers "Trust anchor for certification path not found" even though
 * <em>both</em> roots in that chain are present in the app's own bundle. The validator cannot verify the
 * cross-signed certificate's own signature, so the path dead-ends on a certificate the server sent
 * unnecessarily — the browser-visible result is a host that simply refuses to load on the television.
 *
 * <p>The fix is to ask the same trust store the same question with the last one or two certificates
 * removed. Nothing is weakened by this: every attempt still has to build a path to a certificate the
 * store already trusts, signatures and validity periods are still checked, and the hostname is checked
 * afterwards by OkHttp as before. Only the certificates the server chose to append are dropped.
 */
final class ChainRepairTrustManager implements X509TrustManager {

    /** Two is enough for the chains seen in the wild (cross-signed root plus one extra). */
    private static final int MAX_TRIMMED = 2;

    private final X509TrustManager delegate;

    ChainRepairTrustManager(X509TrustManager delegate) {
        this.delegate = delegate;
    }

    @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        delegate.checkClientTrusted(chain, authType);
    }

    @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        try {
            delegate.checkServerTrusted(chain, authType);
            return;
        } catch (CertificateException rejected) {
            for (int removed = 1; removed <= MAX_TRIMMED && removed < chain.length; removed++) {
                X509Certificate[] trimmed = new X509Certificate[chain.length - removed];
                System.arraycopy(chain, 0, trimmed, 0, trimmed.length);
                try {
                    delegate.checkServerTrusted(trimmed, authType);
                    // Recorded once per app run: "this host only loads because a trailing certificate
                    // was dropped" is exactly what is worth knowing when a source misbehaves later.
                    com.nukacast.app.diagnostics.StageTrace.component("http", "tls-chain",
                            "trimmed_" + removed, true,
                            "去掉末尾 " + removed + " 张证书后通过：" + trimmed[trimmed.length - 1].getSubjectX500Principal().getName());
                    return;
                } catch (CertificateException stillRejected) {
                    // Try one certificate fewer.
                }
            }
            throw rejected;
        }
    }

    @Override public X509Certificate[] getAcceptedIssuers() {
        return delegate.getAcceptedIssuers();
    }

    /** Exposed for the diagnostics endpoint: does the chain validate, and after how much trimming? */
    static int trustDepth(X509TrustManager manager, X509Certificate[] chain, String authType) {
        List<X509Certificate[]> attempts = new ArrayList<X509Certificate[]>();
        attempts.add(chain);
        for (int removed = 1; removed <= MAX_TRIMMED && removed < chain.length; removed++) {
            X509Certificate[] trimmed = new X509Certificate[chain.length - removed];
            System.arraycopy(chain, 0, trimmed, 0, trimmed.length);
            attempts.add(trimmed);
        }
        for (int index = 0; index < attempts.size(); index++) {
            try {
                manager.checkServerTrusted(attempts.get(index), authType);
                return index;
            } catch (CertificateException rejected) {
                // Next attempt.
            }
        }
        return -1;
    }
}
