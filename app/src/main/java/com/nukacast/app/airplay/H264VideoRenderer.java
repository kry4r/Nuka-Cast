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
    private MediaCodec decoder;
    private long lastPtsUs;

    H264VideoRenderer() {
        thread = new Thread(new Runnable() {
            @Override public void run() { decodeLoop(); }
        }, "nukacast-airplay-video");
        thread.start();
    }

    void setSurface(Surface value) {
        surface = value;
        releaseDecoder();
    }

    void offer(byte[] data, int type, long ptsUs) {
        received.incrementAndGet();
        if (type == 0) codecConfig = data;
        Frame frame = new Frame(data, type, ptsUs);
        if (!queue.offer(frame)) {
            queue.poll();
            if (!queue.offer(frame)) dropped.incrementAndGet();
            else dropped.incrementAndGet();
        }
    }

    void flush() {
        queue.clear();
        lastPtsUs = 0;
        if (decoder != null) {
            try { decoder.flush(); } catch (RuntimeException ignored) {}
        }
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

    private void decodeLoop() {
        while (running) {
            try {
                Frame frame = queue.poll(10, TimeUnit.MILLISECONDS);
                if (frame == null) {
                    drain();
                    continue;
                }
                if (decoder == null && !createDecoder()) {
                    dropped.incrementAndGet();
                    continue;
                }
                queueInput(frame);
                drain();
            } catch (InterruptedException ignored) {
                if (!running) return;
            } catch (RuntimeException error) {
                dropped.incrementAndGet();
                releaseDecoder();
            }
        }
    }

    private boolean createDecoder() {
        Surface target = surface;
        if (target == null || !target.isValid()) return false;
        try {
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", 1920, 1080);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024);
            decoder = MediaCodec.createDecoderByType("video/avc");
            decoder.configure(format, target, null, 0);
            decoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);
            decoder.start();
            byte[] config = codecConfig;
            if (config != null) queue.offer(new Frame(config, 0, 0));
            return true;
        } catch (Exception error) {
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
        int flags = frame.type == 0 ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0;
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

    private static final class Frame {
        final byte[] data;
        final int type;
        final long ptsUs;
        Frame(byte[] data, int type, long ptsUs) { this.data = data; this.type = type; this.ptsUs = ptsUs; }
    }
}
