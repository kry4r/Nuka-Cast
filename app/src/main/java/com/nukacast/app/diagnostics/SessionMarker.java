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

        public int heapPercent() {
            if (heapMaxBytes <= 0L) return 0;
            return (int) Math.min(100L, heapUsedBytes * 100L / heapMaxBytes);
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
    }

    private static final String FILE_NAME = "run-session.json";
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final int MAX_SAMPLES = 16;
    private static final long SAMPLE_INTERVAL_MS = 30_000L;
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
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            if (manager != null) {
                manager.getMemoryInfo(info);
                sample.availableMemoryBytes = info.availMem;
                if (info.lowMemory) sample.stage = "low_memory";
            }
        } catch (Throwable ignored) {
            sample.availableMemoryBytes = 0L;
        }
        if (stageOverride != null && !stageOverride.isEmpty()) {
            sample.stage = stageOverride;
        } else if (sample.stage.isEmpty()) {
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
