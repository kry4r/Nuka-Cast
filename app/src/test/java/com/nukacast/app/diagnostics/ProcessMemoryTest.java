package com.nukacast.app.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The budgets are what keep a 139-site config from making the app allocate until the platform's
 * killer ends the process, so their arithmetic is tested directly.
 */
public class ProcessMemoryTest {
    @Test public void parsesProcStatusLines() {
        assertEquals(12L * 1024L * 1024L,
                ProcessMemory.parseKilobytes("VmRSS:\t  12288 kB", "VmRSS:"));
        assertEquals(3L * 1024L,
                ProcessMemory.parseKilobytes("Threads:\t3", "Threads:"));
        assertEquals(0L, ProcessMemory.parseKilobytes("VmSize:", "VmSize:"));
        assertEquals(0L, ProcessMemory.parseKilobytes("Something else", "VmRSS:"));
        assertEquals(0L, ProcessMemory.parseKilobytes(null, "VmRSS:"));
    }

    @Test public void pluginBudgetScalesWithDeviceMemory() {
        // 1 GB TV: a sixth of RAM, which leaves room for the decoder and the receiver.
        assertEquals(1024L * 1024L * 1024L / 6L, ProcessMemory.pluginBudgetFor(1024L));
        // Small boxes get the floor rather than an unusable few megabytes.
        assertEquals(48L * 1024L * 1024L, ProcessMemory.pluginBudgetFor(256L));
        // Large boxes are still capped: plugins do not get to grow without limit.
        assertEquals(192L * 1024L * 1024L, ProcessMemory.pluginBudgetFor(8192L));
        // Unknown device memory falls back to a conservative fixed budget.
        assertTrue(ProcessMemory.pluginBudgetFor(0L) > 0L);
    }

    @Test public void trimLevelNamesAreReadable() {
        assertEquals("RUNNING_CRITICAL", SessionMarker.trimLevelName(
                android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL));
        assertEquals("UI_HIDDEN", SessionMarker.trimLevelName(
                android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN));
        assertTrue(SessionMarker.trimLevelName(1234).contains("1234"));
    }

    @Test public void sampleReportsShareOfDeviceMemory() {
        SessionMarker.Sample sample = new SessionMarker.Sample();
        sample.heapUsedBytes = 5L * 1024L * 1024L;
        sample.heapMaxBytes = 100L * 1024L * 1024L;
        sample.pssTotalBytes = 200L * 1024L * 1024L;
        sample.totalMemoryBytes = 1000L * 1024L * 1024L;
        assertEquals(5, sample.heapPercent());
        assertEquals(20, sample.pssPercentOfDevice());
    }
}
