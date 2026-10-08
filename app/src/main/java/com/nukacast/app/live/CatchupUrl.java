package com.nukacast.app.live;

import com.nukacast.app.live.model.EpgSchedule;
import com.nukacast.app.live.model.LiveCatalog;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Watch a programme that has already been on, when the playlist says how.
 *
 * <p>Free IPTV playlists declare their catch-up in one of a few shapes: a {@code catchup-source} template, or
 * a {@code catchup} attribute naming the request style the server expects ({@code append},
 * {@code default}). Without this, a programme in the guide that finished ten minutes ago can only be
 * watched from wherever the live stream is now — which is the middle of it, or the next one.
 *
 * <p>The times are the device's wall clock, which is what these servers expect: playlists are built for the
 * viewer's local time ({@code Asia/Shanghai} for the sources this app ships with) and the app runs on the
 * television in the same zone.
 */
public final class CatchupUrl {

    /** The style names meaning "append start and end to the channel's address". */
    private static final String STYLE_APPEND = "append";
    /** The style names meaning "pass the moment in UTC seconds". */
    private static final String STYLE_UTC = "default";

    private CatchupUrl() {}

    /** Whether this channel can be asked for a past programme at all. */
    public static boolean canCatchUp(LiveCatalog.Channel channel) {
        if (channel == null) return false;
        return !channel.catchupSource.isEmpty() || !channel.catchup.isEmpty();
    }

    /**
     * Why this programme cannot be watched again, in words a viewer can act on, or "" when it can.
     *
     * @param channel the channel the programme belongs to
     * @param startMs when the programme started
     * @param nowMs   the current time
     */
    public static String reason(LiveCatalog.Channel channel, long startMs, long nowMs) {
        if (channel == null) return "没有这个频道";
        if (startMs <= 0) return "这个节目没有开始时间，无法回看";
        if (!canCatchUp(channel)) return "这个频道不支持回看";
        long days = (nowMs - startMs) / (24L * 3600 * 1000);
        if (days >= channel.catchupDays) return "超出可回看天数（" + channel.catchupDays + " 天）";
        if (url(channel, startMs, startMs, nowMs).isEmpty()) return "这个频道的回看地址没写完整";
        return "";
    }

    /**
     * The address to play for the programme that started at {@code startMs}.
     *
     * <p>The end is only known from the listing; when it is not given, an hour is used, which is what these
     * servers accept for a start-and-end style request.
     *
     * @return the address, or "" when this channel cannot serve it
     */
    public static String url(LiveCatalog.Channel channel, long startMs, long endMs, long nowMs) {
        if (channel == null || channel.urls.isEmpty() || startMs <= 0) return "";
        String base = channel.urls.get(0);
        long end = endMs > startMs ? endMs : startMs + 3600_000L;

        if (!channel.catchupSource.isEmpty()) {
            return expand(channel.catchupSource, base, startMs, end, nowMs);
        }
        if (STYLE_APPEND.equalsIgnoreCase(channel.catchup)) {
            return base + (base.contains("?") ? "&" : "?")
                    + "start=" + stamp(startMs) + "&end=" + stamp(end);
        }
        if (STYLE_UTC.equalsIgnoreCase(channel.catchup) || "shift".equalsIgnoreCase(channel.catchup)) {
            return base + (base.contains("?") ? "&" : "?")
                    + "utc=" + (startMs / 1000) + "&lutc=" + (nowMs / 1000);
        }
        // A style name we do not know: the server's own convention is unknown, so nothing is invented.
        return "";
    }

    /** Fills a catch-up template: {@code {utc}} and friends, and a bare {@code {}} as the start time. */
    private static String expand(String template, String base, long startMs, long endMs, long nowMs) {
        String value = template;
        if (value.contains("{utcend}")) value = value.replace("{utcend}", String.valueOf(endMs / 1000));
        if (value.contains("{utc}")) value = value.replace("{utc}", String.valueOf(startMs / 1000));
        if (value.contains("{lutc}")) value = value.replace("{lutc}", String.valueOf(nowMs / 1000));
        if (value.contains("{start}")) value = value.replace("{start}", stamp(startMs));
        if (value.contains("{end}")) value = value.replace("{end}", stamp(endMs));
        if (value.contains("{duration}")) {
            value = value.replace("{duration}", String.valueOf((endMs - startMs) / 1000));
        }
        if (value.contains("{}")) value = value.replace("{}", stamp(startMs));
        // A template that is only a query is meant to be appended to the channel's address.
        if (!value.startsWith("http://") && !value.startsWith("https://") && !value.startsWith("rtsp://")) {
            value = base + (value.startsWith("?") || value.startsWith("&") ? "" : "?") + value;
        }
        return value;
    }

    /** {@code yyyyMMddHHmmss} in the viewer's time zone, which is what these servers ask for. */
    static String stamp(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date(millis));
    }

    /**
     * When a programme ended, from its own listing line.
     *
     * <p>The end is not always written; the guide then shows a start and lets the next programme's start be
     * the end.
     */
    public static long endOf(EpgSchedule.Program program, String scheduleDate, long startMs) {
        long end = EpgNow.parse(program.end, scheduleDate);
        return end > startMs ? end : startMs + 3600_000L;
    }
}
