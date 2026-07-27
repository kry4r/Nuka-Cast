package com.nukacast.app;

import android.app.Application;

import com.nukacast.app.core.NukaRuntime;

public final class NukaCastApp extends Application {
    private NukaRuntime runtime;

    @Override
    public void onCreate() {
        super.onCreate();
        runtime = new NukaRuntime(this);
    }

    public NukaRuntime runtime() {
        return runtime;
    }
}
