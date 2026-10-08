package com.nukacast.app.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nukacast.app.live.model.LiveCatalog;

import org.junit.Test;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Building the address for a programme that has already been on.
 *
 * <p>The shapes here are the ones free playlists actually use; a wrong time format does not fail loudly on
 * the server, it answers with a stream that starts at the wrong moment or not at all, so the strings are
 * pinned down.
 */
public class CatchupUrlTest {

    private static LiveCatalog.Channel channel(String url, String style, String template) {
        LiveCatalog.Channel channel = new LiveCatalog.Channel();
        channel.name = "CCTV1";
        channel.urls.add(url);
        channel.catchup = style;
        channel.catchupSource = template;
        return channel;
    }

    /** 2026-10-08 20:00:00 local, so the stamps are known numbers. */
    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(TimeZone.getDefault(), Locale.US);
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, 0);
        return calendar.getTimeInMillis();
    }

    @Test public void appendStyleAddsStartAndEnd() {
        LiveCatalog.Channel channel = channel("http://host/live.m3u8", "append", "");
        String url = CatchupUrl.url(channel, at(2026, 10, 8, 20, 0), at(2026, 10, 8, 21, 0),
                at(2026, 10, 8, 22, 30));
        assertTrue(url, url.startsWith("http://host/live.m3u8?start="));
        assertTrue(url, url.contains("&end="));
        // The stamp is the local wall clock, which is what these servers match against their own listings.
        assertTrue(url, url.contains("start=" + CatchupUrl.stamp(at(2026, 10, 8, 20, 0))));
        assertTrue(url, url.contains("end=" + CatchupUrl.stamp(at(2026, 10, 8, 21, 0))));
    }

    @Test public void appendStyleRespectsAnExistingQuery() {
        LiveCatalog.Channel channel = channel("http://host/live.m3u8?token=abc", "append", "");
        String url = CatchupUrl.url(channel, at(2026, 10, 8, 20, 0), at(2026, 10, 8, 21, 0),
                at(2026, 10, 8, 22, 0));
        assertTrue(url, url.startsWith("http://host/live.m3u8?token=abc&start="));
    }

    @Test public void defaultStylePassesUtcSeconds() {
        LiveCatalog.Channel channel = channel("http://host/live.m3u8", "default", "");
        long start = at(2026, 10, 8, 20, 0);
        long now = at(2026, 10, 8, 22, 0);
        String url = CatchupUrl.url(channel, start, start + 3600_000L, now);
        assertEquals("http://host/live.m3u8?utc=" + (start / 1000) + "&lutc=" + (now / 1000), url);
    }

    @Test public void templateFillsEveryPlaceholder() {
        LiveCatalog.Channel channel = channel("http://host/live.m3u8", "append",
                "http://host/timeshift?start={start}&end={end}&utc={utc}&utcend={utcend}&lutc={lutc}"
                        + "&dur={duration}");
        long start = at(2026, 10, 8, 20, 0);
        long end = at(2026, 10, 8, 21, 30);
        long now = at(2026, 10, 8, 22, 0);
        String url = CatchupUrl.url(channel, start, end, now);
        assertEquals("http://host/timeshift?start=" + CatchupUrl.stamp(start)
                + "&end=" + CatchupUrl.stamp(end)
                + "&utc=" + (start / 1000)
                + "&utcend=" + (end / 1000)
                + "&lutc=" + (now / 1000)
                + "&dur=" + (90 * 60), url);
    }

    @Test public void aTemplateThatIsOnlyAQueryIsAppendedToTheChannel() {
        LiveCatalog.Channel channel = channel("http://host/live.m3u8", "", "?playseek={start}-{end}");
        long start = at(2026, 10, 8, 20, 0);
        String url = CatchupUrl.url(channel, start, start + 60_000L, start + 3600_000L);
        assertEquals("http://host/live.m3u8?playseek=" + CatchupUrl.stamp(start) + "-"
                + CatchupUrl.stamp(start + 60_000L), url);
    }

    @Test public void anUnknownStyleBuildsNothingRatherThanSomethingWrong() {
        LiveCatalog.Channel channel = channel("http://host/live.m3u8", "someserver-2018", "");
        assertEquals("", CatchupUrl.url(channel, at(2026, 10, 8, 20, 0), 0, at(2026, 10, 8, 22, 0)));
    }

    @Test public void noSupportIsSaidInWords() {
        LiveCatalog.Channel plain = channel("http://host/live.m3u8", "", "");
        assertFalse(CatchupUrl.canCatchUp(plain));
        assertEquals("这个频道不支持回看",
                CatchupUrl.reason(plain, at(2026, 10, 8, 20, 0), at(2026, 10, 8, 22, 0)));
    }

    @Test public void goingTooFarBackIsSaidInWords() {
        LiveCatalog.Channel channel = channel("http://host/live.m3u8", "append", "");
        channel.catchupDays = 3;
        long start = at(2026, 10, 1, 20, 0);
        long now = at(2026, 10, 8, 20, 0);
        assertTrue(CatchupUrl.reason(channel, start, now).contains("超出可回看天数"));
        assertTrue(CatchupUrl.canCatchUp(channel));
    }

    @Test public void withinTheWindowThereIsNoComplaint() {
        LiveCatalog.Channel channel = channel("http://host/live.m3u8", "append", "");
        channel.catchupDays = 7;
        assertEquals("", CatchupUrl.reason(channel, at(2026, 10, 8, 18, 0), at(2026, 10, 8, 20, 0)));
        // The first address is the one these servers expect the times on; the others are mirrors of it.
        channel.urls.add("http://mirror/live.m3u8");
        assertTrue(CatchupUrl.url(channel, at(2026, 10, 8, 18, 0), 0, at(2026, 10, 8, 20, 0))
                .startsWith("http://host/live.m3u8?"));
    }

    @Test public void endOfFallsBackToAnHourWhenTheListingOmitsIt() {
        com.nukacast.app.live.model.EpgSchedule.Program program =
                new com.nukacast.app.live.model.EpgSchedule.Program();
        program.start = "2026-10-08 20:00";
        program.end = "";
        long start = EpgNow.parse(program.start, "");
        assertEquals(start + 3600_000L, CatchupUrl.endOf(program, "", start));
        program.end = "2026-10-08 21:30";
        assertEquals(EpgNow.parse(program.end, ""), CatchupUrl.endOf(program, "", start));
    }
}
