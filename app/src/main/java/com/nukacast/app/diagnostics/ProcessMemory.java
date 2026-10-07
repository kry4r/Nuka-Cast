package com.nukacast.app.diagnostics;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

/**
 * How much memory this process is actually charged for, and what it is allowed to use.
 *
 * <p>On an Android 4.4 TV the Java heap says almost nothing about the danger: plugin work runs in
 * QuickJS runtimes and DexClassLoader spiders, which live in native memory, so the heap can read 1%
 * while the process is large enough for the platform's killer to end it. The kernel's {@code VmRSS}
 * and the platform's PSS are the numbers that decide who gets killed, so they are what the budgets
 * here are based on.
 */
public final class ProcessMemory {
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private ProcessMemory() {}

    /** Resident set size of this process, or 0 when {@code /proc} is not readable. */
    public static long rssBytes() {
        return procKilobytes("/proc/self/status", "VmRSS:");
    }

    /** Virtual size; grows with thread stacks and memory mappings, useful as a second signal. */
    public static long vmSizeBytes() {
        return procKilobytes("/proc/self/status", "VmSize:");
    }

    public static int threadCount() {
        long value = procKilobytes("/proc/self/status", "Threads:");
        return value <= 0L ? 0 : (int) (value / 1024L);
    }

    public static int oomScoreAdj() {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream("/proc/self/oom_score_adj"), UTF_8));
            try {
                String line = reader.readLine();
                return line == null ? 0 : Integer.parseInt(line.trim());
            } finally {
                closeQuietly(reader);
            }
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** Total PSS from the platform, the number its memory killer looks at. */
    public static long totalPssBytes(Context context) {
        try {
            ActivityManager manager =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return 0L;
            Debug.MemoryInfo[] infos =
                    manager.getProcessMemoryInfo(new int[]{android.os.Process.myPid()});
            if (infos == null || infos.length == 0) return 0L;
            return infos[0].getTotalPss() * 1024L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public static long totalRamBytes(Context context) {
        try {
            ActivityManager manager =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            if (manager != null) manager.getMemoryInfo(info);
            return info.totalMem;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /**
     * What this process may use before plugin work has to stop. A sixth of device RAM keeps room for
     * the 1080p decoder, the AirPlay receiver and whatever else the TV is running; plugins are the
     * only part of the app whose memory grows without bound, so they carry the limit.
     */
    public static long pluginBudgetBytes(Context context) {
        return pluginBudgetFor(totalRamBytes(context) / (1024L * 1024L));
    }

    /** Budget arithmetic, separated from the device query so it can be tested directly. */
    public static long pluginBudgetFor(long totalRamMegabytes) {
        if (totalRamMegabytes <= 0L) return 64L * 1024L * 1024L;
        long sixth = totalRamMegabytes * 1024L * 1024L / 6L;
        long minimum = 48L * 1024L * 1024L;
        long maximum = 192L * 1024L * 1024L;
        return Math.max(minimum, Math.min(maximum, sixth));
    }

    public static String megabytes(long bytes) {
        return (bytes / 1048576L) + "MB";
    }

    private static long procKilobytes(String path, String key) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(path), UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith(key)) continue;
                return parseKilobytes(line, key);
            }
        } catch (Throwable ignored) {
            return 0L;
        } finally {
            closeQuietly(reader);
        }
        return 0L;
    }

    /** Reads the first number of a {@code /proc} line such as {@code VmRSS:\t  12288 kB}. */
    static long parseKilobytes(String line, String key) {
        if (line == null || key == null || !line.startsWith(key)) return 0L;
        StringBuilder digits = new StringBuilder();
        for (int i = key.length(); i < line.length(); i++) {
            char character = line.charAt(i);
            if (character >= '0' && character <= '9') digits.append(character);
            else if (digits.length() > 0) break;
        }
        if (digits.length() == 0) return 0L;
        try {
            return Long.parseLong(digits.toString()) * 1024L;
        } catch (NumberFormatException error) {
            return 0L;
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
