package com.nukacast.app.tvbox;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * An initials query expands to the canonical title so that every site carrying any 流浪地球 entry
 * matches, instead of the one long sequel the index happened to see first.
 */
public class CanonicalTitleTest {
    @Test
    public void trimsSubtitlesAndSeasons() {
        assertEquals("流浪地球", SearchEngine.canonicalTitle("流浪地球之大夏战狼"));
        assertEquals("流浪地球", SearchEngine.canonicalTitle("流浪地球2：再次冒险"));
        assertEquals("流浪地球", SearchEngine.canonicalTitle("流浪地球（2019）"));
        assertEquals("庆余年", SearchEngine.canonicalTitle("庆余年 第二季"));
        assertEquals("狂飙", SearchEngine.canonicalTitle("狂飙"));
    }

    @Test
    public void keepsShortTitlesIntact() {
        // Cutting at the first marker would leave one character, which is not a usable query.
        assertEquals("之乎者也", SearchEngine.canonicalTitle("之乎者也"));
        assertEquals("", SearchEngine.canonicalTitle(null));
    }
}
