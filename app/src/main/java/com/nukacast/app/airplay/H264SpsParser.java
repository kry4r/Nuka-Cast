package com.nukacast.app.airplay;

import java.io.ByteArrayOutputStream;

final class H264SpsParser {
    static final class Dimensions {
        final int width;
        final int height;

        Dimensions(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    private H264SpsParser() {}

    static Dimensions parse(byte[] annexB) {
        if (annexB == null || annexB.length < 5) {
            throw new IllegalArgumentException("SPS is empty");
        }
        int offset = nalOffset(annexB);
        if (offset >= annexB.length || (annexB[offset] & 0x1f) != 7) {
            throw new IllegalArgumentException("NAL is not an SPS");
        }
        byte[] rbsp = unescape(annexB, offset + 1);
        Bits bits = new Bits(rbsp);
        int profileIdc = bits.readBits(8);
        bits.skip(8); // constraint flags
        bits.skip(8); // level_idc
        bits.readUnsignedExpGolomb(); // seq_parameter_set_id

        int chromaFormatIdc = 1;
        boolean separateColourPlane = false;
        if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122
                || profileIdc == 244 || profileIdc == 44 || profileIdc == 83
                || profileIdc == 86 || profileIdc == 118 || profileIdc == 128
                || profileIdc == 138 || profileIdc == 139 || profileIdc == 134
                || profileIdc == 135) {
            chromaFormatIdc = bits.readUnsignedExpGolomb();
            if (chromaFormatIdc == 3) separateColourPlane = bits.readBit() == 1;
            bits.readUnsignedExpGolomb(); // bit_depth_luma_minus8
            bits.readUnsignedExpGolomb(); // bit_depth_chroma_minus8
            bits.skip(1); // qpprime_y_zero_transform_bypass_flag
            if (bits.readBit() == 1) {
                int scalingLists = chromaFormatIdc == 3 ? 12 : 8;
                for (int i = 0; i < scalingLists; i++) {
                    if (bits.readBit() == 1) skipScalingList(bits, i < 6 ? 16 : 64);
                }
            }
        }

        bits.readUnsignedExpGolomb(); // log2_max_frame_num_minus4
        int picOrderCntType = bits.readUnsignedExpGolomb();
        if (picOrderCntType == 0) {
            bits.readUnsignedExpGolomb();
        } else if (picOrderCntType == 1) {
            bits.skip(1);
            bits.readSignedExpGolomb();
            bits.readSignedExpGolomb();
            int cycle = bits.readUnsignedExpGolomb();
            for (int i = 0; i < cycle; i++) bits.readSignedExpGolomb();
        }
        bits.readUnsignedExpGolomb(); // max_num_ref_frames
        bits.skip(1); // gaps_in_frame_num_value_allowed_flag
        int widthInMbs = bits.readUnsignedExpGolomb() + 1;
        int heightInMapUnits = bits.readUnsignedExpGolomb() + 1;
        int frameMbsOnly = bits.readBit();
        if (frameMbsOnly == 0) bits.skip(1);
        bits.skip(1); // direct_8x8_inference_flag

        int cropLeft = 0;
        int cropRight = 0;
        int cropTop = 0;
        int cropBottom = 0;
        if (bits.readBit() == 1) {
            cropLeft = bits.readUnsignedExpGolomb();
            cropRight = bits.readUnsignedExpGolomb();
            cropTop = bits.readUnsignedExpGolomb();
            cropBottom = bits.readUnsignedExpGolomb();
        }

        int chromaArrayType = separateColourPlane ? 0 : chromaFormatIdc;
        int cropUnitX;
        int cropUnitY;
        if (chromaArrayType == 0) {
            cropUnitX = 1;
            cropUnitY = 2 - frameMbsOnly;
        } else {
            int subWidth = chromaArrayType == 3 ? 1 : 2;
            int subHeight = chromaArrayType == 1 ? 2 : 1;
            cropUnitX = subWidth;
            cropUnitY = subHeight * (2 - frameMbsOnly);
        }
        int width = widthInMbs * 16 - cropUnitX * (cropLeft + cropRight);
        int height = heightInMapUnits * 16 * (2 - frameMbsOnly)
                - cropUnitY * (cropTop + cropBottom);
        if (width < 16 || height < 16 || width > 8192 || height > 8192) {
            throw new IllegalArgumentException("Invalid SPS dimensions " + width + "x" + height);
        }
        return new Dimensions(width, height);
    }

    private static int nalOffset(byte[] value) {
        if (value.length >= 4 && value[0] == 0 && value[1] == 0
                && value[2] == 0 && value[3] == 1) return 4;
        if (value.length >= 3 && value[0] == 0 && value[1] == 0 && value[2] == 1) return 3;
        return 0;
    }

    private static byte[] unescape(byte[] value, int offset) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(value.length - offset);
        int zeroCount = 0;
        for (int i = offset; i < value.length; i++) {
            int current = value[i] & 0xff;
            if (zeroCount >= 2 && current == 3) {
                zeroCount = 0;
                continue;
            }
            output.write(current);
            zeroCount = current == 0 ? zeroCount + 1 : 0;
        }
        return output.toByteArray();
    }

    private static void skipScalingList(Bits bits, int size) {
        int lastScale = 8;
        int nextScale = 8;
        for (int i = 0; i < size; i++) {
            if (nextScale != 0) {
                nextScale = (lastScale + bits.readSignedExpGolomb() + 256) % 256;
            }
            if (nextScale != 0) lastScale = nextScale;
        }
    }

    private static final class Bits {
        private final byte[] value;
        private int bit;

        Bits(byte[] value) { this.value = value; }

        int readBit() {
            if (bit >= value.length * 8) throw new IllegalArgumentException("Truncated SPS");
            int result = (value[bit / 8] >> (7 - bit % 8)) & 1;
            bit++;
            return result;
        }

        int readBits(int count) {
            int result = 0;
            for (int i = 0; i < count; i++) result = (result << 1) | readBit();
            return result;
        }

        void skip(int count) { readBits(count); }

        int readUnsignedExpGolomb() {
            int leadingZeros = 0;
            while (readBit() == 0) {
                leadingZeros++;
                if (leadingZeros > 30) throw new IllegalArgumentException("Invalid Exp-Golomb value");
            }
            return (1 << leadingZeros) - 1 + (leadingZeros == 0 ? 0 : readBits(leadingZeros));
        }

        int readSignedExpGolomb() {
            int code = readUnsignedExpGolomb();
            int magnitude = (code + 1) / 2;
            return (code & 1) == 0 ? -magnitude : magnitude;
        }
    }
}
