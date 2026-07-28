package com.nukacast.app.airplay;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class H264VideoRendererTest {
    @Test
    public void extractsSpsAndPpsFromAnnexBConfig() {
        byte[] config = new byte[] {
                0, 0, 0, 1, 0x67, 0x42, 0, 0x1f,
                0, 0, 1, 0x68, (byte) 0xce, 0x3c, (byte) 0x80
        };

        assertArrayEquals(new byte[] {0, 0, 0, 1, 0x67, 0x42, 0, 0x1f},
                H264VideoRenderer.parameterSet(config, 7));
        assertArrayEquals(new byte[] {0, 0, 1, 0x68, (byte) 0xce, 0x3c, (byte) 0x80},
                H264VideoRenderer.parameterSet(config, 8));
        assertNull(H264VideoRenderer.parameterSet(config, 5));
    }

    @Test
    public void identifiesIdrAcrossThreeAndFourByteStartCodes() {
        assertTrue(H264VideoRenderer.containsNalType(
                new byte[] {0, 0, 1, 0x65, 1, 2, 3}, 5));
        assertTrue(H264VideoRenderer.containsNalType(
                new byte[] {0, 0, 0, 1, 0x41, 1, 0, 0, 1, 0x65, 2}, 5));
        assertFalse(H264VideoRenderer.containsNalType(
                new byte[] {0, 0, 0, 1, 0x41, 1, 2, 3}, 5));
    }
}
