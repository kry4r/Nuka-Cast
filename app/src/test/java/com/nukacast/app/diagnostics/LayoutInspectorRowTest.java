package com.nukacast.app.diagnostics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The "one row does not fit" rule.
 *
 * <p>Found on the device: three buttons added to the playback band made the third one stick out and get
 * clipped, while the layout report said there were no problems — the row fit the screen, and the button
 * was only cut on the sides, so neither the screen test nor the clip test could see it.
 */
public class LayoutInspectorRowTest {

    @Test public void aRowThatFitsIsNotReported() {
        assertFalse(LayoutInspector.rowOverflows(900, 900));
        assertFalse(LayoutInspector.rowOverflows(897, 900));
        // An empty or unmeasured row is not a problem.
        assertFalse(LayoutInspector.rowOverflows(0, 900));
        assertFalse(LayoutInspector.rowOverflows(900, 0));
    }

    @Test public void aRowThatDoesNotFitIsReported() {
        // Three buttons where there is room for two and a half.
        assertTrue(LayoutInspector.rowOverflows(1100, 900));
        assertTrue(LayoutInspector.rowOverflows(905, 900));
    }
}
