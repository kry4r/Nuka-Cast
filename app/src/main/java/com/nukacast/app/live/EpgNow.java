package com.nukacast.app.live;

import com.nukacast.app.live.model.EpgSchedule;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Picks what a channel is showing now and next out of an EPG schedule.
 *
 * <p>The TV live page needs one line per channel; the schedule itself is a list of times that sites
 * write in several formats ({@code 20:00}, {@code 2026-10-07 20:00:00}, ISO with a T). Parsing and
 * picking are kept here so they can be tested without a device.
 */
public final class EpgNow {
    private EpgNow() {}

    /** A programme with its parsed start/end, or null when the times could not be read. */
    public static final class Slot {
        public final String title;
        public final String description;
        public final long startMs;
        public final long endMs;

        Slot(String title, String description, long startMs, long endMs) {
            this.title = title;
            this.description = description == null ? "" : description;
            this.startMs = startMs;
            this.endMs = endMs;
        }

        public boolean isValid() {
            return title != null && !title.isEmpty() && endMs > startMs;
        }

        /** Start time as {@code HH:mm}, which is all a one-line label has room for. */
        public String startLabel() {
            return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(startMs));
        }

        public String endLabel() {
            return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(endMs));
        }

        /** True while this programme is the one airing at {@code nowMs}. */
        public boolean isLive(long nowMs) {
            return nowMs >= startMs && nowMs < endMs;
        }

        /** True for the "no guide information" rows a service sends instead of a listing. */
        public boolean isPlaceholder() {
            String lower = title == null ? "" : title.toLowerCase(Locale.US);
            for (String marker : PLACEHOLDER_MARKERS) {
                if (lower.contains(marker)) return true;
            }
            return false;
        }
    }

    /**
     * Every readable programme of a schedule, in time order.
     *
     * <p>Used by the full-day guide; entries without a usable time are dropped rather than shown as if
     * they were scheduled.
     */
    public static List<Slot> slots(EpgSchedule schedule) {
        List<Slot> slots = new ArrayList<Slot>();
        if (schedule == null) return slots;
        for (EpgSchedule.Program program : schedule.programs) {
            Slot slot = slot(program);
            if (slot != null && slot.isValid()) slots.add(slot);
        }
        Collections.sort(slots, new Comparator<Slot>() {
            @Override public int compare(Slot left, Slot right) {
                return Long.compare(left.startMs, right.startMs);
            }
        });
        return slots;
    }

    /**
     * Markers services use when they have no real listing for a channel.
     *
     * <p>Measured: this service answers every unknown channel with 24 identical placeholder slots
     * ("精彩节目-暂未提供节目预告信息"), which would otherwise be shown as if it were a programme.
     */
    private static final String[] PLACEHOLDER_MARKERS = {
            "暂未提供", "暂无节目", "无节目单", "没有节目", "精彩节目",
            "no information", "not available", "n/a", "tba", "no epg",
    };

    /** True when a schedule carries placeholder slots instead of a real guide. */
    public static boolean isPlaceholder(EpgSchedule schedule) {
        if (schedule == null || schedule.programs.isEmpty()) return true;
        for (EpgSchedule.Program program : schedule.programs) {
            String title = program.title == null ? "" : program.title.trim().toLowerCase(Locale.US);
            boolean marked = false;
            for (String marker : PLACEHOLDER_MARKERS) {
                if (title.contains(marker)) {
                    marked = true;
                    break;
                }
            }
            if (!marked) return false;
        }
        return true;
    }

    /** The programme airing at {@code nowMs}, or null when the schedule does not cover it. */
    public static Slot current(EpgSchedule schedule, long nowMs) {
        if (schedule == null) return null;
        for (EpgSchedule.Program program : schedule.programs) {
            Slot slot = slot(program);
            if (slot.isValid() && nowMs >= slot.startMs && nowMs < slot.endMs) return slot;
        }
        return null;
    }

    /** The programme after the one airing now (or the first future one), or null. */
    public static Slot next(EpgSchedule schedule, long nowMs) {
        if (schedule == null) return null;
        Slot current = current(schedule, nowMs);
        Slot best = null;
        for (EpgSchedule.Program program : schedule.programs) {
            Slot slot = slot(program);
            if (!slot.isValid()) continue;
            if (slot.startMs <= nowMs) continue;
            if (current != null && slot.startMs < current.endMs) continue;
            if (best == null || slot.startMs < best.startMs) best = slot;
        }
        return best;
    }

    /** {@code 正在播出 20:00 新闻} plus the following programme, ready for the status line. */
    public static String label(EpgSchedule schedule, long nowMs) {
        Slot current = current(schedule, nowMs);
        Slot next = next(schedule, nowMs);
        if (current == null && next == null) return "";
        StringBuilder text = new StringBuilder();
        if (current != null) {
            text.append("正在播出 ").append(current.startLabel()).append(' ').append(current.title);
        }
        if (next != null) {
            if (text.length() > 0) text.append("　|　");
            text.append("接下来 ").append(next.startLabel()).append(' ').append(next.title);
        } else if (current != null) {
            text.append("（今日节目单结束）");
        }
        return text.toString();
    }

    static Slot slot(EpgSchedule.Program program) {
        long start = parse(program.start);
        long end = parse(program.end);
        return new Slot(program.title, program.description, start, end);
    }

    /**
     * Parses the time formats EPG feeds actually use.
     *
     * @return epoch millis, or 0 when the text cannot be read
     */
    static long parse(String value) {
        if (value == null) return 0L;
        String text = value.trim();
        if (text.isEmpty()) return 0L;
        String[] formats = {
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm",
                "yyyy/MM/dd HH:mm:ss",
                "yyyy/MM/dd HH:mm",
                "yyyyMMddHHmmss",
        };
        for (String format : formats) {
            try {
                Date date = new SimpleDateFormat(format, Locale.US).parse(text);
                if (date != null) return date.getTime();
            } catch (Exception ignored) {
                // Try the next shape.
            }
        }
        // ISO with a T and possibly a zone: only the local wall clock matters for "now".
        String normalised = text.replace('T', ' ').replace('Z', ' ').trim();
        if (normalised.length() > 19) normalised = normalised.substring(0, 19);
        for (String format : new String[] { "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm" }) {
            try {
                Date date = new SimpleDateFormat(format, Locale.US).parse(normalised);
                if (date != null) return date.getTime();
            } catch (Exception ignored) {
                // Give up below.
            }
        }
        // A bare "20:00" belongs to today.
        try {
            Date time = new SimpleDateFormat("HH:mm", Locale.US).parse(text);
            if (time != null) {
                java.util.Calendar calendar = java.util.Calendar.getInstance();
                java.util.Calendar hours = java.util.Calendar.getInstance();
                hours.setTime(time);
                calendar.set(java.util.Calendar.HOUR_OF_DAY, hours.get(java.util.Calendar.HOUR_OF_DAY));
                calendar.set(java.util.Calendar.MINUTE, hours.get(java.util.Calendar.MINUTE));
                calendar.set(java.util.Calendar.SECOND, 0);
                calendar.set(java.util.Calendar.MILLISECOND, 0);
                return calendar.getTimeInMillis();
            }
        } catch (Exception ignored) {
            // Unparsable.
        }
        return 0L;
    }
}
