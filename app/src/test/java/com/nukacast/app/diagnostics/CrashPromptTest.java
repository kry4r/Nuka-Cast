package com.nukacast.app.diagnostics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CrashPromptTest {

    private static final String REPORT = "Thread: main\njava.lang.NullPointerException\n"
            + "\tat com.nukacast.app.MainActivity.onCreate(MainActivity.java:268)";

    @Test
    public void anEmptyReportIsNeverPrompted() {
        assertEquals("", CrashPrompt.signature(""));
        assertEquals("", CrashPrompt.signature(null));
        assertEquals("", CrashPrompt.signature("   \n "));
        assertFalse(CrashPrompt.shouldPrompt("", ""));
    }

    @Test
    public void aNewCrashIsPromptedOnce() {
        String signature = CrashPrompt.signature(REPORT);
        assertTrue(CrashPrompt.shouldPrompt(REPORT, ""));
        assertFalse("the same crash must not pop up again",
                CrashPrompt.shouldPrompt(REPORT, signature));
    }

    @Test
    public void aDifferentCrashIsPromptedAgain() {
        String other = REPORT.replace("NullPointerException", "IllegalStateException");
        assertTrue(CrashPrompt.shouldPrompt(other, CrashPrompt.signature(REPORT)));
    }

    @Test
    public void theSignatureIsStableAndShort() {
        assertEquals(CrashPrompt.signature(REPORT), CrashPrompt.signature(REPORT));
        assertTrue(CrashPrompt.signature(REPORT).length() <= 16);
        // A change in the stack must change the signature, or a fresh crash would be swallowed.
        assertFalse(CrashPrompt.signature(REPORT).equals(CrashPrompt.signature(REPORT + "\nmore")));
    }
}
