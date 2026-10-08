package com.nukacast.app.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The rules behind 跳过片头/片尾.
 *
 * <p>Both were found on the device rather than here: a 40-second fixture with a 60-second intro skip
 * reported "ended" the moment it started, and a media that continues to be watched must not be thrown
 * back to its opening.
 */
public class PlaybackSkipTest {

    @Test public void theOpeningIsSkippedOncePerEpisode() {
        // A first start jumps past the opening...
        assertEquals(60000, PlayerController.introStartMs(60, 0, true));
        // ...a resume keeps where the viewer stopped...
        assertEquals(12 * 60 * 1000, PlayerController.introStartMs(60, 12 * 60 * 1000, true));
        // ...and a retry inside the same episode does not jump again.
        assertEquals(0, PlayerController.introStartMs(60, 0, false));
    }

    @Test public void noSkipWhenTheSettingIsOff() {
        assertEquals(0, PlayerController.introStartMs(0, 0, true));
        assertEquals(0, PlayerController.introStartMs(0, 0, false));
    }

    @Test public void aSkipLongerThanHalfTheMediaIsTakenBack() {
        // 60 seconds of a 40-second clip, or of a 90-second short drama, is the episode, not the opening.
        assertFalse(PlayerController.introSkipFits(60, 40_000));
        assertFalse(PlayerController.introSkipFits(60, 90_000));
        // Half exactly still counts as an opening. A 40-minute episode always fits.
        assertTrue(PlayerController.introSkipFits(45, 90_000));
        assertTrue(PlayerController.introSkipFits(60, 40 * 60 * 1000));
        // An unknown duration is not a reason to change what is playing.
        assertTrue(PlayerController.introSkipFits(60, 0));
    }

    @Test public void steppingButtonsWalkThroughTheirValuesAndWrap() {
        assertEquals(30, PlaybackSettings.nextStep(PlaybackSettings.INTRO_STEPS, 0));
        assertEquals(120, PlaybackSettings.nextStep(PlaybackSettings.INTRO_STEPS, 90));
        assertEquals(0, PlaybackSettings.nextStep(PlaybackSettings.INTRO_STEPS, 120));
        assertEquals(0, PlaybackSettings.nextStep(PlaybackSettings.OUTRO_STEPS, 90));
        // An unexpected stored value starts the walk again rather than getting stuck.
        assertEquals(0, PlaybackSettings.nextStep(PlaybackSettings.INTRO_STEPS, 17));
    }

    @Test public void labelsSayWhatWillHappen() {
        assertEquals("关", PlaybackSettings.skipLabel(0));
        assertEquals("跳过 60 秒", PlaybackSettings.skipLabel(60));
        assertEquals("关", PlaybackSettings.skipLabel(-5));
    }
}
