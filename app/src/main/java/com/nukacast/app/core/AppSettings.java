package com.nukacast.app.core;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * App-level switches that are not about a single playback.
 *
 * <p>Its own preference file rather than part of the playback settings: these decide how the app
 * behaves as a device on the network, which is what a casting box is judged on.
 */
public final class AppSettings {

    private static final String FILE = "app_settings";
    private static final String KEY_START_ON_BOOT = "startOnBoot";

    private AppSettings() {
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /**
     * Whether the receiver starts by itself when the box powers on.
     *
     * <p>Off by default: a television app that appears on its own is surprising. Once switched on, a
     * phone can cast without anyone finding the remote first, which is how every receiver box behaves.
     */
    public static boolean startOnBoot(Context context) {
        return preferences(context).getBoolean(KEY_START_ON_BOOT, false);
    }

    public static void setStartOnBoot(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(KEY_START_ON_BOOT, enabled).apply();
    }
}
