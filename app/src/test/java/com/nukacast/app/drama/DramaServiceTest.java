package com.nukacast.app.drama;

import static org.junit.Assert.assertEquals;

import com.nukacast.app.tvbox.model.SearchItem;

import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import javax.net.ssl.SSLHandshakeException;

public class DramaServiceTest {
    @Test public void mapsDramaExceptionCode() {
        assertEquals("http_error", DramaService.codeOf(new DramaException("http_error", "x")));
    }

    @Test public void mapsDnsFailure() {
        assertEquals("dns_error", DramaService.codeOf(new UnknownHostException("vote.test")));
    }

    @Test public void mapsTimeout() {
        assertEquals("timeout", DramaService.codeOf(new SocketTimeoutException("read timed out")));
    }

    @Test public void mapsTlsFailure() {
        assertEquals("tls_error", DramaService.codeOf(new SSLHandshakeException("bad cert")));
    }

    @Test public void mapsIoFailure() {
        assertEquals("network_error", DramaService.codeOf(new IOException("connection reset")));
    }

    @Test public void mapsUnknownFailure() {
        assertEquals("internal_error", DramaService.codeOf(new IllegalStateException("boom")));
    }

    @Test public void catalogEntryRoutesBackToDramaService() {
        com.nukacast.app.drama.model.DramaItem item =
                new com.nukacast.app.drama.model.DramaItem();
        item.providerId = "abc";
        item.dramaId = "7690192663693233177";
        item.title = "重生2000";
        item.cover = "https://example.test/c.jpg";
        item.remark = "全115集";
        item.category = "脑洞";
        item.intro = "重回2000年";
        SearchItem entry = item.toSearchItem();
        assertEquals("drama:abc", entry.sourceId);
        assertEquals("abc", entry.siteKey);
        assertEquals("7690192663693233177", entry.vodId);
        assertEquals("重生2000", entry.name);
        assertEquals("https://example.test/c.jpg", entry.poster);
        assertEquals("全115集", entry.remarks);
        assertEquals("脑洞", entry.typeName);
        assertEquals("重回2000年", entry.plot);
    }
}
