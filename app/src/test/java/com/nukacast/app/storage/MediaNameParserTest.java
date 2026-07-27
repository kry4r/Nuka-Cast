package com.nukacast.app.storage;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class MediaNameParserTest {
    @Test
    public void parsesCommonLibraryNames() {
        MediaNameParser.ParsedName series = MediaNameParser.parse(
                "Shows/The.Last.of.Us.S02E03.2160p.WEB-DL.mkv");
        assertEquals("The Last of Us", series.title);
        assertEquals("电视剧", series.typeName);
        assertEquals(2, series.season);
        assertEquals(3, series.episode);

        MediaNameParser.ParsedName movie = MediaNameParser.parse(
                "Movies/Interstellar (2014) [BluRay 1080p].mp4");
        assertEquals("Interstellar", movie.title);
        assertEquals("2014", movie.year);
        assertEquals("电影", movie.typeName);
        assertTrue(MediaNameParser.isVideo("INTERSTELLAR.MKV"));
    }
}
