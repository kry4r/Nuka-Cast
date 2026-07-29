package com.github.catvod.crawler;

import android.util.Log;

import com.nukacast.app.diagnostics.AppLog;

public class SpiderDebug {
    public SpiderDebug() {}

    public static void log(Throwable error) {
        try {
            Log.d("SpiderLog", error == null ? "" : error.getMessage(), error);
            AppLog.w("Spider", error == null ? "Spider 返回空异常" : "Spider 调试异常", error);
        } catch (Throwable ignored) {}
    }

    public static void log(String message) {
        try {
            Log.d("SpiderLog", message == null ? "" : message);
            AppLog.d("Spider", message == null ? "" : message);
        } catch (Throwable ignored) {}
    }

    public static String ec(int value) {
        return "";
    }
}
