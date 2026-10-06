package com.nukacast.app.airplay;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DecoderFallbackPolicyTest {
    private final DecoderFallbackPolicy policy = new DecoderFallbackPolicy(24, 1500L);

    @Test
    public void waitsForEnoughInputAndTime() {
        assertFalse(policy.shouldFallback("OMX.hisi.video.decoder.avc", 23, 0, 2000L));
        assertFalse(policy.shouldFallback("OMX.hisi.video.decoder.avc", 24, 0, 1499L));
    }

    @Test
    public void fallsBackWhenHardwareAcceptsInputButProducesNothing() {
        assertTrue(policy.shouldFallback("OMX.hisi.video.decoder.avc", 24, 0, 1500L));
    }

    @Test
    public void fallsBackFromASingleInputAfterTheLowFrameDeadline() {
        // A still mirror may submit one frame and stop; 24 frames must not be the only gate.
        assertFalse(policy.shouldFallback("OMX.hisi.video.decoder.avc", 1, 0, 2999L));
        assertTrue(policy.shouldFallback("OMX.hisi.video.decoder.avc", 1, 0, 3000L));
        assertTrue(policy.shouldFallback("OMX.hisi.video.decoder.avc", 7, 0, 4100L));
    }

    @Test
    public void neverFallsBackWithoutAcceptedInput() {
        assertFalse(policy.shouldFallback("OMX.hisi.video.decoder.avc", 0, 0, 60000L));
    }

    @Test
    public void keepsHardwareAfterAnyDecodedOutput() {
        assertFalse(policy.shouldFallback("OMX.hisi.video.decoder.avc", 48, 1, 3000L));
        assertFalse(policy.shouldFallback("OMX.hisi.video.decoder.avc", 1, 1, 60000L));
    }

    @Test
    public void neverFallsBackFromGoogleSoftwareDecoder() {
        assertFalse(policy.shouldFallback("OMX.google.h264.decoder", 48, 0, 3000L));
    }

    @Test
    public void treatsUnknownAndSoftwareNamesAsSoftware() {
        assertFalse(policy.shouldFallback("", 48, 0, 3000L));
        assertFalse(policy.shouldFallback("c2.android.avc.decoder", 48, 0, 3000L));
    }
}
