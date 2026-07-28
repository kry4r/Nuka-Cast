package com.nukacast.app;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public final class SmokeTest {
    @Test
    public void testApplicationContextUsesDebugPackage() {
        assertEquals("com.nukacast.app.debug",
                InstrumentationRegistry.getInstrumentation()
                        .getTargetContext().getPackageName());
    }
}
