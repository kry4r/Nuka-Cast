package com.nukacast.app.net;

import android.os.Build;

import com.nukacast.app.diagnostics.StageTrace;

import org.conscrypt.Conscrypt;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.ConnectionSpec;
import okhttp3.Dns;
import okhttp3.OkHttpClient;

public final class HttpStack {
    private static final Dns IPV4_DNS = new Dns() {
        @Override public List<InetAddress> lookup(String hostname) throws UnknownHostException {
            return ipv4Only(Dns.SYSTEM.lookup(hostname), hostname);
        }
    };
    /**
     * The client is built inside a static block that contains every failure mode: on API 16-21 the
     * TLS 1.2 setup loads Conscrypt, and a linker/initializer {@link Error} escaping class
     * initialization would otherwise crash whichever thread touched the network first. When the
     * legacy TLS path cannot be built we keep a plain platform-TLS client and publish the reason
     * instead of taking the process down or silently weakening certificate validation.
     */
    private static final OkHttpClient CLIENT;
    private static final String INIT_ERROR;

    static {
        OkHttpClient client = null;
        String error = "";
        try {
            client = createClient();
        } catch (Throwable failure) {
            error = describeInitFailure(failure);
        }
        if (client == null) client = fallbackClient();
        CLIENT = client;
        INIT_ERROR = error;
        StageTrace.component("http", "tls", INIT_ERROR.isEmpty() ? "legacy_tls" : "platform_tls",
                INIT_ERROR.isEmpty(), INIT_ERROR);
    }

    private HttpStack() {}

    public static OkHttpClient client() {
        return CLIENT;
    }

    /** True when the legacy TLS 1.2 stack could not be built and platform TLS is in use. */
    public static boolean degraded() {
        return !INIT_ERROR.isEmpty();
    }

    public static String initError() {
        return INIT_ERROR;
    }

    static String describeInitFailure(Throwable failure) {
        Throwable root = failure;
        while (root != null && root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root == null) return "未知错误";
        String message = root.getMessage();
        return message == null || message.trim().isEmpty()
                ? root.getClass().getSimpleName()
                : root.getClass().getSimpleName() + "：" + message;
    }

    /** Plain platform-TLS client used when the legacy TLS 1.2 stack is unavailable. */
    static OkHttpClient fallbackClient() {
        return new OkHttpClient.Builder()
                .dns(IPV4_DNS)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .build();
    }

    public static Dns dns() {
        return IPV4_DNS;
    }

    private static OkHttpClient createClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .dns(IPV4_DNS)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true);
        if (Build.VERSION.SDK_INT < 22) {
            try {
                X509TrustManager trustManager = new FallbackTrustManager(
                        platformTrustManager(), bundledTrustManager());
                SSLContext context = usesBundledConscrypt(Build.VERSION.SDK_INT)
                        ? SSLContext.getInstance("TLS", Conscrypt.newProvider())
                        : SSLContext.getInstance("TLS");
                context.init(null, new TrustManager[] {trustManager}, null);
                builder.sslSocketFactory(new Tls12SocketFactory(
                                context.getSocketFactory(), Build.VERSION.SDK_INT), trustManager)
                        .connectionSpecs(Arrays.asList(
                                ConnectionSpec.MODERN_TLS,
                                ConnectionSpec.COMPATIBLE_TLS,
                                ConnectionSpec.CLEARTEXT));
            } catch (Exception error) {
                throw new IllegalStateException("无法初始化 Android 4.4 TLS 1.2", error);
            }
        }
        return builder.build();
    }

    private static X509TrustManager platformTrustManager() throws Exception {
        return trustManager(null);
    }

    /**
     * Trust anchors used in addition to the platform store on Android 4.x.
     *
     * <p>A single DigiCert root was not enough in practice: sites served by Let's Encrypt, Sectigo,
     * GlobalSign or Google Trust Services failed with {@code Trust anchor for certification path not
     * found} even though the certificates were perfectly valid. The bundle is Mozilla's CA set plus
     * the roots that the app shipped with, and it is refreshed with
     * {@code node tools/update-ca-bundle.mjs}. Certificate and hostname validation stay enabled.
     */
    static X509TrustManager bundledTrustManager() throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        int loaded = 0;
        loaded += loadCertificates(store, "/com/nukacast/app/net/mozilla_ca_bundle.pem", "mozilla");
        loaded += loadCertificates(store, "/com/nukacast/app/net/digicert_global_root_g2.pem", "digicert");
        if (loaded == 0) throw new IOException("缺少内置根证书资源");
        return trustManager(store);
    }

    private static int loadCertificates(KeyStore store, String resource, String prefix)
            throws Exception {
        InputStream input = HttpStack.class.getResourceAsStream(resource);
        if (input == null) return 0;
        try {
            java.util.Collection<? extends Certificate> certificates =
                    CertificateFactory.getInstance("X.509").generateCertificates(input);
            int index = 0;
            for (Certificate certificate : certificates) {
                store.setCertificateEntry(prefix + "-" + index, certificate);
                index++;
            }
            return index;
        } finally {
            input.close();
        }
    }

    static boolean usesBundledConscrypt(int sdk) {
        return sdk >= 16 && sdk < 22;
    }

    private static X509TrustManager trustManager(KeyStore store) throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager) return (X509TrustManager) manager;
        }
        throw new IllegalStateException("系统未提供 X509TrustManager");
    }

    static List<InetAddress> ipv4Only(List<InetAddress> addresses, String hostname)
            throws UnknownHostException {
        List<InetAddress> result = new ArrayList<InetAddress>();
        for (InetAddress address : addresses) {
            if (address instanceof Inet4Address) result.add(address);
        }
        if (result.isEmpty()) throw new UnknownHostException(hostname + " 没有 IPv4 地址");
        return result;
    }
}
