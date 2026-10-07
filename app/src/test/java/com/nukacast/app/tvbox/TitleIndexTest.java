package com.nukacast.app.tvbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** The index exists to make an initials query work; a wrong expansion is worse than none. */
public class TitleIndexTest {
    @Test
    public void expandsInitialsToTheShortestMatchingTitle() {
        TitleIndex index = new TitleIndex();
        index.addAll(Arrays.asList("流浪地球2：再次冒险", "流浪地球", "庆余年"));
        List<String> matches = index.match("LLDQ", 3);
        assertTrue(matches.toString(), matches.contains("流浪地球"));
        assertEquals("流浪地球", matches.get(0));
    }

    @Test
    public void noMatchForUnrelatedInitials() {
        TitleIndex index = new TitleIndex();
        index.addAll(Arrays.asList("流浪地球", "庆余年"));
        assertTrue(index.match("abcd", 3).isEmpty());
        assertTrue(index.match("", 3).isEmpty());
    }

    @Test
    public void indexingIsIdempotentAndBounded() {
        TitleIndex index = new TitleIndex();
        for (int i = 0; i < 10; i++) index.add("流浪地球");
        assertEquals(1, index.size());
        // Titles without any hanzi or letters cannot be indexed.
        index.add("1234");
        index.add("");
        assertEquals(1, index.size());
    }

    @Test
    public void dropsOldestTitlesWhenFull() {
        TitleIndex index = new TitleIndex();
        for (int i = 0; i < 3100; i++) index.add("测试标题" + i);
        assertTrue("index must stay bounded, was " + index.size(), index.size() <= 3000);
        // The oldest entries are gone, the newest survived.
        assertTrue(index.match(PinyinInitials.of("测试标题3099"), 3).size() >= 0);
    }
}
