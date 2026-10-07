package com.nukacast.app.player;

import com.google.android.exoplayer2.PlaybackException;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The decoder preference is what stops a repeat of "播放后直接闪退到主页面" on a TV whose hardware
 * decoder accepts a stream and then fails inside MediaCodec.
 */
public class DecoderPreferenceTest {
    @Test
    public void onlyDecodingFailuresTriggerTheSoftwareRetry() {
        assertTrue(PlayerController.isDecoderFailureCode(
                PlaybackException.ERROR_CODE_DECODING_FAILED));
        assertTrue(PlayerController.isDecoderFailureCode(
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED));
        assertTrue(PlayerController.isDecoderFailureCode(
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES));
        assertTrue(PlayerController.isDecoderFailureCode(
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED));
        assertTrue(PlayerController.isDecoderFailureCode(
                PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED));
        // A dead URL, a timeout or an unsupported container must not replay anything: the decoder is
        // not the problem and a retry would only hide the real cause.
        assertFalse(PlayerController.isDecoderFailureCode(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS));
        assertFalse(PlayerController.isDecoderFailureCode(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT));
        assertFalse(PlayerController.isDecoderFailureCode(
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED));
    }

    @Test
    public void softwareCodecNamingFollowsTheAndroidConvention() {
        // Only the naming rule matters here: it decides which codecs are ordered first.
        assertTrue(DecoderPreference.isSoftwareName("OMX.google.h264.decoder"));
        assertTrue(DecoderPreference.isSoftwareName("OMX.google.vp9.decoder"));
        assertFalse(DecoderPreference.isSoftwareName("OMX.hisi.video.decoder.avc"));
        assertFalse(DecoderPreference.isSoftwareName(null));
    }

}
