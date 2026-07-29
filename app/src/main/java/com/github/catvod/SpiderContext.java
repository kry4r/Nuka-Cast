package com.github.catvod;

import android.annotation.SuppressLint;
import android.content.Context;

/** Process context shared with host-API compatibility shims. */
public final class SpiderContext {
    @SuppressLint("StaticFieldLeak")
    private static Context context;

    private SpiderContext() {}

    public static synchronized void set(Context value) {
        context = value == null ? null : value.getApplicationContext();
    }

    public static synchronized Context get() {
        return context;
    }
}
