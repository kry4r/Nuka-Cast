package com.nukacast.app.airplay;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public final class H264VideoRendererTest {
    @Test
    public void api19UsesCachedLegacyInputBuffers() {
        assertTrue(H264VideoRenderer.usesLegacyInputBuffers(19));
        assertFalse(H264VideoRenderer.usesLegacyInputBuffers(21));
    }

    @Test
    public void selectsCachedInputBufferWithoutRefreshingCodecBuffers() {
        ByteBuffer second = ByteBuffer.allocate(8);

        assertSame(second, H264VideoRenderer.cachedInputBuffer(
                new ByteBuffer[] {ByteBuffer.allocate(4), second}, 1));
    }

    @Test
    public void prefersGoogleWhenFallingBackAcrossSoftwareDecoders() {
        ArrayList<String> decoders = new ArrayList<String>(Arrays.asList(
                "OMX.vendor.h264.sw.decoder", "OMX.google.h264.decoder"));

        H264VideoRenderer.moveGoogleDecoderFirst(decoders);

        assertEquals("OMX.google.h264.decoder", decoders.get(0));
        assertEquals("OMX.vendor.h264.sw.decoder", decoders.get(1));
        assertTrue(H264VideoRenderer.isSoftwareCodec("OMX.vendor.h264.sw.decoder"));
    }

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

    @Test
    public void retainsLatestAnnexBIdrForDecoderRecovery() {
        byte[] first = new byte[] {0, 0, 0, 1, 0x65, 1};
        byte[] delta = new byte[] {0, 0, 0, 1, 0x41, 2};
        byte[] latest = new byte[] {0, 0, 1, 0x65, 3};

        byte[] retained = H264VideoRenderer.retainLatestKeyFrame(null, first);
        assertArrayEquals(first, H264VideoRenderer.retainLatestKeyFrame(retained, delta));
        assertArrayEquals(latest, H264VideoRenderer.retainLatestKeyFrame(retained, latest));
    }

    @Test
    public void resetsDecoderCountersForANewAirPlaySession() {
        AtomicLong received = new AtomicLong(12);
        AtomicLong outputs = new AtomicLong(7);

        H264VideoRenderer.resetSessionCounters(received, outputs);

        assertEquals(0L, received.get());
        assertEquals(0L, outputs.get());
    }

    @Test
    public void parsesDimensionsAndFrameCroppingFromSps() {
        H264SpsParser.Dimensions hd = H264SpsParser.parse(sps(1280, 720));
        assertEquals(1280, hd.width);
        assertEquals(720, hd.height);

        H264SpsParser.Dimensions fullHd = H264SpsParser.parse(sps(1920, 1080));
        assertEquals(1920, fullHd.width);
        assertEquals(1080, fullHd.height);
    }

    private static byte[] sps(int width, int height) {
        BitWriter bits = new BitWriter();
        bits.bits(66, 8); // baseline profile
        bits.bits(0, 8);
        bits.bits(31, 8);
        bits.ue(0); // sps id
        bits.ue(0); // log2_max_frame_num_minus4
        bits.ue(0); // pic_order_cnt_type
        bits.ue(0); // log2_max_pic_order_cnt_lsb_minus4
        bits.ue(1); // max_num_ref_frames
        bits.bit(0);
        bits.ue(width / 16 - 1);
        int codedHeight = ((height + 15) / 16) * 16;
        bits.ue(codedHeight / 16 - 1);
        bits.bit(1); // frame_mbs_only
        bits.bit(1); // direct_8x8_inference
        int cropBottom = (codedHeight - height) / 2; // 4:2:0 crop unit is two pixels
        bits.bit(cropBottom > 0 ? 1 : 0);
        if (cropBottom > 0) {
            bits.ue(0);
            bits.ue(0);
            bits.ue(0);
            bits.ue(cropBottom);
        }
        bits.bit(0); // vui_parameters_present
        bits.bit(1); // rbsp stop bit
        byte[] rbsp = bits.bytes();
        byte[] result = new byte[rbsp.length + 5];
        result[3] = 1;
        result[4] = 0x67;
        System.arraycopy(rbsp, 0, result, 5, rbsp.length);
        return result;
    }

    private static final class BitWriter {
        private final java.util.ArrayList<Integer> values = new java.util.ArrayList<Integer>();

        void bit(int value) { values.add(value & 1); }

        void bits(int value, int count) {
            for (int i = count - 1; i >= 0; i--) bit(value >> i);
        }

        void ue(int value) {
            int code = value + 1;
            int width = 32 - Integer.numberOfLeadingZeros(code);
            for (int i = 1; i < width; i++) bit(0);
            bits(code, width);
        }

        byte[] bytes() {
            while (values.size() % 8 != 0) bit(0);
            byte[] result = new byte[values.size() / 8];
            for (int i = 0; i < values.size(); i++) {
                result[i / 8] |= values.get(i) << (7 - i % 8);
            }
            return result;
        }
    }
}
