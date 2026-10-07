package com.nukacast.app.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Playback defaults are what a viewer gets before touching anything, and a corrupted stored value must
 * never leave the player asking for a quality nobody chose.
 */
public class PlaybackSettingsTest {
    @Test
    public void defaultsMatchWhatAViewerExpects() {
        assertTrue("一集播完应自动进入下一集", PlaybackSettings.DEFAULT_AUTO_NEXT);
        assertFalse("默认不强制软件解码", PlaybackSettings.DEFAULT_PREFER_SOFTWARE);
        assertEquals("auto", PlaybackSettings.DEFAULT_QUALITY);
    }

    @Test
    public void qualityValuesAreNormalised() {
        assertEquals("highest", PlaybackSettings.normaliseQuality("highest"));
        assertEquals("lowest", PlaybackSettings.normaliseQuality("lowest"));
        // Anything else (including a value written by an older build) means automatic.
        assertEquals("auto", PlaybackSettings.normaliseQuality("auto"));
        assertEquals("auto", PlaybackSettings.normaliseQuality(""));
        assertEquals("auto", PlaybackSettings.normaliseQuality(null));
        assertEquals("auto", PlaybackSettings.normaliseQuality("1080p"));
    }

    @Test
    public void qualityCyclesThroughTheThreeChoices() {
        assertEquals("最高", PlaybackSettings.qualityLabel("highest"));
        assertEquals("最低", PlaybackSettings.qualityLabel("lowest"));
        assertEquals("自动", PlaybackSettings.qualityLabel("auto"));
        assertEquals("自动", PlaybackSettings.qualityLabel("nonsense"));
    }
}
