package com.nukacast.app.tvbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Initials must be right for real titles, since they are what a user types into search. */
public class PinyinInitialsTest {
    @Test
    public void initialsOfCommonTitles() {
        assertEquals("lldq", PinyinInitials.of("流浪地球"));
        assertEquals("lldq", PinyinInitials.of("流浪地球2"));
        assertEquals("qyn", PinyinInitials.of("庆余年"));
        assertEquals("cjh", PinyinInitials.of("长津湖"));
        assertEquals("mzwr", PinyinInitials.of("目中无人"));
        assertEquals("wdfq", PinyinInitials.of("我的父亲"));
    }

    @Test
    public void keepsLatinAndDropsDecoration() {
        assertEquals("wm", PinyinInitials.of("《无名》"));
        assertEquals("ipzz", PinyinInitials.of("IP猪猪"));
        assertEquals("", PinyinInitials.of("1234"));
    }

    @Test
    public void onlyPureLettersCountAsInitialQuery() {
        assertTrue(PinyinInitials.isInitialQuery("LLDQ"));
        assertTrue(PinyinInitials.isInitialQuery("lldq"));
        assertFalse(PinyinInitials.isInitialQuery("流浪地球"));
        assertFalse(PinyinInitials.isInitialQuery("L"));
        assertFalse(PinyinInitials.isInitialQuery("流浪LLDQ"));
        assertFalse(PinyinInitials.isInitialQuery("LL DQ"));
    }

    @Test
    public void matchesTitlesByPrefix() {
        assertTrue(PinyinInitials.matches("流浪地球2", "lldq"));
        assertTrue(PinyinInitials.matches("流浪地球", "LLDQ"));
        assertFalse(PinyinInitials.matches("庆余年", "lldq"));
        assertFalse(PinyinInitials.matches("", "lldq"));
    }
}
