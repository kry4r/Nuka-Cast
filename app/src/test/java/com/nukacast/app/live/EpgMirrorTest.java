package com.nukacast.app.live;

import com.nukacast.app.live.model.EpgSchedule;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Rules the live page depends on when a guide service misbehaves.
 *
 * <p>Measured: {@code epg.51zmt.top:8000} answered with an empty body (HTTP 200, 0 bytes) for every
 * channel, which used to be indistinguishable from "this playlist has no guide for that channel".
 */
public class EpgMirrorTest {

    @Test
    public void aFailedFetchIsNotTheSameAsNoListing() {
        EpgSchedule failed = new EpgSchedule();
        failed.error = "节目单服务暂时不可用";
        assertTrue(failed.programs.isEmpty());
        assertTrue(!failed.error.isEmpty());

        EpgSchedule noListing = new EpgSchedule();
        assertTrue(noListing.programs.isEmpty());
        assertEquals("", noListing.error);
    }
}
