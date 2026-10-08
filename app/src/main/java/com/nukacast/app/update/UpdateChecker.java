package com.nukacast.app.update;

import com.nukacast.app.diagnostics.AppLog;
import com.nukacast.app.net.HttpStack;

import java.util.concurrent.TimeUnit;

import okhttp3.Request;
import okhttp3.Response;

/**
 * Asks the release feed whether a newer build exists, and remembers the answer.
 *
 * <p>Cached for six hours: the check is a courtesy, not something to repeat on every screen. A failed
 * check is remembered for two minutes only, so a television that was offline for a moment can try again
 * without waiting.
 */
public final class UpdateChecker {

    private static final long OK_TTL_MS = 6L * 60L * 60L * 1000L;
    private static final long FAIL_TTL_MS = 2L * 60L * 1000L;

    private final String currentVersion;
    private volatile Updates.Result cached;
    private volatile long cachedAt;
    private volatile boolean running;

    public UpdateChecker(String currentVersion) {
        this.currentVersion = currentVersion == null ? "" : currentVersion;
    }

    /** The last answer, or null when nothing has been checked yet. */
    public Updates.Result last() {
        return cached;
    }

    /**
     * The last answer if it is still fresh, otherwise a fresh check.
     *
     * <p>Blocking: callers are the HTTP thread of the debug API or a background thread of the settings
     * screen, never the UI thread.
     */
    public Updates.Result check(boolean force) {
        Updates.Result cachedResult = cached;
        long age = System.currentTimeMillis() - cachedAt;
        long ttl = cachedResult != null && cachedResult.error.isEmpty() ? OK_TTL_MS : FAIL_TTL_MS;
        if (!force && cachedResult != null && age < ttl) return cachedResult;
        if (running) return cachedResult == null ? new Updates.Result() : cachedResult;
        running = true;
        try {
            Updates.Result result = fetch();
            cached = result;
            cachedAt = System.currentTimeMillis();
            AppLog.i("更新", result.summary);
            return result;
        } finally {
            running = false;
        }
    }

    private Updates.Result fetch() {
        Request request = new Request.Builder()
                .url(Updates.RELEASES_API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "NukaCast/" + currentVersion)
                .build();
        try (Response response = HttpStack.client().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return Updates.Result.failure(currentVersion, "HTTP " + response.code());
            }
            String body = response.body() == null ? "" : response.body().string();
            return Updates.evaluate(currentVersion, Updates.parse(body));
        } catch (Throwable failure) {
            Throwable root = failure;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            return Updates.Result.failure(currentVersion, root.getClass().getSimpleName()
                    + (root.getMessage() == null ? "" : "：" + root.getMessage()));
        }
    }

    /** Runs a check off the calling thread; used when a screen simply wants the answer ready. */
    public void checkInBackground(final boolean force) {
        new Thread(new Runnable() {
            @Override public void run() {
                check(force);
            }
        }, "update-check").start();
    }

    /** Sleeps until the cached answer expires; only used by tests of the cache timing. */
    static long timeToLive(boolean ok) {
        return ok ? OK_TTL_MS : FAIL_TTL_MS;
    }

    /** Convenience for callers that only care about the deadline in seconds. */
    static long timeToLiveSeconds(boolean ok) {
        return TimeUnit.MILLISECONDS.toSeconds(timeToLive(ok));
    }
}
