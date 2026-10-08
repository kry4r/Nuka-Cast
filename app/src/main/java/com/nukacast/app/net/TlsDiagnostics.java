package com.nukacast.app.net;

import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Answers "why does this host fail on the television?" for HTTPS.
 *
 * <p>A box running Android 4.4 has a 2013 trust store: hosts served by Let's Encrypt, Google Trust
 * Services or Sectigo fail with {@code Trust anchor for certification path not found} while being
 * perfectly valid, and the app carries its own bundle of roots to compensate. When a host fails anyway,
 * the useful question is which of the two trust stores disagreed and what it said, so this class
 * performs the handshake and then validates what the server sent against both — the answer is a
 * sentence, not a stack trace to guess from.
 *
 * <p>The socket used for the handshake accepts any certificate on purpose: nothing is fetched over it
 * beyond the certificate chain that is about to be validated, and the validation itself is done by the
 * real trust stores. Nothing here is used for application traffic.
 */
public final class TlsDiagnostics {

    private TlsDiagnostics() {
    }

    public static Map<String, Object> check(String host, int port, int timeoutMs) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("host", host);
        result.put("port", port);

        X509Certificate[] chain;
        try {
            chain = inspect(host, port, timeoutMs);
        } catch (Throwable failure) {
            result.put("handshake", "failed");
            result.put("handshakeError", describe(failure));
            return result;
        }
        result.put("handshake", "ok");
        result.put("chain", claimChain(chain));
        result.put("anchorCount", anchorCount());
        result.put("bundleResources", new ArrayList<String>(HttpStack.bundleNotes()));

        result.put("platform", verdictSafely(true, chain));
        result.put("bundled", verdictSafely(false, chain));
        result.put("attempts", attempts(chain));
        result.put("handshakeWithAppStack", appStackHandshake(host, port, timeoutMs));
        result.put("degraded", HttpStack.degraded());
        result.put("initError", HttpStack.initError());
        return result;
    }

    /** The lockout-free handshake, used only to read the chain the server presents. */
    private static X509Certificate[] inspect(String host, int port, int timeoutMs) throws Exception {
        TrustManager[] permissive = new TrustManager[] {new InspectOnlyTrustManager()};
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, permissive, new java.security.SecureRandom());
        SSLSocketFactory factory = context.getSocketFactory();
        SSLSocket socket = (SSLSocket) factory.createSocket();
        try {
            socket.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.startHandshake();
            java.security.cert.Certificate[] peer = socket.getSession().getPeerCertificates();
            List<X509Certificate> certificates = new ArrayList<X509Certificate>();
            for (java.security.cert.Certificate certificate : peer) {
                if (certificate instanceof X509Certificate) certificates.add((X509Certificate) certificate);
            }
            return certificates.toArray(new X509Certificate[0]);
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
                // Nothing useful to do about a socket that will not close.
            }
        }
    }

    /**
     * Every repair this app could apply, with what the trust store says about each.
     *
     * <p>"Trust anchor not found" gives no hint about which certificate was the problem, so each shape is
     * tried: the chain as sent, shorter chains, and the chain with the self-signed root appended — the
     * shape a browser effectively ends up with.
     */
    private static List<Map<String, Object>> attempts(X509Certificate[] chain) {
        List<Map<String, Object>> attempts = new ArrayList<Map<String, Object>>();
        X509TrustManager manager;
        try {
            manager = bundledTrustManager();
        } catch (Throwable failure) {
            return attempts;
        }
        java.util.List<X509Certificate> anchors = new ArrayList<X509Certificate>();
        java.util.Collections.addAll(anchors, manager.getAcceptedIssuers());

        for (int removed = 0; removed <= MAX_TRIMMED && removed < chain.length; removed++) {
            X509Certificate[] candidate = new X509Certificate[chain.length - removed];
            System.arraycopy(chain, 0, candidate, 0, candidate.length);
            addAttempt(attempts, manager, removed == 0 ? "原样" : "去掉末尾 " + removed + " 张", candidate);
        }
        // A server often ends its chain with a cross-signed copy of a root; the same root's self-signed
        // certificate is what a trust store holds. Both "what the server meant" and "what the store has"
        // are tried, so the answer says whether the app can repair the chain itself.
        for (int index = 0; index < chain.length; index++) {
            X509Certificate anchor = findAnchor(anchors, chain[index].getIssuerX500Principal() == null
                    ? null : chain[index].getIssuerX500Principal().getName());
            if (anchor == null) continue;
            X509Certificate[] appended = new X509Certificate[index + 2];
            System.arraycopy(chain, 0, appended, 0, index + 1);
            appended[appended.length - 1] = anchor;
            addAttempt(attempts, manager, "到第 " + (index + 1) + " 张为止 + 自签名根 "
                    + name(anchor.getSubjectX500Principal().getName()), appended);
        }
        // The intermediate on its own: this is the question OpenSSL answers "OK", so a failure here
        // means the trust store itself (not the chain shape) is what the device disagrees with.
        if (chain.length >= 2) {
            addAttempt(attempts, manager, "只用服务器发的中间证书", new X509Certificate[] {chain[1]});
        }
        return attempts;
    }

    private static void addAttempt(List<Map<String, Object>> attempts, X509TrustManager manager,
                                   String shape, X509Certificate[] candidate) {
        Map<String, Object> attempt = new LinkedHashMap<String, Object>();
        attempt.put("shape", shape);
        attempt.putAll(verdict(manager, candidate));
        attempts.add(attempt);
    }

    /** The trusted certificate with this subject, if the store has one. */
    private static X509Certificate findAnchor(List<X509Certificate> anchors, String subject) {
        if (subject == null) return null;
        for (X509Certificate anchor : anchors) {
            String anchorSubject = anchor.getSubjectX500Principal().getName();
            if (anchorSubject != null && anchorSubject.equals(subject)) return anchor;
        }
        return null;
    }

    /** Two is enough for the chains seen in the wild. */
    private static final int MAX_TRIMMED = 2;

    /**
     * The question that actually matters: does a handshake through the app's own client work?
     *
     * <p>A trust store can validate a chain while the handshake still fails — the provider's own trust
     * manager may be consulted instead of the one the app configured — and the two answers look the same
     * from a stack trace. This performs the handshake with the app's socket factory and trust manager and
     * says what came back.
     */
    private static Map<String, Object> appStackHandshake(String host, int port, int timeoutMs) {
        Map<String, Object> outcome = new LinkedHashMap<String, Object>();
        try {
            javax.net.ssl.SSLSocketFactory factory = HttpStack.socketFactory();
            if (factory == null) {
                outcome.put("result", "无自定义工厂（平台 TLS）");
                return outcome;
            }
            javax.net.ssl.SSLSocket socket = (javax.net.ssl.SSLSocket) factory.createSocket();
            try {
                socket.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
                socket.setSoTimeout(timeoutMs);
                socket.startHandshake();
                outcome.put("result", "ok");
                outcome.put("protocol", socket.getSession().getProtocol());
                outcome.put("cipher", socket.getSession().getCipherSuite());
            } finally {
                try {
                    socket.close();
                } catch (Exception ignored) {
                    // Nothing useful to do about a socket that will not close.
                }
            }
        } catch (Throwable failure) {
            outcome.put("result", "failed");
            outcome.put("error", describe(failure));
        }
        return outcome;
    }

    /** What one trust store thinks of the chain, including the case where it cannot be built at all. */
    private static Map<String, Object> verdictSafely(boolean platform, X509Certificate[] chain) {
        X509TrustManager manager;
        try {
            manager = platform ? platformTrustManager() : bundledTrustManager();
        } catch (Throwable failure) {
            Map<String, Object> broken = new LinkedHashMap<String, Object>();
            broken.put("trusted", false);
            broken.put("reason", "无法构建：" + describe(failure));
            return broken;
        }
        Map<String, Object> verdict = verdict(manager, chain);
        // When the plain attempt fails, say whether dropping trailing certificates helps — that is the
        // difference between "this host cannot be trusted" and "this host needs the old validator
        // worked around", and only the second one is something the app can do anything about.
        if (!Boolean.TRUE.equals(verdict.get("trusted"))) {
            int depth = ChainRepairTrustManager.trustDepth(manager, chain, authType(chain));
            if (depth > 0) {
                verdict.put("trusted", true);
                verdict.put("reason", "去掉末尾 " + depth + " 张证书后通过");
                verdict.put("trimmed", depth);
            }
        }
        return verdict;
    }

    /** What one trust store thinks of the chain. */
    private static Map<String, Object> verdict(X509TrustManager manager, X509Certificate[] chain) {
        Map<String, Object> verdict = new LinkedHashMap<String, Object>();
        try {
            manager.checkServerTrusted(chain, authType(chain));
            verdict.put("trusted", true);
            verdict.put("reason", "");
        } catch (CertificateException rejected) {
            verdict.put("trusted", false);
            verdict.put("reason", rejected.getClass().getSimpleName() + ": "
                    + (rejected.getMessage() == null ? "" : rejected.getMessage()));
        } catch (Throwable failure) {
            verdict.put("trusted", false);
            verdict.put("reason", describe(failure));
        }
        return verdict;
    }

    /** The chain as readable lines: who signed whom, which is what makes a failure obvious. */
    private static List<String> claimChain(X509Certificate[] chain) {
        List<String> lines = new ArrayList<String>();
        for (X509Certificate certificate : chain) {
            lines.add(subject(certificate) + " ← " + issuer(certificate));
        }
        return lines;
    }

    static String subject(X509Certificate certificate) {
        return name(certificate.getSubjectX500Principal().getName());
    }

    static String issuer(X509Certificate certificate) {
        return name(certificate.getIssuerX500Principal().getName());
    }

    /** "CN=github.com, O=GitHub, Inc., C=US" → "github.com (GitHub, Inc.)". */
    static String name(String distinguishedName) {
        if (distinguishedName == null) return "";
        String commonName = null;
        String organisation = null;
        for (String part : distinguishedName.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("CN=")) commonName = trimmed.substring(3);
            else if (trimmed.startsWith("O=")) organisation = trimmed.substring(2);
        }
        if (commonName == null) return distinguishedName;
        return organisation == null || organisation.isEmpty()
                ? commonName : commonName + " (" + organisation + ")";
    }

    /** How many certificates the bundled store actually holds. */
    private static int anchorCount() {
        try {
            return bundledTrustManager().getAcceptedIssuers().length;
        } catch (Throwable failure) {
            return -1;
        }
    }

    /** Server authentication is what every failure here has been about. */
    private static String authType(X509Certificate[] chain) {
        if (chain.length == 0) return "RSA";
        String algorithm = chain[0].getPublicKey().getAlgorithm();
        return algorithm == null || algorithm.isEmpty() ? "RSA" : algorithm;
    }

    private static X509TrustManager platformTrustManager() throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        return first(factory);
    }

    private static X509TrustManager bundledTrustManager() throws Exception {
        return HttpStack.bundledTrustManager();
    }

    private static X509TrustManager first(TrustManagerFactory factory) {
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager) return (X509TrustManager) manager;
        }
        throw new IllegalStateException("系统未提供 X509TrustManager");
    }

    private static String describe(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /** Accepts anything; see the class comment for why that is acceptable here and nowhere else. */
    private static final class InspectOnlyTrustManager implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
