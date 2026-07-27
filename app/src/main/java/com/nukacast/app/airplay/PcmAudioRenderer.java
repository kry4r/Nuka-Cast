package com.nukacast.app.airplay;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class PcmAudioRenderer {
    private static final int SAMPLE_RATE = 44100;
    private final ArrayBlockingQueue<short[]> queue = new ArrayBlockingQueue<short[]>(6);
    private final AtomicLong packets = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final Thread thread;
    private volatile boolean running = true;
    private AudioTrack track;

    PcmAudioRenderer() {
        thread = new Thread(new Runnable() {
            @Override public void run() { playbackLoop(); }
        }, "nukacast-airplay-audio");
        thread.start();
    }

    void offer(short[] samples) {
        packets.incrementAndGet();
        if (!queue.offer(samples)) {
            queue.poll();
            queue.offer(samples);
            dropped.incrementAndGet();
        }
    }

    void flush() {
        queue.clear();
        if (track != null) {
            try { track.flush(); } catch (IllegalStateException ignored) {}
        }
    }

    void stop() {
        running = false;
        thread.interrupt();
        try { thread.join(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        releaseTrack();
    }

    long packets() { return packets.get(); }
    long dropped() { return dropped.get(); }

    private void playbackLoop() {
        while (running) {
            try {
                short[] samples = queue.poll(20, TimeUnit.MILLISECONDS);
                if (samples == null) continue;
                if (track == null && !createTrack()) {
                    dropped.incrementAndGet();
                    continue;
                }
                int offset = 0;
                while (running && offset < samples.length) {
                    int written = track.write(samples, offset, samples.length - offset);
                    if (written <= 0) break;
                    offset += written;
                }
            } catch (InterruptedException ignored) {
                if (!running) return;
            } catch (RuntimeException error) {
                dropped.incrementAndGet();
                releaseTrack();
            }
        }
    }

    private boolean createTrack() {
        int channel = AudioFormat.CHANNEL_OUT_STEREO;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;
        int minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE, channel, encoding);
        if (minimum <= 0) return false;
        try {
            track = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE, channel, encoding,
                    Math.max(minimum, 3840), AudioTrack.MODE_STREAM);
            track.play();
            return track.getState() == AudioTrack.STATE_INITIALIZED;
        } catch (RuntimeException error) {
            releaseTrack();
            return false;
        }
    }

    private void releaseTrack() {
        AudioTrack active = track;
        track = null;
        if (active == null) return;
        try { active.pause(); } catch (IllegalStateException ignored) {}
        try { active.flush(); } catch (IllegalStateException ignored) {}
        try { active.release(); } catch (RuntimeException ignored) {}
    }
}
