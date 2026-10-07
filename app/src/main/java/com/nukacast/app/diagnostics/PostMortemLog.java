package com.nukacast.app.diagnostics;

import android.content.Context;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;

/**
 * Keeps a filtered copy of the system log from around the time the previous process died.
 *
 * <p>A process ended by the kernel's memory killer, or by a native crash, leaves nothing behind that
 * Java code can see: no exception, no log line, no tombstone (those need root). What it does leave
 * is the platform's own log — {@code lowmemorykiller: Kill 'com.nukacast.app'}, {@code libc: Fatal
 * signal 11} — which is still in the ring buffer for a while after the restart.
 *
 * <p>Reading it needs the READ_LOGS permission, which a normal app on API 19 does not have. The
 * capture is therefore best-effort: when it fails, the report says so instead of pretending the
 * system log was clean.
 */
public final class PostMortemLog {
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String FILE_NAME = "post-mortem.log";
    private static final int MAX_LINES = 4000;
    private static final int MAX_CHARS = 96 * 1024;
    /** Lines worth keeping: process deaths, native crashes, ANRs and our own app's entries. */
    private static final String[] INTERESTING = {
            "Fatal signal", "signal 11", "signal 6", "signal 7", "DEBUG", "tombstone",
            "lowmemorykiller", "LowMemoryKiller", "lmkd", "Killing", "am_kill", "am_proc_died",
            "ANR ", "ANR in", "not responding", "Choreographer", "dalvikvm", "art",
            "LinearAlloc", "OutOfMemory", "GC_", "ActivityManager", "com.nukacast.app",
            "PowerManagerService", "WindowManager", "libc",
    };

    private PostMortemLog() {}

    public static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    /** Captures in the background so application startup is not delayed by logcat. */
    public static void captureAsync(final Context context) {
        if (context == null) return;
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    capture(context.getApplicationContext());
                } catch (Throwable ignored) {
                    // Diagnostics only.
                }
            }
        }, "nukacast-postmortem");
        thread.setDaemon(true);
        thread.start();
    }

    /** Runs {@code logcat -d}, filters it and stores the result. Returns what was stored. */
    public static String capture(Context context) {
        String raw = null;
        String failure = null;
        Process process = null;
        try {
            // -t limits to the newest lines, which is where a crash from seconds ago will be.
            process = new ProcessBuilder("logcat", "-d", "-v", "time", "-t", String.valueOf(MAX_LINES))
                    .redirectErrorStream(true)
                    .start();
            StringBuilder output = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), UTF_8));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                    if (output.length() >= MAX_CHARS * 4) break;
                }
            } finally {
                closeQuietly(reader);
            }
            raw = output.toString();
            process.waitFor();
        } catch (Throwable error) {
            failure = error.getClass().getSimpleName() + ": " + error.getMessage();
        } finally {
            if (process != null) process.destroy();
        }

        StringBuilder report = new StringBuilder();
        if (raw == null || raw.trim().isEmpty()) {
            report.append("系统日志不可用");
            if (failure != null) report.append("：").append(failure);
            else report.append("：logcat 无输出（Android 4.1 起普通应用需要 root 才能读取系统日志）");
        } else {
            int kept = 0;
            for (String line : raw.split("\n")) {
                if (!isInteresting(line)) continue;
                report.append(line).append('\n');
                kept++;
                if (report.length() >= MAX_CHARS) {
                    report.append("[已截断]\n");
                    break;
                }
            }
            if (kept == 0) report.append("系统日志里没有与崩溃相关的行（共 ").append(raw.length()).append(" 字符）");
        }
        write(context, report.toString());
        AppLog.i("诊断", "已保存上次退出时的系统日志片段（" + report.length() + " 字符）");
        return report.toString();
    }

    private static boolean isInteresting(String line) {
        for (String token : INTERESTING) {
            if (line.contains(token)) return true;
        }
        return false;
    }

    private static void write(Context context, String text) {
        File file = file(context);
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(temporary), UTF_8);
            try {
                writer.write("NukaCast 上次退出时的系统日志片段\n\n");
                writer.write(text);
                writer.flush();
            } finally {
                writer.close();
            }
            if (file.exists() && !file.delete()) return;
            temporary.renameTo(file);
        } catch (Exception ignored) {
            // Best effort.
        }
    }

    public static String read(Context context) {
        File file = file(context);
        if (file == null || !file.isFile()) return "";
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new java.io.FileInputStream(file), UTF_8));
            try {
                StringBuilder text = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    text.append(line).append('\n');
                    if (text.length() >= MAX_CHARS) break;
                }
                return text.toString().trim();
            } finally {
                closeQuietly(reader);
            }
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Nothing to do.
        }
    }
}
