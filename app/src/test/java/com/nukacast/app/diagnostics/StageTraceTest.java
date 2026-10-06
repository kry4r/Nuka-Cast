package com.nukacast.app.diagnostics;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class StageTraceTest {
    @Before public void setUp() {
        StageTrace.clear();
    }

    @After public void tearDown() {
        StageTrace.clear();
    }

    @Test public void recordsSuccessfulSourceStages() {
        StageTrace.Trace trace = StageTrace.start("source", "肥猫");
        trace.stage("fetch_config");
        trace.stage("decode");
        trace.stage("persist");
        trace.success();

        List<StageTrace.Record> records = StageTrace.snapshot();
        assertEquals(1, records.size());
        StageTrace.Record record = records.get(0);
        assertEquals("source", record.scope);
        assertEquals("肥猫", record.subject);
        assertEquals("persist", record.stage);
        assertEquals(StageTrace.RESULT_OK, record.result);
        assertTrue(record.elapsedMs >= 0L);
        assertTrue(record.errorCode.isEmpty());
    }

    @Test public void recordsFailureWithRootCauseAndCode() {
        StageTrace.Trace trace = StageTrace.start("source", "xhztv");
        trace.stage("decode");
        trace.failure(new IOException("配置解码失败", new VerifyError("bad class")));

        StageTrace.Record record = StageTrace.snapshot().get(0);
        assertEquals(StageTrace.RESULT_FAILED, record.result);
        assertEquals("linkage_error", record.errorCode);
        assertEquals("java.lang.VerifyError", record.rootCauseClass);
        assertEquals("bad class", record.detail);
    }

    @Test public void recordsComponentFailureForNativeLoads() {
        StageTrace.componentFailure("airplay", "native", "native_load",
                new UnsatisfiedLinkError("libnukacast_airplay.so"));

        StageTrace.Record record = StageTrace.snapshot().get(0);
        assertEquals("native_load", record.stage);
        assertEquals("native_link_error", record.errorCode);
        assertEquals(StageTrace.RESULT_FAILED, record.result);
    }

    @Test public void keepsNewestRecordsFirstAndBoundsTheBuffer() {
        for (int i = 0; i < 60; i++) {
            StageTrace.Trace trace = StageTrace.start("search", "k" + i);
            trace.stage("search");
            trace.success();
        }
        List<StageTrace.Record> records = StageTrace.snapshot();
        assertEquals(48, records.size());
        assertEquals("k59", records.get(0).subject);
        assertEquals("k12", records.get(records.size() - 1).subject);
        assertFalse(records.get(0).generation == records.get(1).generation);
    }

    @Test public void clearResetsRecordsAndGeneration() {
        StageTrace.Trace trace = StageTrace.start("http", "tls");
        trace.stage("legacy_tls");
        trace.success();
        StageTrace.clear();
        assertTrue(StageTrace.snapshot().isEmpty());

        StageTrace.Trace next = StageTrace.start("http", "tls");
        next.stage("platform_tls");
        next.failure("tls_provider_unavailable", "Conscrypt 不可用");
        StageTrace.Record record = StageTrace.snapshot().get(0);
        assertEquals(1, record.generation);
        assertEquals("tls_provider_unavailable", record.errorCode);
    }
}
