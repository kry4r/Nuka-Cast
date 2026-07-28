package com.nukacast.app.security;

import android.annotation.SuppressLint;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.SecureRandom;

/** Reads the Linux kernel RNG directly, including on Android 4.2/4.3. */
final class SecureEntropy {
    private static final String URANDOM = "/dev/urandom";

    private SecureEntropy() {}

    static void nextBytes(byte[] output) {
        if (output == null) throw new IllegalArgumentException("随机数据缓冲区不能为空");
        try (DataInputStream input = new DataInputStream(new FileInputStream(URANDOM))) {
            input.readFully(output);
        } catch (IOException unavailable) {
            fallback(output);
        }
    }

    static int nextInt(int bound) {
        if (bound <= 0) throw new IllegalArgumentException("随机数上限必须大于零");
        byte[] bytes = new byte[4];
        long range = 1L << 32;
        long limit = range - range % bound;
        long value;
        do {
            nextBytes(bytes);
            value = ((long) (bytes[0] & 0xff) << 24)
                    | ((long) (bytes[1] & 0xff) << 16)
                    | ((long) (bytes[2] & 0xff) << 8)
                    | (long) (bytes[3] & 0xff);
        } while (value >= limit);
        return (int) (value % bound);
    }

    @SuppressLint("TrulyRandom")
    private static void fallback(byte[] output) {
        // /dev/urandom is present on Android; this fallback covers unusual JVM environments.
        new SecureRandom().nextBytes(output);
    }
}
