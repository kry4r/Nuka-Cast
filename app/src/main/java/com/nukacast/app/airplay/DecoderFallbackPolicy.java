package com.nukacast.app.airplay;

import java.util.Locale;

final class DecoderFallbackPolicy {
    private final long minimumInputs;
    private final long minimumWaitMs;

    DecoderFallbackPolicy(long minimumInputs, long minimumWaitMs) {
        this.minimumInputs = minimumInputs;
        this.minimumWaitMs = minimumWaitMs;
    }

    boolean shouldFallback(String decoderName, long inputs, long outputs, long elapsedMs) {
        return !isSoftware(decoderName)
                && inputs >= minimumInputs
                && outputs == 0
                && elapsedMs >= minimumWaitMs;
    }

    private static boolean isSoftware(String decoderName) {
        String name = decoderName == null ? "" : decoderName.toLowerCase(Locale.US);
        return name.isEmpty() || name.startsWith("omx.google.")
                || name.contains("software") || name.contains("ffmpeg");
    }
}
