package com.nukacast.app.tvbox;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Initials must be computed by the device runtime, not only by the JVM that runs unit tests.
 *
 * <p>The conversion goes through {@code String.getBytes("GB2312")}; if a platform build lacks that
 * charset the initials silently come back empty and initials search stops working without any error.
 * This test runs on the device (including API 19) so that failure cannot hide.
 */
@RunWith(AndroidJUnit4.class)
public final class PinyinInitialsDeviceTest {
    @Test
    public void initialsWorkOnThisRuntime() {
        assertEquals("lldq", PinyinInitials.of("流浪地球"));
        assertEquals("qyn", PinyinInitials.of("庆余年"));
        assertTrue(PinyinInitials.isInitialQuery("LLDQ"));
        assertTrue(PinyinInitials.matches("流浪地球2", "lldq"));
    }

    @Test
    public void titleIndexFindsTitlesOnThisRuntime() {
        TitleIndex index = new TitleIndex();
        index.add("流浪地球2：再次冒险");
        index.add("流浪地球");
        assertEquals("流浪地球", index.match("LLDQ", 3).get(0));
    }
}
