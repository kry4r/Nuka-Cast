package com.nukacast.app;

import android.app.Application;
import android.content.Context;

import androidx.multidex.MultiDex;

import com.nukacast.app.core.NukaRuntime;

public final class NukaCastApp extends Application {
    private NukaRuntime runtime;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        runtime = new NukaRuntime(this);
    }

    public NukaRuntime runtime() {
        return runtime;
    }
}
