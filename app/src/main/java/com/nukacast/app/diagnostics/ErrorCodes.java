package com.nukacast.app.diagnostics;

import java.io.IOException;

/**
 * Maps failures to stable, low-cardinality codes so diagnostics can distinguish a bad network from
 * a Dalvik verifier failure, a native library problem or an OOM kill.
 */
public final class ErrorCodes {
    private ErrorCodes() {}

    public static String of(Throwable error) {
        if (error == null) return "";
        Throwable root = root(error);
        String name = root.getClass().getName();
        if (root instanceof OutOfMemoryError) return "out_of_memory";
        if (name.endsWith("UnsatisfiedLinkError")) return "native_link_error";
        if (name.endsWith("UnknownHostException")) return "dns_error";
        if (name.endsWith("SocketTimeoutException")
                || name.endsWith("InterruptedIOException")) return "timeout";
        if (name.contains("SSL") || name.contains("Certificate")) return "tls_error";
        if (name.endsWith("VerifyError") || name.endsWith("NoClassDefFoundError")
                || name.endsWith("ClassNotFoundException")
                || name.endsWith("ExceptionInInitializerError")
                || name.endsWith("LinkageError") || name.endsWith("IncompatibleClassChangeError")) {
            return "linkage_error";
        }
        if (name.endsWith("TimeoutException")) return "timeout";
        if (root instanceof InterruptedException) return "cancelled";
        if (root instanceof IOException) return "network_error";
        if (root instanceof Error) return "fatal_error";
        return "internal_error";
    }

    /** Deepest cause, which is where Dalvik/linker/network failure usually records the real reason. */
    public static Throwable root(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current == null ? error : current;
    }

    public static String message(Throwable error) {
        if (error == null) return "";
        Throwable root = root(error);
        String message = root.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = error.getMessage();
        }
        return message == null || message.trim().isEmpty()
                ? root.getClass().getSimpleName() : message.trim();
    }
}
