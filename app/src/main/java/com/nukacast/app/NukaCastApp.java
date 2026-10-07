package com.nukacast.app;

import android.app.Application;
import android.content.Context;

import androidx.multidex.MultiDex;

import com.nukacast.app.core.NukaRuntime;
import com.nukacast.app.diagnostics.AppLog;
import com.nukacast.app.diagnostics.SessionMarker;

public final class NukaCastApp extends Application implements android.content.ComponentCallbacks2 {
    private NukaRuntime runtime;
    private long lastPressureLog;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.initialize(this);
        CrashReporter.install(this);
        SessionMarker.initialize(this);
        SessionMarker.Run interrupted = SessionMarker.interruptedRun();
        if (interrupted != null && !interrupted.endedCleanly) {
            AppLog.w("应用", "上次运行在 " + (interrupted.durationMs() / 1000) + " 秒后被系统结束"
                    + (interrupted.lastSample() == null ? ""
                    : "（最后内存占用 " + interrupted.peakHeapPercent() + "%）"));
        }
        AppLog.i("应用", "NukaCast 启动");
        runtime = new NukaRuntime(this);
    }

    public NukaRuntime runtime() {
        return runtime;
    }

    @Override
    public void onTerminate() {
        SessionMarker.markCleanExit();
        super.onTerminate();
    }

    /**
     * The platform told us memory is tight. The log buffer is the largest thing the app owns that
     * is not needed for playback, so its stack traces go first; the caches follow.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return;
        AppLog.trimStackTrace();
        SessionMarker marker = SessionMarker.get();
        if (marker != null) marker.recordPressure(level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
                ? "memory_critical" : "memory_low");
        if (runtime != null && level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            runtime.trimCaches();
        }
        long now = System.currentTimeMillis();
        if (now - lastPressureLog > 60_000L) {
            lastPressureLog = now;
            AppLog.i("应用", "系统内存紧张（level " + level + "），已释放日志与缓存");
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL);
    }
}
