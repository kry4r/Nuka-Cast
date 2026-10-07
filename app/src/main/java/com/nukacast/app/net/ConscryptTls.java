package com.nukacast.app.net;

import android.os.Build;

import com.nukacast.app.diagnostics.AppLog;

import org.conscrypt.Conscrypt;

import java.security.Security;

/**
 * Installs Conscrypt as the process-wide TLS provider on old devices.
 *
 * <p>Android 4.4's platform TLS cannot negotiate TLS 1.2 with most modern servers, and it fails with
 * messages that look like a network problem rather than a protocol one:
 *
 * <pre>{@code
 * javax.net.ssl.SSLProtocolException: SSL handshake aborted … sslv3 alert handshake failure
 * }</pre>
 *
 * <p>OkHttp gets Conscrypt through {@code SSLContext}, but anything else using {@code HttpsURLConnection}
 * — the CMS player-page resolver, for example — still goes through the platform provider and fails.
 * Registering Conscrypt at position 1 makes every {@code https://} connection in the process use it,
 * which is what the platform documentation recommends for API &lt; 21.
 */
public final class ConscryptTls {
    private static volatile boolean installed;
    private static volatile String failure = "";

    private ConscryptTls() {}

    /** Idempotent; failures are recorded rather than thrown. */
    public static void install() {
        if (installed || Build.VERSION.SDK_INT >= 21) return;
        synchronized (ConscryptTls.class) {
            if (installed) return;
            try {
                Security.insertProviderAt(Conscrypt.newProvider(), 1);
                installed = true;
                AppLog.i("网络", "已启用 Conscrypt TLS 1.2 提供者（Android " + Build.VERSION.SDK_INT + "）");
            } catch (Throwable error) {
                failure = error.getClass().getSimpleName() + ": " + error.getMessage();
                AppLog.w("网络", "启用 Conscrypt 失败，HTTPS 可能回落到旧版协议：" + failure);
            }
        }
    }

    public static boolean isInstalled() {
        return installed;
    }

    public static String failureReason() {
        return failure;
    }
}
