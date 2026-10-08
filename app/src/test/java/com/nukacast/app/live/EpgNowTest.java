package com.nukacast.app.live;

import com.nukacast.app.live.model.EpgSchedule;

import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The live page shows one line of EPG per focused channel, so picking "now" and "next" out of a feed
 * has to be right even when the times are written in one of the several formats sites use.
 */
public class EpgNowTest {
    private static final String DATE = "2026-10-07";

    @Test
    public void picksTheProgrammeAiringNow() {
        EpgSchedule schedule = schedule(program("早间新闻", "08:00", "09:00"),
                program("午间新闻", "12:00", "13:00"), program("晚间新闻", "20:00", "21:00"));
        long now = at("2026-10-07 12:30");
        EpgNow.Slot current = EpgNow.current(schedule, now);
        assertNotNull(current);
        assertEquals("午间新闻", current.title);
        assertEquals("12:00", current.startLabel());
        assertEquals("晚间新闻", EpgNow.next(schedule, now).title);
    }

    @Test
    public void aGapBetweenProgrammesHasNoCurrentShow() {
        EpgSchedule schedule = schedule(program("早间新闻", "08:00", "09:00"),
                program("晚间新闻", "20:00", "21:00"));
        long now = at("2026-10-07 13:00");
        assertNull(EpgNow.current(schedule, now));
        // The next programme is still known, which is what the label needs.
        assertEquals("晚间新闻", EpgNow.next(schedule, now).title);
        assertTrue(EpgNow.label(schedule, now).startsWith("接下来 20:00 晚间新闻"));
    }

    @Test
    public void readsTheTimeFormatsFeedsUse() {
        assertTrue(EpgNow.parse("20:00") > 0);
        assertTrue(EpgNow.parse("2026-10-07 20:00:00") > 0);
        assertTrue(EpgNow.parse("2026/10/07 20:00") > 0);
        assertTrue(EpgNow.parse("20261007200000") > 0);
        assertTrue(EpgNow.parse("2026-10-07T20:00:00+08:00") > 0);
        assertEquals(0L, EpgNow.parse(""));
        assertEquals(0L, EpgNow.parse(null));
        assertEquals(0L, EpgNow.parse("稍后播出"));
    }

    @Test
    public void labelShowsNowAndNextInOneLine() {
        EpgSchedule schedule = schedule(program("晚间新闻", "20:00", "21:00"),
                program("电视剧场", "21:00", "22:00"));
        String label = EpgNow.label(schedule, at("2026-10-07 20:30"));
        assertEquals("正在播出 20:00 晚间新闻　|　接下来 21:00 电视剧场", label);
        // The last programme of the day says so instead of leaving a dangling separator.
        assertEquals("正在播出 21:00 电视剧场（今日节目单结束）",
                EpgNow.label(schedule, at("2026-10-07 21:30")));
    }

    @Test
    public void stripsTheWatermarkFreeServicesAppend() {
        assertEquals("爱在山海间16/20", EpgParser.cleanTitle("爱在山海间16/20 --免费使用"));
        assertEquals("经济信息联播", EpgParser.cleanTitle("经济信息联播-免费使用"));
        // A title that merely mentions the word keeps its own text.
        assertEquals("免费使用说明", EpgParser.cleanTitle("免费使用说明"));
        assertEquals("", EpgParser.cleanTitle(null));
    }

    @Test
    public void slotsAreSortedAndUsableTimesOnly() {
        // Mixed time formats plus a row without times, as sites send them.
        EpgSchedule mixed = schedule(
                program("晚间新闻", "21:00", "22:00"),
                program("午间新闻", "12:00", "12:30"),
                program("没有时间的节目", "", ""));
        java.util.List<EpgNow.Slot> slots = EpgNow.slots(mixed);
        assertEquals(2, slots.size());
        assertEquals("午间新闻", slots.get(0).title);
        assertEquals("晚间新闻", slots.get(1).title);
        assertEquals("12:00", slots.get(0).startLabel());
        assertEquals("12:30", slots.get(0).endLabel());
    }

    @Test
    public void aSlotKnowsWhetherItIsOnNow() {
        EpgNow.Slot slot = EpgNow.slots(schedule(program("晚间新闻", "20:00", "21:00"))).get(0);
        assertTrue(slot.isLive(slot.startMs + 1000));
        assertFalse(slot.isLive(slot.startMs - 1000));
        assertFalse(slot.isLive(slot.endMs));
        assertTrue(slot.isPlaceholder() == false);
    }

    @Test
    public void placeholderRowsAreRecognised() {
        EpgNow.Slot slot = EpgNow.slots(
                schedule(program("精彩节目-暂未提供节目预告信息 --免费使用", "20:00", "21:00"))).get(0);
        assertTrue(slot.isPlaceholder());
    }

    @Test
    public void placeholderSchedulesAreRecognised() {
        // What a real service returns for a channel it has no listing for.
        EpgSchedule fake = schedule(
                program("精彩节目-暂未提供节目预告信息 --免费使用", "20:00", "21:00"),
                program("精彩节目-暂未提供节目预告信息 --免费使用", "21:00", "22:00"));
        assertTrue(EpgNow.isPlaceholder(fake));
        // A single real title is enough to make the schedule trustworthy.
        EpgSchedule real = schedule(program("晚间新闻", "20:00", "21:00"),
                program("精彩节目", "21:00", "22:00"));
        assertFalse(EpgNow.isPlaceholder(real));
        assertTrue(EpgNow.isPlaceholder(new EpgSchedule()));
        assertTrue(EpgNow.isPlaceholder(null));
    }

    @Test
    public void anEmptyScheduleProducesNoLabel() {
        assertEquals("", EpgNow.label(new EpgSchedule(), at("2026-10-07 20:30")));
        assertEquals("", EpgNow.label(null, at("2026-10-07 20:30")));
        // A programme without parsable times is ignored rather than shown as "now".
        EpgSchedule broken = schedule(program("无时间", "", ""));
        assertNull(EpgNow.current(broken, at("2026-10-07 20:30")));
    }

    @Test
    public void bareTimesBelongToTheSchedulesOwnDate() {
        // The bug this pins: a guide fetched for another day was read as if its times were today's, so
        // "what is on now" was answered against the wrong date (and CI, whose clock is a day off the
        // fixed date in these tests, failed on it).
        EpgSchedule other = schedule(program("晚间新闻", "20:00", "21:00"));
        other.date = "2026-10-09";
        EpgNow.Slot slot = EpgNow.slots(other).get(0);
        assertEquals(at("2026-10-09 20:00"), slot.startMs);
        assertEquals(at("2026-10-09 21:00"), slot.endMs);
        // Reading the same schedule on the 9th finds it, reading it on the 7th does not.
        assertEquals("晚间新闻", EpgNow.current(other, at("2026-10-09 20:30")).title);
        assertNull(EpgNow.current(other, at("2026-10-07 20:30")));
    }

    @Test
    public void aScheduleWithoutADateFallsBackToToday() {
        EpgSchedule undated = schedule(program("晚间新闻", "20:00", "21:00"));
        undated.date = "";
        EpgNow.Slot slot = EpgNow.slots(undated).get(0);
        java.util.Calendar today = java.util.Calendar.getInstance();
        java.util.Calendar at20 = java.util.Calendar.getInstance();
        at20.setTimeInMillis(slot.startMs);
        assertEquals(today.get(java.util.Calendar.DAY_OF_MONTH), at20.get(java.util.Calendar.DAY_OF_MONTH));
        assertEquals(20, at20.get(java.util.Calendar.HOUR_OF_DAY));
    }

    private static EpgSchedule.Program program(String title, String start, String end) {
        EpgSchedule.Program program = new EpgSchedule.Program();
        program.title = title;
        program.start = start;
        program.end = end;
        return program;
    }

    private static EpgSchedule schedule(EpgSchedule.Program... programs) {
        EpgSchedule schedule = new EpgSchedule();
        schedule.channel = "cctv1";
        schedule.date = DATE;
        for (EpgSchedule.Program program : programs) schedule.programs.add(program);
        return schedule;
    }

    private static long at(String time) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(time).getTime();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
