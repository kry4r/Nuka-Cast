package com.nukacast.app.player;

import com.nukacast.app.tvbox.model.MediaDetail;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The automatic line switch is what turns "点进去放不了" into a picture, so its rules are pinned here.
 */
public class LinePickerTest {
    @Test
    public void movesToTheNextLineAndWrapsAround() {
        MediaDetail detail = detail("gsm3u8", "gsyun", "ffm3u8");
        assertEquals("gsyun", LinePicker.next(detail, "gsm3u8").name);
        assertEquals("ffm3u8", LinePicker.next(detail, "gsyun").name);
        // The last line failing must fall back to the first, not give up.
        assertEquals("gsm3u8", LinePicker.next(detail, "ffm3u8").name);
    }

    @Test
    public void skipsLinesWithoutEpisodes() {
        MediaDetail detail = new MediaDetail();
        detail.playSources.add(line("empty"));
        detail.playSources.add(line("broken"));
        detail.playSources.add(line("gsm3u8", "第1集-http://gsm3u8/1.m3u8"));
        assertEquals("gsm3u8", LinePicker.next(detail, "broken").name);
    }

    @Test
    public void refusesWhenThereIsNoAlternative() {
        assertNull(LinePicker.next(detail("only"), "only"));
        assertNull(LinePicker.next(null, "x"));
        assertNull(LinePicker.next(new MediaDetail(), "x"));
    }

    @Test
    public void keepsTheSameEpisodeWhenTheIdsAgree() {
        MediaDetail detail = detail("gsm3u8", "gsyun");
        MediaDetail.PlaySource gsyun = LinePicker.next(detail, "gsm3u8");
        // Lines publish different ids for the same episode, so an unknown id falls back to episode one
        // rather than failing: playing the wrong episode is worse than playing none at all.
        assertEquals("第1集", LinePicker.episodeOf(gsyun, "第2集-http://gsm3u8/2.m3u8").name);
        assertEquals("第2集", LinePicker.episodeOf(gsyun, "第2集-http://gsyun/2.m3u8").name);
        assertNull(LinePicker.episodeOf(null, "x"));
    }

    @Test
    public void advancesToTheNextEpisodeAndStopsAtTheEnd() {
        MediaDetail.PlaySource line = line("gsm3u8", "第1集-a", "第2集-b");
        assertEquals("第2集", LinePicker.nextEpisode(line, "第1集-a").name);
        // The last episode must not wrap around: the series is over.
        assertNull(LinePicker.nextEpisode(line, "第2集-b"));
        assertNull(LinePicker.nextEpisode(null, "x"));
        // An unknown id (after a line switch) resumes at the second episode rather than restarting.
        assertEquals("第2集", LinePicker.nextEpisode(line, "unknown").name);
    }

    @Test
    public void stepsEpisodesWithinBounds() {
        MediaDetail.PlaySource line = line("gsm3u8", "第1集-a", "第2集-b", "第3集-c");
        assertEquals("第2集", LinePicker.stepEpisode(line, "第1集-a", 1).name);
        assertEquals("第3集", LinePicker.stepEpisode(line, "第2集-b", 2).name);
        // Past either end there is nothing to do: the menu must not wrap to the other side.
        assertNull(LinePicker.stepEpisode(line, "第1集-a", -1));
        assertNull(LinePicker.stepEpisode(line, "第3集-c", 1));
        assertNull(LinePicker.stepEpisode(line, "第3集-c", 0));
        assertNull(LinePicker.stepEpisode(null, "x", 1));
    }

    @Test
    public void fallsBackToTheFirstLine() {
        MediaDetail detail = detail("gsm3u8", "gsyun");
        assertEquals("gsyun", LinePicker.lineOf(detail, "gsyun").name);
        assertEquals("gsm3u8", LinePicker.lineOf(detail, "gone").name);
        assertNull(LinePicker.lineOf(null, "x"));
    }

    private static MediaDetail detail(String... lineNames) {
        MediaDetail detail = new MediaDetail();
        for (String name : lineNames) {
            detail.playSources.add(line(name, "第1集-http://" + name + "/1.m3u8",
                    "第2集-http://" + name + "/2.m3u8"));
        }
        return detail;
    }

    private static MediaDetail.PlaySource line(String name, String... episodeIds) {
        MediaDetail.PlaySource source = new MediaDetail.PlaySource();
        source.name = name;
        for (String id : episodeIds) {
            MediaDetail.Episode episode = new MediaDetail.Episode();
            episode.name = id.substring(0, id.indexOf('-'));
            episode.id = id;
            source.episodes.add(episode);
        }
        return source;
    }
}
