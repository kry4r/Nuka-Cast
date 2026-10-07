package com.nukacast.app.live;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Playlist names carry quality markers that EPG services do not know about, so lookups are tried with
 * progressively looser names. Without this every channel came back with "no programme list".
 */
public class EpgChannelIdTest {
    @Test
    public void stripsQualityMarkersAndKeepsThePlainName() {
        List<String> candidates = EpgChannelId.candidates("CCTV-13 (1080p)");
        assertEquals("CCTV-13 (1080p)", candidates.get(0));
        assertTrue(candidates.toString(), candidates.contains("CCTV-13"));
        assertTrue(candidates.toString(), candidates.contains("CCTV13"));
    }

    @Test
    public void handlesChineseDecorations() {
        List<String> candidates = EpgChannelId.candidates("湖南卫视高清");
        assertTrue(candidates.toString(), candidates.contains("湖南卫视"));
        List<String> cctv = EpgChannelId.candidates("CCTV-1 综合");
        assertTrue(cctv.toString(), cctv.contains("CCTV-1"));
        assertTrue(cctv.toString(), cctv.contains("CCTV1"));
    }

    @Test
    public void keepsAPlainNameUnchangedAndDeduplicates() {
        assertEquals(java.util.Arrays.asList("CCTV1"), EpgChannelId.candidates("CCTV1"));
        List<String> candidates = EpgChannelId.candidates("凤凰卫视");
        assertEquals(1, candidates.size());
        assertTrue(EpgChannelId.candidates("").isEmpty());
        assertTrue(EpgChannelId.candidates(null).isEmpty());
    }
}
