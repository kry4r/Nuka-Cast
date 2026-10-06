package com.nukacast.app.airplay;

import java.util.Locale;

/**
 * Decides when a hardware H.264 decoder that accepts input but produces no picture must fall back
 * to software.
 *
 * <p>The 24-frame threshold is a fast path only: a still AirPlay mirror can submit a handful of
 * frames and then stop, so a pure frame-count gate would wait forever. Any single accepted input
 * that stays without output past {@code lowInputsWaitMs} is therefore also enough evidence.
 */
final class DecoderFallbackPolicy {
    private final long minimumInputs;
    private final long minimumWaitMs;
    private final long lowInputsWaitMs;

    DecoderFallbackPolicy(long minimumInputs, long minimumWaitMs) {
        this(minimumInputs, minimumWaitMs, Math.max(minimumWaitMs, minimumWaitMs * 2L));
    }

    DecoderFallbackPolicy(long minimumInputs, long minimumWaitMs, long lowInputsWaitMs) {
        this.minimumInputs = minimumInputs;
        this.minimumWaitMs = minimumWaitMs;
        this.lowInputsWaitMs = lowInputsWaitMs;
    }

    boolean shouldFallback(String decoderName, long inputs, long outputs, long elapsedMs) {
        if (isSoftware(decoderName) || outputs > 0) return false;
        if (inputs >= minimumInputs && elapsedMs >= minimumWaitMs) return true;
        return inputs >= 1 && elapsedMs >= lowInputsWaitMs;
    }

    private static boolean isSoftware(String decoderName) {
        String name = decoderName == null ? "" : decoderName.toLowerCase(Locale.US);
        return name.isEmpty() || name.startsWith("omx.google.") || name.startsWith("c2.android.")
                || name.contains("software") || name.contains("ffmpeg")
                || name.contains(".sw.") || name.contains(".soft.");
    }
}
