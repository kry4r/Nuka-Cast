package com.nukacast.app.net;

import android.os.Build;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.KeyStore;
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
    private static final OkHttpClient CLIENT = createClient();

    private HttpStack() {}

    public static OkHttpClient client() {
        return CLIENT;
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
                X509TrustManager trustManager = platformTrustManager();
                SSLContext context = SSLContext.getInstance("TLS");
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
        TrustManagerFactory factory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
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
