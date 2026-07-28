package com.nukacast.app.airplay;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class H264VideoRenderer {
    private static final int QUEUE_CAPACITY = 8;
    private final ArrayBlockingQueue<Frame> queue = new ArrayBlockingQueue<Frame>(QUEUE_CAPACITY);
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong configPackets = new AtomicLong();
    private final AtomicLong keyFrames = new AtomicLong();
    private final AtomicLong decoderInputs = new AtomicLong();
    private final AtomicLong decoderOutputs = new AtomicLong();
    private final AtomicLong decoderFormatChanges = new AtomicLong();
    private final DecoderFallbackPolicy fallbackPolicy = new DecoderFallbackPolicy(24, 1500L);
    private final Thread thread;
    private volatile boolean running = true;
    private volatile Surface surface;
    private volatile byte[] codecConfig;
    private volatile boolean decoderResetRequested;
    private volatile Frame pendingKeyFrame;
    private volatile Frame retainedKeyFrame;
    private volatile String error = "";
    private volatile String decoderName = "";
    private volatile boolean softwareFallback;
    private volatile int videoWidth;
    private volatile int videoHeight;
    private MediaCodec decoder;
    private long lastPtsUs;
    private long decoderStartedAtMs;
    private long decoderInputsAtStart;
    private long decoderOutputsAtStart;
    private boolean waitingForKeyFrame = true;

    H264VideoRenderer() {
        thread = new Thread(new Runnable() {
            @Override public void run() { decodeLoop(); }
        }, "nukacast-airplay-video");
        thread.start();
    }

    void setSurface(Surface value) {
        surface = value;
        decoderResetRequested = true;
        Frame pending = retainedKeyFrame;
        if (value != null && value.isValid() && pending != null) {
            queue.clear();
            queue.offer(pending);
        }
        thread.interrupt();
    }

    void offer(byte[] data, int type, long ptsUs) {
        received.incrementAndGet();
        if (data == null || data.length == 0) {
            dropped.incrementAndGet();
            return;
        }
        if (type == 0) {
            codecConfig = data;
            pendingKeyFrame = null;
            retainedKeyFrame = null;
            softwareFallback = false;
            configPackets.incrementAndGet();
            queue.clear();
            decoderResetRequested = true;
        }
        Frame frame = new Frame(data, type, ptsUs);
        byte[] retained = retainedKeyFrame == null ? null : retainedKeyFrame.data;
        if (type != 0 && retainLatestKeyFrame(retained, data) == data) {
            keyFrames.incrementAndGet();
            retainedKeyFrame = frame;
        }
        if (!queue.offer(frame)) {
            queue.poll();
            if (!queue.offer(frame)) dropped.incrementAndGet();
            else dropped.incrementAndGet();
        }
    }

    void flush() {
        queue.clear();
        resetSessionCounters(received, dropped, configPackets, keyFrames,
                decoderInputs, decoderOutputs, decoderFormatChanges);
        codecConfig = null;
        pendingKeyFrame = null;
        retainedKeyFrame = null;
        lastPtsUs = 0;
        waitingForKeyFrame = true;
        softwareFallback = false;
        decoderName = "";
        error = "";
        videoWidth = 0;
        videoHeight = 0;
        decoderResetRequested = true;
        thread.interrupt();
    }

    void stop() {
        running = false;
        thread.interrupt();
        try { thread.join(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        releaseDecoder();
        queue.clear();
    }

    long received() { return received.get(); }
    long dropped() { return dropped.get(); }
    String error() { return error; }
    int width() { return videoWidth; }
    int height() { return videoHeight; }
    long configPackets() { return configPackets.get(); }
    long keyFrames() { return keyFrames.get(); }
    long decoderInputs() { return decoderInputs.get(); }
    long decoderOutputs() { return decoderOutputs.get(); }
    long decoderFormatChanges() { return decoderFormatChanges.get(); }
    String decoderName() { return decoderName; }
    boolean softwareFallback() { return softwareFallback; }

    private void decodeLoop() {
        while (running) {
            try {
                if (decoderResetRequested) {
                    decoderResetRequested = false;
                    releaseDecoder();
                    waitingForKeyFrame = true;
                }
                Frame frame = queue.poll(10, TimeUnit.MILLISECONDS);
                if (frame == null) {
                    drain();
                    maybeFallback();
                    continue;
                }
                if (frame.type == 0) continue;
                if (decoder == null && !createDecoder(softwareFallback)) {
                    if (containsNalType(frame.data, 5)) pendingKeyFrame = frame;
                    dropped.incrementAndGet();
                    continue;
                }
                boolean keyFrame = containsNalType(frame.data, 5);
                if (waitingForKeyFrame && !keyFrame) {
                    dropped.incrementAndGet();
                    continue;
                }
                waitingForKeyFrame = false;
                if (keyFrame) pendingKeyFrame = null;
                queueInput(frame);
                drain();
                maybeFallback();
            } catch (InterruptedException ignored) {
                if (!running) return;
            } catch (RuntimeException error) {
                dropped.incrementAndGet();
                this.error = error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage();
                releaseDecoder();
            }
        }
    }

    private boolean createDecoder(boolean forceSoftware) {
        Surface target = surface;
        byte[] config = codecConfig;
        if (target == null || !target.isValid() || config == null) return false;
        try {
            byte[] sps = parameterSet(config, 7);
            byte[] pps = parameterSet(config, 8);
            if (sps == null || pps == null) throw new IllegalArgumentException("AirPlay SPS/PPS missing");
            H264SpsParser.Dimensions dimensions = H264SpsParser.parse(sps);
            MediaFormat format = MediaFormat.createVideoFormat(
                    "video/avc", dimensions.width, dimensions.height);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,
                    Math.max(2 * 1024 * 1024, dimensions.width * dimensions.height));
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
            String selectedName = findDecoderName(forceSoftware);
            if (selectedName == null) {
                throw new IllegalStateException(forceSoftware
                        ? "设备没有 OMX.google.h264.decoder" : "设备没有 H.264 解码器");
            }
            decoderName = selectedName;
            decoder = MediaCodec.createByCodecName(selectedName);
            decoder.configure(format, target, null, 0);
            decoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);
            decoder.start();
            videoWidth = dimensions.width;
            videoHeight = dimensions.height;
            decoderStartedAtMs = System.currentTimeMillis();
            decoderInputsAtStart = decoderInputs.get();
            decoderOutputsAtStart = decoderOutputs.get();
            error = "";
            return true;
        } catch (Exception error) {
            this.error = error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage();
            releaseDecoder();
            return false;
        }
    }

    private void queueInput(Frame frame) {
        MediaCodec active = decoder;
        if (active == null) return;
        int index = active.dequeueInputBuffer(2000);
        if (index < 0) {
            dropped.incrementAndGet();
            return;
        }
        ByteBuffer input = active.getInputBuffers()[index];
        input.clear();
        if (frame.data.length > input.remaining()) {
            active.queueInputBuffer(index, 0, 0, timestamp(frame.ptsUs), 0);
            dropped.incrementAndGet();
            return;
        }
        input.put(frame.data);
        int flags = containsNalType(frame.data, 5) ? MediaCodec.BUFFER_FLAG_SYNC_FRAME : 0;
        active.queueInputBuffer(index, 0, frame.data.length, timestamp(frame.ptsUs), flags);
        decoderInputs.incrementAndGet();
    }

    private void drain() {
        MediaCodec active = decoder;
        if (active == null) return;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int index = active.dequeueOutputBuffer(info, 0);
            if (index >= 0) {
                active.releaseOutputBuffer(index, true);
                decoderOutputs.incrementAndGet();
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                decoderFormatChanges.incrementAndGet();
            } else if (index != MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                break;
            }
        }
    }

    private void maybeFallback() {
        if (decoder == null || softwareFallback) return;
        long elapsed = Math.max(0L, System.currentTimeMillis() - decoderStartedAtMs);
        long inputs = decoderInputs.get() - decoderInputsAtStart;
        long outputs = decoderOutputs.get() - decoderOutputsAtStart;
        if (!fallbackPolicy.shouldFallback(decoderName, inputs, outputs, elapsed)) return;

        Frame recovery = retainedKeyFrame;
        softwareFallback = true;
        releaseDecoder();
        waitingForKeyFrame = true;
        if (!createDecoder(true)) return;
        if (recovery == null) {
            error = "硬解码器无输出，软件解码器正在等待 IDR";
            return;
        }
        waitingForKeyFrame = false;
        queueInput(recovery);
        drain();
    }

    private long timestamp(long candidate) {
        if (candidate <= lastPtsUs || candidate - lastPtsUs > 1000000L) {
            candidate = lastPtsUs == 0 ? System.nanoTime() / 1000L : lastPtsUs + 33333L;
        }
        lastPtsUs = candidate;
        return candidate;
    }

    private synchronized void releaseDecoder() {
        if (decoder == null) return;
        try { decoder.stop(); } catch (RuntimeException ignored) {}
        try { decoder.release(); } catch (RuntimeException ignored) {}
        decoder = null;
    }

    private static String findDecoderName(boolean software) {
        String fallback = null;
        int count = MediaCodecList.getCodecCount();
        for (int i = 0; i < count; i++) {
            MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
            if (info.isEncoder() || !supportsAvc(info)) continue;
            String name = info.getName();
            if (software) {
                if ("OMX.google.h264.decoder".equalsIgnoreCase(name)) return name;
            } else if (!isSoftwareCodec(name)) {
                return name;
            } else if (fallback == null) {
                fallback = name;
            }
        }
        return software ? null : fallback;
    }

    private static boolean supportsAvc(MediaCodecInfo info) {
        for (String type : info.getSupportedTypes()) {
            if ("video/avc".equalsIgnoreCase(type)) return true;
        }
        return false;
    }

    private static boolean isSoftwareCodec(String codecName) {
        String name = codecName == null ? "" : codecName.toLowerCase(java.util.Locale.US);
        return name.startsWith("omx.google.") || name.contains("software")
                || name.contains("ffmpeg");
    }

    static byte[] parameterSet(byte[] data, int wantedType) {
        int start = findStartCode(data, 0);
        while (start >= 0) {
            int prefix = data[start + 2] == 1 ? 3 : 4;
            int nal = start + prefix;
            int next = findStartCode(data, nal + 1);
            if (nal < data.length && (data[nal] & 0x1f) == wantedType) {
                int end = next < 0 ? data.length : next;
                byte[] result = new byte[end - start];
                System.arraycopy(data, start, result, 0, result.length);
                return result;
            }
            start = next;
        }
        return null;
    }

    static boolean containsNalType(byte[] data, int wantedType) {
        int start = findStartCode(data, 0);
        while (start >= 0) {
            int prefix = data[start + 2] == 1 ? 3 : 4;
            int nal = start + prefix;
            if (nal < data.length && (data[nal] & 0x1f) == wantedType) return true;
            start = findStartCode(data, nal + 1);
        }
        return false;
    }

    static byte[] retainLatestKeyFrame(byte[] retained, byte[] candidate) {
        return containsNalType(candidate, 5) ? candidate : retained;
    }

    static void resetSessionCounters(AtomicLong... counters) {
        for (AtomicLong counter : counters) counter.set(0L);
    }

    private static int findStartCode(byte[] data, int offset) {
        for (int i = Math.max(0, offset); i + 3 < data.length; i++) {
            if (data[i] != 0 || data[i + 1] != 0) continue;
            if (data[i + 2] == 1) return i;
            if (data[i + 2] == 0 && data[i + 3] == 1) return i;
        }
        return -1;
    }

    private static final class Frame {
        final byte[] data;
        final int type;
        final long ptsUs;
        Frame(byte[] data, int type, long ptsUs) { this.data = data; this.type = type; this.ptsUs = ptsUs; }
    }
}
