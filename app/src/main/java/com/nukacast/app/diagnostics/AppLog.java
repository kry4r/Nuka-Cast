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
    private static final int DEFAULT_MAX_ENTRIES = 500;
    private static final int DEFAULT_MAX_BYTES = 512 * 1024;
    private static volatile AppLog instance = new AppLog(null, DEFAULT_MAX_ENTRIES,
            DEFAULT_MAX_BYTES);

    private final File file;
    private final int maxEntries;
    private final int maxBytes;
    private final Gson gson = new Gson();
    private final List<Entry> entries = new ArrayList<Entry>();

    public AppLog(File file, int maxEntries, int maxBytes) {
        this.file = file;
        this.maxEntries = Math.max(1, maxEntries);
        this.maxBytes = Math.max(1024, maxBytes);
        load();
    }

    public static synchronized void initialize(Context context) {
        instance = new AppLog(new File(context.getFilesDir(), "application-log.jsonl"),
                DEFAULT_MAX_ENTRIES, DEFAULT_MAX_BYTES);
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
        instance.add(level, safeComponent, safeMessage, error);
        try {
            if (level == Level.ERROR) Log.e(TAG + "/" + safeComponent, safeMessage, error);
            else if (level == Level.WARN) Log.w(TAG + "/" + safeComponent, safeMessage, error);
            else if (level == Level.INFO) Log.i(TAG + "/" + safeComponent, safeMessage);
            else Log.d(TAG + "/" + safeComponent, safeMessage);
        } catch (Throwable ignored) {
            // Persistent diagnostics must keep working even when Logcat is unavailable in tests.
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

    public synchronized void add(Level level, String component, String message, Throwable error) {
        entries.add(new Entry(System.currentTimeMillis(), level, clean(component, "应用"),
                limit(clean(message, "无详细信息"), 12000), trace(error)));
        trimToLimits();
        persist();
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
            if (entry.trace != null && !entry.trace.isEmpty()) text.append('\n').append(entry.trace);
        }
        return text.toString();
    }

    public synchronized void clearEntries() {
        entries.clear();
        if (file != null && file.exists() && !file.delete()) persist();
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
                    if (entry != null && entry.level != null) entries.add(entry);
                }
            } finally {
                reader.close();
            }
        } catch (Exception ignored) {
            entries.clear();
        }
        trimToLimits();
    }

    private void trimToLimits() {
        while (entries.size() > maxEntries) entries.remove(0);
        while (entries.size() > 1 && encodedSize() > maxBytes) entries.remove(0);
    }

    private int encodedSize() {
        int size = 0;
        for (Entry entry : entries) size += gson.toJson(entry).getBytes(UTF_8).length + 1;
        return size;
    }

    private void persist() {
        if (file == null) return;
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(temporary), UTF_8);
            try {
                for (Entry entry : entries) {
                    writer.write(gson.toJson(entry));
                    writer.write('\n');
                }
                writer.flush();
            } finally {
                writer.close();
            }
            if (file.exists() && !file.delete()) return;
            temporary.renameTo(file);
        } catch (Exception ignored) {
            // Logging must never interrupt playback or receiver services.
        }
    }

    private static String trace(Throwable error) {
        if (error == null) return "";
        StringWriter output = new StringWriter();
        error.printStackTrace(new PrintWriter(output));
        return limit(output.toString().trim(), 24000);
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        return value.trim();
    }

    private static String limit(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum) + "\n[内容已截断]";
    }
}
