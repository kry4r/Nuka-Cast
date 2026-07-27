package com.nukacast.app.airplay;

final class NativeAirPlayBridge {
    interface Listener {
        void onVideo(byte[] data, int type, long presentationTimeUs);
        void onAudio(short[] samples, long rtpTimestamp);
        void onSession(boolean active);
    }

    private static final boolean AVAILABLE;
    static {
        boolean loaded;
        try {
            System.loadLibrary("nukacast_airplay");
            loaded = true;
        } catch (Throwable error) {
            loaded = false;
        }
        AVAILABLE = loaded;
    }

    private final Listener listener;
    private long handle;

    NativeAirPlayBridge(Listener listener) {
        this.listener = listener;
    }

    synchronized void start() {
        if (!AVAILABLE) throw new IllegalStateException("当前 ABI 没有 AirPlay 原生库");
        if (handle != 0) return;
        handle = nativeStart();
        if (handle == 0) throw new IllegalStateException("RAOP 服务启动失败");
    }

    synchronized void stop() {
        if (handle == 0) return;
        nativeStop(handle);
        handle = 0;
    }

    synchronized int port() { return handle == 0 ? 0 : nativePort(handle); }
    synchronized String publicKey() { return handle == 0 ? "" : nativePublicKey(handle); }

    @SuppressWarnings("unused") private void onNativeVideo(byte[] data, int type, long pts) {
        listener.onVideo(data, type, pts);
    }

    @SuppressWarnings("unused") private void onNativeAudio(short[] samples, long pts) {
        listener.onAudio(samples, pts);
    }

    @SuppressWarnings("unused") private void onNativeSession(boolean active) {
        listener.onSession(active);
    }

    private native long nativeStart();
    private native void nativeStop(long handle);
    private native int nativePort(long handle);
    private native String nativePublicKey(long handle);
}
