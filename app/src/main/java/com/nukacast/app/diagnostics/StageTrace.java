package com.nukacast.app.diagnostics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bounded, in-memory trace of the pipeline stages that matter for remote diagnosis:
 * {@code fetch_config -> decode -> persist} for a source, {@code load_plugin -> plugin_init} for a
 * spider, {@code native_load -> native_listen -> mdns_publish -> codec_config -> first_output} for
 * AirPlay. Records are cheap snapshots, not logs, and the newest {@value #MAX_RECORDS} are kept.
 *
 * <p>A trace record is a lead, not a verdict: "last stage before the process disappeared" is
 * evidence to collect together with logcat/native/ANR data, not proof of the root cause.
 */
public final class StageTrace {
    public static final String RESULT_RUNNING = "running";
    public static final String RESULT_OK = "ok";
    public static final String RESULT_FAILED = "failed";
    private static final int MAX_RECORDS = 48;
    private static final StageTrace INSTANCE = new StageTrace();

    public static final class Record {
        public String scope = "";
        public String subject = "";
        public String stage = "";
        public String result = RESULT_RUNNING;
        public long startedAt;
        public long updatedAt;
        public long elapsedMs;
        public String detail = "";
        public String errorCode = "";
        public String rootCauseClass = "";
        public int generation;
    }

    /** Mutable handle for one operation; safe to use from the thread that started it. */
    public static final class Trace {
        private final Record record;

        private Trace(Record record) {
            this.record = record;
        }

        public void stage(String stage) {
            synchronized (INSTANCE) {
                long now = System.currentTimeMillis();
                record.stage = safe(stage);
                record.startedAt = now;
                record.updatedAt = now;
                record.elapsedMs = 0L;
                record.result = RESULT_RUNNING;
            }
        }

        public void detail(String detail) {
            synchronized (INSTANCE) {
                record.detail = safe(detail);
                record.updatedAt = System.currentTimeMillis();
            }
        }

        public void success() {
            finish(RESULT_OK, null, "");
        }

        public void failure(Throwable error) {
            finish(RESULT_FAILED, error, "");
        }

        public void failure(String errorCode, String detail) {
            synchronized (INSTANCE) {
                record.result = RESULT_FAILED;
                record.errorCode = safe(errorCode);
                record.detail = safe(detail);
                record.updatedAt = System.currentTimeMillis();
                record.elapsedMs = Math.max(0L, record.updatedAt - record.startedAt);
            }
        }

        private void finish(String result, Throwable error, String detail) {
            synchronized (INSTANCE) {
                long now = System.currentTimeMillis();
                record.result = result;
                record.updatedAt = now;
                record.elapsedMs = Math.max(0L, now - record.startedAt);
                if (error != null) {
                    record.errorCode = ErrorCodes.of(error);
                    record.rootCauseClass = ErrorCodes.root(error).getClass().getName();
                    record.detail = ErrorCodes.message(error);
                } else if (detail != null && !detail.isEmpty()) {
                    record.detail = detail;
                }
            }
        }
    }

    private final Deque<Record> records = new ArrayDeque<Record>();
    private int generation;

    private StageTrace() {}

    public static StageTrace get() { return INSTANCE; }

    public static Trace start(String scope, String subject) {
        return INSTANCE.begin(scope, subject);
    }

    /** Fire-and-forget component marker without an operation handle. */
    public static void component(String scope, String subject, String stage, boolean ok,
                                 String detail) {
        Trace trace = INSTANCE.begin(scope, subject);
        trace.stage(stage);
        if (ok) {
            trace.detail(detail);
            trace.success();
        } else {
            trace.failure("component_unavailable", detail);
        }
    }

    public static void componentFailure(String scope, String subject, String stage,
                                        Throwable error) {
        Trace trace = INSTANCE.begin(scope, subject);
        trace.stage(stage);
        trace.failure(error);
    }

    /** Newest first so the UI shows the most recent failure without sorting. */
    public static List<Record> snapshot() {
        return INSTANCE.copy();
    }

    public static void clear() {
        INSTANCE.reset();
    }

    private synchronized Trace begin(String scope, String subject) {
        Record record = new Record();
        record.scope = safe(scope);
        record.subject = safe(subject);
        record.stage = "start";
        record.startedAt = System.currentTimeMillis();
        record.updatedAt = record.startedAt;
        record.generation = ++generation;
        records.addFirst(record);
        while (records.size() > MAX_RECORDS) records.removeLast();
        return new Trace(record);
    }

    private synchronized List<Record> copy() {
        return new ArrayList<Record>(records);
    }

    private synchronized void reset() {
        records.clear();
        generation = 0;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
