package com.nukacast.app.diagnostics;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Debug;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Records how the previous process ended, with the memory curve that led there.
 *
 * <p>"It crashes after a while" cannot be answered by a Java crash record when the platform kills
 * the process for memory pressure: no handler runs, nothing is logged, and the next start looks
 * perfectly normal. This marker closes that gap — the running session keeps a small memory history
 * plus the stage it was in, and marks a clean exit when it shuts down. On the next start an
 * unmarked file means the process was killed from the outside, and the samples show how close to
 * the heap limit it had been living.
 */
public final class SessionMarker {
    public static final class Sample {
        public long at;
        public long heapUsedBytes;
        public long heapMaxBytes;
        public long nativeHeapBytes;
        public long availableMemoryBytes;
        public String stage = "";
        /** Totals from {@code /proc/self/status}: the numbers the platform's killer looks at. */
        public long vmRssBytes;
        public long vmSizeBytes;
        public int threads;
        public int oomScoreAdj;
        /** PSS split from {@code Debug.MemoryInfo}; native includes plugin runtimes and codecs. */
        public long pssTotalBytes;
        public long pssNativeBytes;
        public long pssDalvikBytes;
        public long pssOtherBytes;
        public long totalMemoryBytes;
        public boolean lowMemory;
        public int importance;
        public int lastTrimLevel;
        /** Plugin session counts, because that is what actually grows native memory here. */
        public String pluginSessions = "";

        public int heapPercent() {
            if (heapMaxBytes <= 0L) return 0;
            return (int) Math.min(100L, heapUsedBytes * 100L / heapMaxBytes);
        }

        /** Percent of device RAM this process is charged for; 0 when the value is unavailable. */
        public int pssPercentOfDevice() {
            if (totalMemoryBytes <= 0L || pssTotalBytes <= 0L) return 0;
            return (int) Math.min(100L, pssTotalBytes * 100L / totalMemoryBytes);
        }
    }

    public static final class Run {
        public long startedAt;
        public long endedAt;
        public int sdk;
        public String version = "";
        public String device = "";
        /** True only when the process shut down through its own code path. */
        public boolean endedCleanly;
        public final List<Sample> samples = new ArrayList<Sample>();

        public long durationMs() {
            return Math.max(0L, (endedAt <= 0L ? System.currentTimeMillis() : endedAt) - startedAt);
        }

        /** The last memory reading before the process disappeared. */
        public Sample lastSample() {
            return samples.isEmpty() ? null : samples.get(samples.size() - 1);
        }

        public int peakHeapPercent() {
            int peak = 0;
            for (Sample sample : samples) peak = Math.max(peak, sample.heapPercent());
            return peak;
        }

        /** Highest RSS seen, which shows a native-memory blow-up that the heap never reports. */
        public long peakRssBytes() {
            long peak = 0L;
            for (Sample sample : samples) peak = Math.max(peak, sample.vmRssBytes);
            return peak;
        }

        public String describeDeath() {
            if (endedCleanly) return "正常退出";
            Sample last = lastSample();
            if (last == null) return "被系统或外部结束";
            return "被系统或外部结束（末次：堆 " + last.heapPercent() + "% · RSS "
                    + (last.vmRssBytes / 1048576L) + "MB · 线程 " + last.threads
                    + " · oom_adj " + last.oomScoreAdj + "）";
        }
    }

    private static final String FILE_NAME = "run-session.json";
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final int MAX_SAMPLES = 120;
    /**
     * Five seconds, not thirty. The process dies roughly 40 seconds after startup on the affected
     * TV, and a 30 second interval captured only the startup reading.
     */
    private static final long SAMPLE_INTERVAL_MS = 5_000L;
    private static final Gson GSON = new GsonBuilder().create();
    private static volatile SessionMarker instance;

    private final Context context;
    private final Run run = new Run();
    private Run previous;
    private ScheduledExecutorService sampler;

    private SessionMarker(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Reads the previous run and starts recording this one. Safe to call once per process. */
    public static synchronized SessionMarker initialize(Context context) {
        SessionMarker created = new SessionMarker(context);
        created.previous = created.readPrevious();
        created.run.startedAt = System.currentTimeMillis();
        created.run.sdk = Build.VERSION.SDK_INT;
        created.run.version = com.nukacast.app.BuildConfig.VERSION_NAME;
        created.run.device = Build.MANUFACTURER + " " + Build.MODEL;
        instance = created;
        created.start();
        return created;
    }

    public static SessionMarker get() {
        return instance;
    }

    /** The run that was interrupted, or null when the previous exit was clean or unknown. */
    public static Run interruptedRun() {
        SessionMarker marker = instance;
        return marker == null ? null : marker.previous;
    }

    public static Run currentRun() {
        SessionMarker marker = instance;
        return marker == null ? null : marker.run;
    }

    /** Marks a clean exit so the next start does not report an interrupted run. */
    public static void markCleanExit() {
        SessionMarker marker = instance;
        if (marker == null) return;
        marker.run.endedCleanly = true;
        marker.run.endedAt = System.currentTimeMillis();
        marker.persist();
    }

    private void start() {
        sample("startup");
        try {
            sampler = Executors.newSingleThreadScheduledExecutor(new java.util.concurrent.ThreadFactory() {
                @Override public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "nukacast-session-marker");
                    thread.setDaemon(true);
                    return thread;
                }
            });
            sampler.scheduleWithFixedDelay(new Runnable() {
                @Override public void run() { sample(""); }
            }, SAMPLE_INTERVAL_MS, SAMPLE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
            sampler = null;
        }
    }

    /** Takes one reading; the stage from the newest trace record tells where the process was. */
    public synchronized void sample(String stageOverride) {
        Sample sample = new Sample();
        sample.at = System.currentTimeMillis();
        Runtime runtime = Runtime.getRuntime();
        sample.heapUsedBytes = runtime.totalMemory() - runtime.freeMemory();
        sample.heapMaxBytes = runtime.maxMemory();
        try {
            sample.nativeHeapBytes = Debug.getNativeHeapAllocatedSize();
        } catch (Throwable ignored) {
            sample.nativeHeapBytes = 0L;
        }
        readProcStatus(sample);
        readProcessMemory(sample);
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            if (manager != null) {
                manager.getMemoryInfo(info);
                sample.availableMemoryBytes = info.availMem;
                sample.totalMemoryBytes = info.totalMem;
                sample.lowMemory = info.lowMemory;
            }
        } catch (Throwable ignored) {
            sample.availableMemoryBytes = 0L;
        }
        try {
            ActivityManager.RunningAppProcessInfo state = new ActivityManager.RunningAppProcessInfo();
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null) {
                ActivityManager.getMyMemoryState(state);
                sample.importance = state.importance;
                sample.lastTrimLevel = state.lastTrimLevel;
            }
        } catch (Throwable ignored) {
            sample.importance = 0;
        }
        sample.pluginSessions = SessionMarker.pluginSessions;
        if (stageOverride != null && !stageOverride.isEmpty()) {
            sample.stage = stageOverride;
        } else if (sample.lowMemory) {
            sample.stage = "low_memory";
        } else {
            List<StageTrace.Record> records = StageTrace.snapshot();
            if (!records.isEmpty()) {
                StageTrace.Record newest = records.get(0);
                sample.stage = newest.scope + "/" + newest.subject + "/" + newest.stage;
            }
        }
        run.samples.add(sample);
        while (run.samples.size() > MAX_SAMPLES) run.samples.remove(0);
        persist();
    }

    /**
     * Reads the kernel's view of this process. {@code VmRSS} and the thread count expose native
     * growth (plugin runtimes, codecs, sockets) that never appears in the Java heap, and
     * {@code oom_score_adj} says how likely the platform's killer is to pick us.
     */
    private static void readProcStatus(Sample sample) {
        java.io.BufferedReader reader = null;
        try {
            reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream("/proc/self/status"), UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("VmRSS:")) sample.vmRssBytes = kbOf(line);
                else if (line.startsWith("VmSize:")) sample.vmSizeBytes = kbOf(line);
                else if (line.startsWith("Threads:")) sample.threads = (int) kbOf(line);
            }
        } catch (Throwable ignored) {
            // Not readable on every ROM; the rest of the sample is still useful.
        } finally {
            closeQuietly(reader);
        }
        try {
            java.io.BufferedReader oom = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream("/proc/self/oom_score_adj"), UTF_8));
            try {
                String value = oom.readLine();
                if (value != null) sample.oomScoreAdj = Integer.parseInt(value.trim());
            } finally {
                closeQuietly(oom);
            }
        } catch (Throwable ignored) {
            sample.oomScoreAdj = 0;
        }
    }

    /** Parses {@code VmRSS:  123456 kB}. */
    private static long kbOf(String line) {
        int colon = line.indexOf(':');
        if (colon < 0) return 0L;
        StringBuilder digits = new StringBuilder();
        for (int i = colon + 1; i < line.length(); i++) {
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

    private void readProcessMemory(Sample sample) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return;
            Debug.MemoryInfo[] infos = manager.getProcessMemoryInfo(new int[]{android.os.Process.myPid()});
            if (infos == null || infos.length == 0) return;
            Debug.MemoryInfo info = infos[0];
            sample.pssTotalBytes = info.getTotalPss() * 1024L;
            sample.pssDalvikBytes = info.dalvikPss * 1024L;
            sample.pssNativeBytes = info.nativePss * 1024L;
            sample.pssOtherBytes = info.otherPss * 1024L;
        } catch (Throwable ignored) {
            // Memory info is best-effort.
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Nothing to do.
        }
    }

    /** Latest plugin-session summary, published by the spider manager for the samples. */
    private static volatile String pluginSessions = "";

    public static void publishPluginSessions(String summary) {
        pluginSessions = summary == null ? "" : summary;
    }

    /** Human-readable trim level, so a report says what the platform actually asked for. */
    public static String trimLevelName(int level) {
        switch (level) {
            case android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE: return "COMPLETE";
            case android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE: return "MODERATE";
            case android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND: return "BACKGROUND";
            case android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN: return "UI_HIDDEN";
            case android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL: return "RUNNING_CRITICAL";
            case android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW: return "RUNNING_LOW";
            case android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE: return "RUNNING_MODERATE";
            default: return "level " + level;
        }
    }

    /** Notes a low-memory callback without waiting for the next scheduled sample. */
    public synchronized void recordPressure(String detail) {
        sample(detail);
    }

    private Run readPrevious() {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.isFile()) return null;
        try {
            InputStreamReader reader = new InputStreamReader(new FileInputStream(file), UTF_8);
            try {
                Run parsed = GSON.fromJson(reader, new TypeToken<Run>() {}.getType());
                if (parsed == null || parsed.startedAt <= 0L) return null;
                // A clean exit is not an incident, and it is still worth showing on the device page.
                return parsed;
            } finally {
                reader.close();
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private synchronized void persist() {
        File file = new File(context.getFilesDir(), FILE_NAME);
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(temporary), UTF_8);
            try {
                writer.write(GSON.toJson(run));
                writer.flush();
            } finally {
                writer.close();
            }
            if (file.exists() && !file.delete()) return;
            temporary.renameTo(file);
        } catch (Exception ignored) {
            // Diagnostics only.
        }
    }
}
