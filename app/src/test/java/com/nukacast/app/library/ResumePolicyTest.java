package com.nukacast.app.library;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * When an episode continues where it was left and when it starts fresh.
 *
 * <p>The rule matters both ways: resuming too eagerly drops the viewer in the middle of something they
 * barely started, and not resuming is the bug this was written for (opening a series again restarted the
 * episode from the beginning unless the viewer went through 片库).
 */
public class ResumePolicyTest {

    @Test public void aFewSecondsInDoesNotCount() {
        assertEquals(0, ResumePolicy.resumeFrom(0, 40 * 60 * 1000));
        assertEquals(0, ResumePolicy.resumeFrom(25_000, 40 * 60 * 1000));
        // Exactly at the line it is still "not really started".
        assertEquals(0, ResumePolicy.resumeFrom(ResumePolicy.MIN_RESUME_MS - 1, 40 * 60 * 1000));
    }

    @Test public void aRealWayInContinues() {
        assertEquals(31_000, ResumePolicy.resumeFrom(31_000, 40 * 60 * 1000));
        assertEquals(20 * 60 * 1000, ResumePolicy.resumeFrom(20 * 60 * 1000, 40 * 60 * 1000));
    }

    @Test public void anEpisodeThatIsNearlyOverStartsAgain() {
        // 39:45 of a 40-minute episode is the credits, not something to continue into.
        assertEquals(0, ResumePolicy.resumeFrom(39 * 60 * 1000 + 45_000, 40 * 60 * 1000));
        assertEquals(0, ResumePolicy.resumeFrom(40 * 60 * 1000, 40 * 60 * 1000));
        // A minute from the end is still worth continuing.
        assertEquals(39 * 60 * 1000, ResumePolicy.resumeFrom(39 * 60 * 1000, 40 * 60 * 1000));
    }

    @Test public void anUnknownLengthStillContinues() {
        // Live-ish or unmeasured media reports no duration; the remembered position is all there is.
        assertEquals(5 * 60 * 1000, ResumePolicy.resumeFrom(5 * 60 * 1000, 0));
        assertEquals(0, ResumePolicy.resumeFrom(10_000, 0));
    }

    @Test public void theNoticeReadsAsAClock() {
        assertEquals("0:31", ResumePolicy.clock(31_000));
        assertEquals("12:34", ResumePolicy.clock(12 * 60 * 1000 + 34_000));
        assertEquals("1:02:03", ResumePolicy.clock(3600_000 + 2 * 60 * 1000 + 3000));
        assertEquals("0:00", ResumePolicy.clock(-5000));
    }
}
