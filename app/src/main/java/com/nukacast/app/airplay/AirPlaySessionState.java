package com.nukacast.app.airplay;

/** Thread-safe lifecycle state shared by native AirPlay callbacks and the UI watchdog. */
final class AirPlaySessionState {
    static final int PACKET_REJECTED = -1;
    static final int PACKET_CONTINUING = 0;
    static final int PACKET_STARTED = 1;

    private boolean acceptingPackets;
    private boolean active;
    private boolean nativeConnectionActive;
    private long lastPacketAt;

    synchronized void receiverStarted() {
        acceptingPackets = true;
        active = false;
        nativeConnectionActive = false;
        lastPacketAt = 0L;
    }

    synchronized void receiverStopped() {
        acceptingPackets = false;
        active = false;
        nativeConnectionActive = false;
        lastPacketAt = 0L;
    }

    synchronized int recordPacket(long nowMs) {
        if (!acceptingPackets) return PACKET_REJECTED;
        return recordAccepted(nowMs);
    }

    synchronized int nativeConnected(long nowMs) {
        if (!acceptingPackets) return PACKET_REJECTED;
        nativeConnectionActive = true;
        return recordAccepted(nowMs);
    }

    private int recordAccepted(long nowMs) {
        lastPacketAt = nowMs;
        if (active) return PACKET_CONTINUING;
        active = true;
        return PACKET_STARTED;
    }

    synchronized void disconnect() {
        acceptingPackets = false;
        active = false;
        nativeConnectionActive = false;
        lastPacketAt = 0L;
    }

    synchronized boolean isActive() {
        return active;
    }

    synchronized boolean isIdle(long nowMs, long timeoutMs) {
        return active && !nativeConnectionActive && lastPacketAt > 0L
                && nowMs - lastPacketAt > timeoutMs;
    }
}
