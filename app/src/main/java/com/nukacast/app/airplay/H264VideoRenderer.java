package com.nukacast.app.airplay;

import android.media.MediaCodec;
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
    private final Thread thread;
    private volatile boolean running = true;
    private volatile Surface surface;
    private volatile byte[] codecConfig;
    private volatile boolean decoderResetRequested;
    private volatile Frame pendingKeyFrame;
    private volatile String error = "";
    private volatile int videoWidth;
    private volatile int videoHeight;
    private MediaCodec decoder;
    private long lastPtsUs;
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
        Frame pending = pendingKeyFrame;
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
            queue.clear();
            decoderResetRequested = true;
        }
        Frame frame = new Frame(data, type, ptsUs);
        if (!queue.offer(frame)) {
            queue.poll();
            if (!queue.offer(frame)) dropped.incrementAndGet();
            else dropped.incrementAndGet();
        }
    }

    void flush() {
        queue.clear();
        codecConfig = null;
        pendingKeyFrame = null;
        lastPtsUs = 0;
        waitingForKeyFrame = true;
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
                    continue;
                }
                if (frame.type == 0) continue;
                if (decoder == null && !createDecoder()) {
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

    private boolean createDecoder() {
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
            decoder = MediaCodec.createDecoderByType("video/avc");
            decoder.configure(format, target, null, 0);
            decoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);
            decoder.start();
            videoWidth = dimensions.width;
            videoHeight = dimensions.height;
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
    }

    private void drain() {
        MediaCodec active = decoder;
        if (active == null) return;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int index;
        while ((index = active.dequeueOutputBuffer(info, 0)) >= 0) {
            active.releaseOutputBuffer(index, true);
        }
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
