package com.nukacast.app.player;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The 音轨 / 字幕 entries cycle through what the media offers, then switch off. */
public class PlayerTrackMenuTest {

    private static List<String> labels(String... values) {
        return Arrays.asList(values);
    }

    @Test
    public void selectedNameReadsTheCheckMarkedLabel() {
        assertEquals("中文 · WebVTT",
                PlayerTrackMenu.selectedName(labels("中文 · WebVTT ✓", "English · WebVTT")));
        assertEquals("英语 · AAC",
                PlayerTrackMenu.selectedName(labels("中文 · AAC", "英语 · AAC ✓")));
        // Nothing selected means subtitles are off, which is what the menu should say.
        assertEquals("关", PlayerTrackMenu.selectedName(labels("中文 · WebVTT", "English · WebVTT")));
        assertEquals("关", PlayerTrackMenu.selectedName(Collections.<String>emptyList()));
        assertEquals("关", PlayerTrackMenu.selectedName(null));
    }

    @Test
    public void theCycleWalksEveryTrackThenTurnsOff() {
        List<String> two = labels("中文 · WebVTT ✓", "English · WebVTT");
        assertEquals(1, PlayerTrackMenu.nextIndex(two));
        assertEquals(-1, PlayerTrackMenu.nextIndex(labels("中文 · WebVTT", "English · WebVTT ✓")));
        // A single track: on, then off.
        assertEquals(0, PlayerTrackMenu.nextIndex(labels("中文 · WebVTT")));
        assertEquals(-1, PlayerTrackMenu.nextIndex(labels("中文 · WebVTT ✓")));
    }

    @Test
    public void theFirstPressTurnsSubtitlesOnWhenTheyAreOff() {
        // Coming out of "off" must not immediately jump to the second track, or the first one would be
        // unreachable without a second press.
        assertEquals(0, PlayerTrackMenu.nextIndex(labels("中文 · WebVTT", "English · WebVTT")));
        assertEquals(-1, PlayerTrackMenu.nextIndex(Collections.<String>emptyList()));
    }
}
