package com.nukacast.app.diagnostics;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Bounded, persisted application log.
 *
 * <p>Three properties matter on a 1.5 GB Android 4.4 TV, and all three were learned the hard way
 * from a real device log full of decoder errors:
 *
 * <ul>
 *   <li><b>Repeats are folded.</b> A failing loop can emit dozens of identical lines per second;
 *       the newest entry then carries a repeat count instead of flooding the list. Without this the
 *       500-entry buffer only kept a few seconds of history.
 *   <li><b>Disk writes are asynchronous and throttled.</b> Persisting synchronously on the caller's
 *       thread meant rewriting the whole JSONL file for every single line — tens of megabytes per
 *       second during an error storm, on the thread that was supposed to decode video.
 *   <li><b>Memory stays bounded.</b> Traces are truncated and the encoded size is tracked
 *       incrementally instead of re-serialising every entry on each write.
 * </ul>
 */
public final class AppLog {
    public enum Level {
        DEBUG("调试"), INFO("信息"), WARN("警告"), ERROR("错误");

        public final String label;

        Level(String label) { this.label = label; }
    }

    public static final class Entry {
        public long timestamp;
        public Level level;
        public String component;
        public String message;
        public String trace;
        /** How many identical lines this entry represents (1 = only itself). */
        public int repeats = 1;

        Entry() {}

        Entry(long timestamp, Level level, String component, String message, String trace) {
            this.timestamp = timestamp;
            this.level = level;
            this.component = component;
            this.message = message;
            this.trace = trace;
        }
    }

    private static final String TAG = "NukaCast";
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final int DEFAULT_MAX_ENTRIES = 400;
    private static final int DEFAULT_MAX_BYTES = 256 * 1024;
    private static final int MAX_MESSAGE_CHARS = 4000;
    private static final int MAX_TRACE_CHARS = 4000;
    /** Identical lines inside this window collapse into one entry with a repeat count. */
    private static final long DEDUPE_WINDOW_MS = 10_000L;
    private static final long PERSIST_DELAY_MS = 2000L;
    private static volatile AppLog instance = new AppLog(null, DEFAULT_MAX_ENTRIES,
            DEFAULT_MAX_BYTES);

    private final File file;
    private final int maxEntries;
    private final int maxBytes;
    private final Gson gson = new Gson();
    private final List<Entry> entries = new ArrayList<Entry>();
    private ScheduledExecutorService writer;
    private int encodedBytes;
    private boolean dirty;

    public AppLog(File file, int maxEntries, int maxBytes) {
        this.file = file;
        this.maxEntries = Math.max(1, maxEntries);
        this.maxBytes = Math.max(1024, maxBytes);
        load();
    }

    public static synchronized void initialize(Context context) {
        AppLog created = new AppLog(new File(context.getFilesDir(), "application-log.jsonl"),
                DEFAULT_MAX_ENTRIES, DEFAULT_MAX_BYTES);
        created.startWriter();
        instance = created;
    }

    /** True when writes are handed to the background writer instead of the caller's thread. */
    public boolean usesBackgroundWriter() {
        return writer != null;
    }

    public static void d(String component, String message) {
        write(Level.DEBUG, component, message, null);
    }

    public static void i(String component, String message) {
        write(Level.INFO, component, message, null);
    }

    public static void w(String component, String message) {
        write(Level.WARN, component, message, null);
    }

    public static void w(String component, String message, Throwable error) {
        write(Level.WARN, component, message, error);
    }

    public static void e(String component, String message, Throwable error) {
        write(Level.ERROR, component, message, error);
    }

    private static void write(Level level, String component, String message, Throwable error) {
        String safeComponent = clean(component, "应用");
        String safeMessage = clean(message, error == null ? "无详细信息" : error.getClass().getSimpleName());
        try {
            instance.add(level, safeComponent, safeMessage, error);
        } catch (Throwable ignored) {
            // Logging must never be the reason a stream stops.
        }
        try {
            if (level == Level.ERROR) Log.e(TAG + "/" + safeComponent, safeMessage, error);
            else if (level == Level.WARN) Log.w(TAG + "/" + safeComponent, safeMessage, error);
            else if (level == Level.INFO) Log.i(TAG + "/" + safeComponent, safeMessage);
            else Log.d(TAG + "/" + safeComponent, safeMessage);
        } catch (Throwable ignored) {
            // Logcat is unavailable in unit tests.
        }
    }

    public static List<Entry> snapshot(Level exactLevel) {
        return instance.entries(exactLevel);
    }

    public static String format(Level exactLevel) {
        return instance.formatted(exactLevel);
    }

    public static void clear() {
        instance.clearEntries();
    }

    /** Flushes any pending write; used on shutdown and by tests. */
    public static void flushNow() {
        instance.persistNow();
    }

    /** Drops stack traces from the current buffer; called under memory pressure. */
    public static void trimStackTrace() {
        instance.trimTraces();
    }

    public synchronized void add(Level level, String component, String message, Throwable error) {
        long now = System.currentTimeMillis();
        String safeComponent = clean(component, "应用");
        String safeMessage = limit(clean(message, "无详细信息"), MAX_MESSAGE_CHARS);
        Entry last = entries.isEmpty() ? null : entries.get(entries.size() - 1);
        if (last != null && last.level == level && safeComponent.equals(last.component)
                && safeMessage.equals(last.message)
                && now - last.timestamp <= DEDUPE_WINDOW_MS) {
            // Same line again: count it instead of appending. The trace is already recorded.
            last.timestamp = now;
            last.repeats++;
            dirty = true;
            return;
        }
        Entry entry = new Entry(now, level, safeComponent, safeMessage, trace(error));
        entries.add(entry);
        encodedBytes += encodedSize(entry);
        trimToLimits();
        dirty = true;
        // With a writer thread (the Android process) the flush happens off-thread; a standalone
        // instance without one keeps the simple synchronous behaviour callers rely on.
        if (writer == null) persistNow();
    }

    public synchronized List<Entry> entries(Level exactLevel) {
        List<Entry> result = new ArrayList<Entry>();
        for (Entry entry : entries) {
            if (exactLevel == null || entry.level == exactLevel) result.add(entry);
        }
        return Collections.unmodifiableList(result);
    }

    public synchronized String formatted(Level exactLevel) {
        SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA);
        StringBuilder text = new StringBuilder();
        for (Entry entry : entries) {
            if (exactLevel != null && entry.level != exactLevel) continue;
            if (text.length() > 0) text.append("\n\n");
            text.append(time.format(new Date(entry.timestamp)))
                    .append("  ").append(entry.level == null ? Level.INFO.label : entry.level.label)
                    .append("  [").append(clean(entry.component, "应用")).append("]\n")
                    .append(clean(entry.message, "无详细信息"));
            if (entry.repeats > 1) text.append("\n（重复 ").append(entry.repeats).append(" 次）");
            if (entry.trace != null && !entry.trace.isEmpty()) text.append('\n').append(entry.trace);
        }
        return text.toString();
    }

    public synchronized void clearEntries() {
        entries.clear();
        encodedBytes = 0;
        dirty = false;
        persistNow();
        if (file != null && file.exists() && !file.delete()) persistNow();
    }

    /** Drops trace text when the process is under memory pressure; keeps the messages. */
    public synchronized void trimTraces() {
        int freed = 0;
        for (Entry entry : entries) {
            if (entry.trace == null || entry.trace.isEmpty()) continue;
            freed += entry.trace.length();
            entry.trace = "";
            entry.repeats = Math.max(1, entry.repeats);
        }
        trimToLimits();
        if (freed > 0) dirty = true;
    }

    private void startWriter() {
        try {
            writer = Executors.newSingleThreadScheduledExecutor(new java.util.concurrent.ThreadFactory() {
                @Override public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "nukacast-log-writer");
                    thread.setDaemon(true);
                    return thread;
                }
            });
            writer.scheduleWithFixedDelay(new Runnable() {
                @Override public void run() { persistIfDirty(); }
            }, PERSIST_DELAY_MS, PERSIST_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
            writer = null;
        }
    }

    private synchronized void persistIfDirty() {
        if (!dirty) return;
        persistNow();
    }

    private synchronized void persistNow() {
        if (file == null) {
            dirty = false;
            return;
        }
        dirty = false;
        List<Entry> snapshot = new ArrayList<Entry>(entries);
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            OutputStreamWriter out = new OutputStreamWriter(new FileOutputStream(temporary), UTF_8);
            try {
                for (Entry entry : snapshot) {
                    out.write(gson.toJson(entry));
                    out.write('\n');
                }
                out.flush();
            } finally {
                out.close();
            }
            if (file.exists() && !file.delete()) return;
            temporary.renameTo(file);
        } catch (Exception ignored) {
            // Diagnostics are best-effort; never surface I/O failures to playback.
        }
    }

    private synchronized void load() {
        if (file == null || !file.isFile()) return;
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), UTF_8));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    Entry entry = gson.fromJson(line, Entry.class);
                    if (entry != null && entry.level != null) {
                        if (entry.repeats < 1) entry.repeats = 1;
                        entries.add(entry);
                    }
                }
            } finally {
                reader.close();
            }
        } catch (Exception ignored) {
            entries.clear();
        }
        encodedBytes = 0;
        for (Entry entry : entries) encodedBytes += encodedSize(entry);
        trimToLimits();
    }

    private void trimToLimits() {
        while (entries.size() > maxEntries) {
            encodedBytes -= encodedSize(entries.remove(0));
        }
        while (entries.size() > 1 && encodedBytes > maxBytes) {
            encodedBytes -= encodedSize(entries.remove(0));
        }
        if (encodedBytes < 0) encodedBytes = 0;
    }

    private int encodedSize(Entry entry) {
        return gson.toJson(entry).getBytes(UTF_8).length + 1;
    }

    private static String trace(Throwable error) {
        if (error == null) return "";
        StringWriter output = new StringWriter();
        error.printStackTrace(new PrintWriter(output));
        return limit(output.toString().trim(), MAX_TRACE_CHARS);
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        return value.trim();
    }

    private static String limit(String value, int maximum) {
        if (value == null) return "";
        return value.length() <= maximum ? value : value.substring(0, maximum) + "\n[内容已截断]";
    }
}
