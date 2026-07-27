package com.nukacast.app.live;

import com.nukacast.app.live.model.EpgSchedule;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class EpgParserTest {
    @Test
    public void parsesCommonTvBoxJsonEpg() throws Exception {
        String body = "{\"channel_name\":\"CCTV-1\",\"epg_data\":[" +
                "{\"title\":\"新闻30分\",\"start\":\"12:00\",\"end\":\"12:30\"}," +
                "{\"title\":\"今日说法\",\"start\":\"12:30\",\"end\":\"13:00\"}]}";

        EpgSchedule schedule = EpgParser.parse(body, "CCTV-1", "2026-07-27");

        assertEquals("CCTV-1", schedule.channel);
        assertEquals(2, schedule.programs.size());
        assertEquals("新闻30分", schedule.programs.get(0).title);
    }
}
