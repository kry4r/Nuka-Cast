package com.nukacast.app.diagnostics;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AppLogTest {
    @Test
    public void filtersEntriesByExactSeverity() throws Exception {
        AppLog log = log(10, 65536);
        log.add(AppLog.Level.INFO, "AirPlay", "receiver ready", null);
        log.add(AppLog.Level.ERROR, "Player", "decode failed", new Exception("codec"));

        assertEquals(2, log.entries(null).size());
        assertEquals(1, log.entries(AppLog.Level.ERROR).size());
        assertTrue(log.formatted(AppLog.Level.ERROR).contains("错误  [Player]"));
        assertFalse(log.formatted(AppLog.Level.ERROR).contains("receiver ready"));
    }

    @Test
    public void retainsNewestEntriesWithinBound() throws Exception {
        AppLog log = log(2, 65536);
        log.add(AppLog.Level.DEBUG, "Test", "first", null);
        log.add(AppLog.Level.INFO, "Test", "second", null);
        log.add(AppLog.Level.WARN, "Test", "third", null);

        assertEquals(2, log.entries(null).size());
        assertEquals("second", log.entries(null).get(0).message);
        assertEquals("third", log.entries(null).get(1).message);
    }

    @Test
    public void persistsAndClearsEntries() throws Exception {
        File directory = Files.createTempDirectory("nukacast-log").toFile();
        File file = new File(directory, "log.jsonl");
        AppLog first = new AppLog(file, 10, 65536);
        first.add(AppLog.Level.WARN, "Source", "HTTP 502", null);

        AppLog restored = new AppLog(file, 10, 65536);
        assertEquals("HTTP 502", restored.entries(null).get(0).message);
        restored.clearEntries();
        assertTrue(restored.entries(null).isEmpty());
        assertFalse(file.exists());
    }

    private static AppLog log(int maxEntries, int maxBytes) throws Exception {
        File directory = Files.createTempDirectory("nukacast-log").toFile();
        return new AppLog(new File(directory, "log.jsonl"), maxEntries, maxBytes);
    }
}
