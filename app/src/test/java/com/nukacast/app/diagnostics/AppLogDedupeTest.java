package com.nukacast.app.diagnostics;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Deduplication is what keeps the buffer useful on a device: before it, a decoder retry loop and a
 * hundred failing sites filled all 500 entries in seconds, and the interesting lines were gone.
 */
public final class AppLogDedupeTest {
    @Test
    public void foldsIdenticalConsecutiveLinesIntoOneEntry() throws Exception {
        AppLog log = log();
        for (int i = 0; i < 200; i++) {
            log.add(AppLog.Level.ERROR, "AirPlay 视频", "解码循环异常：IllegalStateException",
                    new IllegalStateException("boom"));
        }
        assertEquals(1, log.entries(AppLog.Level.ERROR).size());
        assertEquals(200, log.entries(AppLog.Level.ERROR).get(0).repeats);
        assertTrue(log.formatted(AppLog.Level.ERROR).contains("重复 200 次"));
    }

    @Test
    public void keepsDifferentMessagesSeparate() throws Exception {
        AppLog log = log();
        log.add(AppLog.Level.WARN, "片源", "首页站点失败 [A]：超时", null);
        log.add(AppLog.Level.WARN, "片源", "首页站点失败 [B]：超时", null);
        assertEquals(2, log.entries(AppLog.Level.WARN).size());
        assertEquals(1, log.entries(AppLog.Level.WARN).get(0).repeats);
    }

    @Test
    public void aDifferentComponentStartsANewEntry() throws Exception {
        AppLog log = log();
        log.add(AppLog.Level.INFO, "AirPlay", "使用 OMX.google.h264.decoder 解码 498x1080（软件）", null);
        log.add(AppLog.Level.INFO, "AirPlay", "使用 OMX.google.h264.decoder 解码 498x1080（软件）", null);
        log.add(AppLog.Level.INFO, "Spider", "使用 OMX.google.h264.decoder 解码 498x1080（软件）", null);
        assertEquals(2, log.entries(AppLog.Level.INFO).size());
        assertEquals(2, log.entries(AppLog.Level.INFO).get(0).repeats);
    }

    @Test
    public void trimsTracesUnderPressureButKeepsMessages() throws Exception {
        AppLog log = log();
        log.add(AppLog.Level.ERROR, "崩溃", "线程 main 发生未捕获异常", new IllegalStateException("boom"));
        assertTrue(log.entries(null).get(0).trace.contains("IllegalStateException"));

        log.trimTraces();

        assertEquals("", log.entries(null).get(0).trace);
        assertEquals("线程 main 发生未捕获异常", log.entries(null).get(0).message);
    }

    @Test
    public void boundsTheMessageLength() throws Exception {
        AppLog log = log();
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 5000; i++) huge.append('x');
        log.add(AppLog.Level.WARN, "片源", huge.toString(), null);
        assertTrue(log.entries(null).get(0).message.length() < 4200);
    }

    private static AppLog log() throws Exception {
        File directory = Files.createTempDirectory("nukacast-log-dedupe").toFile();
        return new AppLog(new File(directory, "log.jsonl"), 50, 65536);
    }
}
