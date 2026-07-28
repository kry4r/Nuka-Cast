package com.nukacast.app;

import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;

public final class CrashReporter {
    private static final String FILE_NAME = "last-java-crash.txt";
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private CrashReporter() {}

    static void install(final Context context) {
        final Context appContext = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread, Throwable error) {
                write(appContext, thread, error);
                if (previous != null) previous.uncaughtException(thread, error);
            }
        });
    }

    public static String read(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.isFile()) return "";
        StringBuilder report = new StringBuilder();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), UTF_8));
            try {
                String line;
                while ((line = reader.readLine()) != null && report.length() < 24000) {
                    report.append(line).append('\n');
                }
            } finally {
                reader.close();
            }
        } catch (Exception ignored) {
            return "";
        }
        return report.toString().trim();
    }

    static void clear(Context context) {
        context.deleteFile(FILE_NAME);
    }

    private static void write(Context context, Thread thread, Throwable error) {
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        String report = "Thread: " + thread.getName() + '\n'
                + "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "Device: " + Build.MANUFACTURER + " " + Build.MODEL + '\n'
                + trace.toString();
        try {
            FileOutputStream output = context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE);
            try {
                output.write(report.getBytes(UTF_8));
                output.flush();
            } finally {
                output.close();
            }
        } catch (Exception ignored) {
            // The platform crash handler must still receive the original exception.
        }
    }
}
