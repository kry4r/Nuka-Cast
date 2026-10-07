package com.nukacast.app.live;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RecentChannelsTest {

    @Test
    public void theWatchedChannelGoesToTheFront() {
        String stored = RecentChannels.update("", "cctv1", RecentChannels.LIMIT);
        stored = RecentChannels.update(stored, "cctv5", RecentChannels.LIMIT);
        assertEquals(Arrays.asList("cctv5", "cctv1"), RecentChannels.parse(stored));
    }

    @Test
    public void watchingAgainMovesTheChannelInsteadOfDuplicating() {
        String stored = "cctv5\ncctv1\nhnws";
        stored = RecentChannels.update(stored, "hnws", RecentChannels.LIMIT);
        assertEquals(Arrays.asList("hnws", "cctv5", "cctv1"), RecentChannels.parse(stored));
    }

    @Test
    public void theListIsCapped() {
        String stored = "";
        for (int i = 0; i < 30; i++) {
            stored = RecentChannels.update(stored, "channel-" + i, 5);
        }
        assertEquals(5, RecentChannels.parse(stored).size());
        assertEquals("channel-29", RecentChannels.parse(stored).get(0));
    }

    @Test
    public void junkIsIgnored() {
        assertTrue(RecentChannels.parse(null).isEmpty());
        assertTrue(RecentChannels.parse("").isEmpty());
        // Empty lines and duplicates cannot sneak in through the stored text.
        assertEquals(Arrays.asList("a", "b"), RecentChannels.parse("a\n\nb\na\n"));
    }

    @Test
    public void aLimitOfOneKeepsTheLatest() {
        String stored = RecentChannels.update("a\nb", "c", 1);
        assertEquals(Arrays.asList("c"), RecentChannels.parse(stored));
    }
}
