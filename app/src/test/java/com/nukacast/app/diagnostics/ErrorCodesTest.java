package com.nukacast.app.diagnostics;

import org.junit.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLHandshakeException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public final class ErrorCodesTest {
    @Test public void classifiesNetworkFailures() {
        assertEquals("dns_error", ErrorCodes.of(new UnknownHostException("vote.test")));
        assertEquals("timeout", ErrorCodes.of(new SocketTimeoutException("read timed out")));
        assertEquals("timeout", ErrorCodes.of(new TimeoutException("future")));
        assertEquals("timeout", ErrorCodes.of(new InterruptedIOException("cancelled")));
        assertEquals("tls_error", ErrorCodes.of(new SSLHandshakeException("bad cert")));
        assertEquals("network_error", ErrorCodes.of(new IOException("connection reset")));
    }

    @Test public void classifiesDalvikAndLinkerFailures() {
        assertEquals("linkage_error", ErrorCodes.of(new VerifyError("bad bytecode")));
        assertEquals("linkage_error", ErrorCodes.of(new NoClassDefFoundError("com.x.Spider")));
        assertEquals("linkage_error", ErrorCodes.of(
                new ExceptionInInitializerError("static init")));
        assertEquals("native_link_error", ErrorCodes.of(new UnsatisfiedLinkError("libx.so")));
    }

    @Test public void classifiesProcessLevelFailures() {
        assertEquals("out_of_memory", ErrorCodes.of(new OutOfMemoryError("heap")));
        assertEquals("cancelled", ErrorCodes.of(new InterruptedException("interrupted")));
        assertEquals("internal_error", ErrorCodes.of(new IllegalStateException("boom")));
    }

    @Test public void usesTheDeepestCauseWhenOneExists() {
        IOException outer = new IOException("connection reset",
                new VerifyError("dalvik verifier rejected class"));
        assertEquals("linkage_error", ErrorCodes.of(outer));
        assertSame(VerifyError.class, ErrorCodes.root(outer).getClass());
        assertEquals("dalvik verifier rejected class", ErrorCodes.message(outer));
    }

    @Test public void handlesNullAndEmptyMessages() {
        assertEquals("", ErrorCodes.of(null));
        assertEquals("IllegalStateException", ErrorCodes.message(new IllegalStateException()));
    }
}
