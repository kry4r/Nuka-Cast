package com.nukacast.app.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.text.CueGroup;

import org.junit.Test;

import java.util.Collections;

/**
 * The player menu describes audio and subtitle tracks by name, so these names are what a viewer reads
 * when deciding which track to watch.
 */
public class PlayerControllerTracksTest {

    private static String audio(String language, String label, String mime, int channels) {
        return PlayerController.trackName(label, language, mime, channels, C.TRACK_TYPE_AUDIO);
    }

    private static String text(String language, String label, String mime) {
        return PlayerController.trackName(label, language, mime, 0, C.TRACK_TYPE_TEXT);
    }

    @Test
    public void audioTrackNamesCarryLanguageCodecAndChannels() {
        assertEquals("中文 · AAC 2声道", audio("zh", null, "audio/mp4a-latm", 2));
        assertEquals("英语 · AC-3 6声道", audio("en", null, "audio/ac-3", 6));
        // A playlist's own label wins: that is what the viewer sees in the source.
        assertEquals("国语 · AAC 2声道", audio("zh", "国语", "audio/mp4a-latm", 2));
        // Mono is the normal case and does not need saying.
        assertEquals("日语 · Opus", audio("ja", null, "audio/opus", 1));
    }

    @Test
    public void subtitleTrackNamesFallBackToAReadableWord() {
        assertEquals("中文 · WebVTT", text("zh-Hans", null, "text/vtt"));
        assertEquals("繁体中文 · TTML", text("zh-Hant", null, "application/ttml+xml"));
        // No language and no label: the type name, rather than an empty menu entry.
        assertEquals("字幕 · WebVTT", text(null, null, "text/vtt"));
        assertEquals("音轨", audio(null, null, null, 1));
    }

    @Test
    public void languageNamesCoverTheLanguagesChineseSourcesShip() {
        assertEquals("中文", PlayerController.languageName("zh-CN"));
        assertEquals("繁体中文", PlayerController.languageName("zh-TW"));
        assertEquals("粤语", PlayerController.languageName("yue"));
        assertEquals("英语", PlayerController.languageName("en-US"));
        assertEquals("日语", PlayerController.languageName("jp"));
        assertEquals("韩语", PlayerController.languageName("ko"));
        // Anything unknown is passed through rather than hidden: "pt-BR" tells the viewer more than "".
        assertEquals("pt-BR", PlayerController.languageName("pt-BR"));
    }

    @Test
    public void cueTextKeepsEveryLineAndDropsEmptyOnes() {
        CueGroup group = new CueGroup(java.util.Arrays.asList(
                new Cue("第一行"), new Cue("   "), new Cue("第二行")), 0L);
        assertEquals("第一行\n第二行", PlayerController.cueText(group));
        assertTrue(PlayerController.cueText(null).isEmpty());
        assertTrue(PlayerController.cueText(new CueGroup(java.util.Collections.emptyList(), 0L)).isEmpty());
        assertTrue(PlayerController.cueText(null).isEmpty());
    }

    @Test
    public void subtitleCodecsAreNamed() {
        // The menu shows the codec, which is how a viewer tells a text track from a picture one.
        assertEquals("WebVTT", PlayerController.codecName("text/vtt"));
        assertEquals("SRT", PlayerController.codecName("application/x-subrip"));
        assertEquals("TTML", PlayerController.codecName("application/ttml+xml"));
        // A cue with no text (a bitmap subtitle, DVB or PGS, or a positioning-only cue) adds no line.
        assertTrue(PlayerController.cueText(
                new CueGroup(Collections.singletonList(new Cue("")), 0L)).isEmpty());
    }
}
