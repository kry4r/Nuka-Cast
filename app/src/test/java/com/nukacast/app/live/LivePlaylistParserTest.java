package com.nukacast.app.live;

import com.nukacast.app.live.model.LiveCatalog;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class LivePlaylistParserTest {
    @Test
    public void parsesM3uAttributesAndAlternativeUrls() {
        String body = "#EXTM3U\n" +
                "#EXTINF:-1 tvg-id=\"cctv1\" tvg-logo=\"https://img/c1.png\" group-title=\"央视\",CCTV-1 综合\n" +
                "https://one/live.m3u8#https://backup/live.m3u8\n";

        LiveCatalog catalog = LivePlaylistParser.parse(body);

        assertEquals(1, catalog.groups.size());
        assertEquals("央视", catalog.groups.get(0).name);
        assertEquals("cctv1", catalog.groups.get(0).channels.get(0).epgId);
        assertEquals(2, catalog.groups.get(0).channels.get(0).urls.size());
    }

    /**
     * The shape 饭太硬's ITV.txt actually has: the same channel listed once per mirror.
     *
     * <p>Measured on the device before this: CCTV1 appeared three times in the grid, each with one address,
     * so a dead mirror meant "this channel cannot be played" even though two others were listed right
     * below it.
     */
    @Test
    public void mergesMirrorsOfTheSameChannelIntoOneEntry() {
        String body = "央视频道,#genre#\n"
                + "CCTV1,http://mirror-one\n"
                + "CCTV1,http://mirror-two\n"
                + "CCTV1,http://mirror-three\n"
                + "CCTV2,http://other\n";

        LiveCatalog catalog = LivePlaylistParser.parse(body);

        assertEquals(2, catalog.groups.get(0).channels.size());
        LiveCatalog.Channel cctv1 = catalog.groups.get(0).channels.get(0);
        assertEquals("CCTV1", cctv1.name);
        assertEquals(3, cctv1.urls.size());
        assertEquals("http://mirror-one", cctv1.urls.get(0));
        assertEquals("http://mirror-three", cctv1.urls.get(2));
    }

    /** The same channel in a different group is a different entry, and a repeated address is not a mirror. */
    @Test
    public void keepsGroupsApartAndIgnoresRepeatedAddresses() {
        String body = "#EXTM3U\n"
                + "#EXTINF:-1 group-title=\"央视\",CCTV-1\nhttp://one\n"
                + "#EXTINF:-1 group-title=\"央视\",CCTV-1\nhttp://one\n"
                + "#EXTINF:-1 group-title=\"地方\",CCTV-1\nhttp://two\n";

        LiveCatalog catalog = LivePlaylistParser.parse(body);

        assertEquals(2, catalog.groups.size());
        assertEquals(1, catalog.groups.get(0).channels.size());
        assertEquals(1, catalog.groups.get(0).channels.get(0).urls.size());
        assertEquals("http://two", catalog.groups.get(1).channels.get(0).urls.get(0));
    }

    /**
     * One #EXTINF followed by several address lines is how an m3u spells "same channel, three mirrors".
     *
     * <p>Measured on the device: the fixture playlist below arrived as a single channel with a single
     * address, so only the first — a dead one — was ever tried.
     */
    @Test
    public void keepsEveryAddressLineUnderOneExtinf() {
        String body = "#EXTM3U\n"
                + "#EXTINF:-1 tvg-name=\"回退测试台\" group-title=\"测试\",回退测试台\n"
                + "http://host/dead-1.m3u8\n"
                + "http://host/dead-2.m3u8\n"
                + "http://host/good.m3u8\n"
                + "#EXTINF:-1 group-title=\"测试\",第二台\n"
                + "http://host/other.m3u8\n";

        LiveCatalog catalog = LivePlaylistParser.parse(body);

        assertEquals(1, catalog.groups.size());
        assertEquals(2, catalog.groups.get(0).channels.size());
        LiveCatalog.Channel first = catalog.groups.get(0).channels.get(0);
        assertEquals("回退测试台", first.name);
        assertEquals(3, first.urls.size());
        assertEquals("http://host/good.m3u8", first.urls.get(2));
        assertEquals(1, catalog.groups.get(0).channels.get(1).urls.size());
    }

    /**
     * The guide address a playlist declares must be read.
     *
     * <p>Sources ship their own listing this way, and it is the only one that knows their channels: asking a
     * public mirror for a regional channel returns nothing, so the guide is empty for every channel of that
     * source (measured on a fixture playlist before this was read).
     */
    @Test
    public void readsTheGuideAddressThePlaylistDeclares() {
        String body = "#EXTM3U url-tvg=\"http://host/guide.xml\"\n"
                + "#EXTINF:-1 tvg-id=\"one\" group-title=\"测试\",一台\n"
                + "http://host/one.m3u8\n";
        assertEquals("http://host/guide.xml", LivePlaylistParser.parse(body).tvgUrl);

        // And it is optional: a playlist without one leaves the field empty rather than reading a neighbour.
        String plain = "#EXTM3U\n#EXTINF:-1 group-title=\"测试\",一台\nhttp://host/one.m3u8\n";
        assertEquals("", LivePlaylistParser.parse(plain).tvgUrl);

        // The other spelling the same idea travels under.
        String alternative = "#EXTM3U x-tvg-url=\"http://host/other.xml\"\n"
                + "#EXTINF:-1 group-title=\"测试\",一台\nhttp://host/one.m3u8\n";
        assertEquals("http://host/other.xml", LivePlaylistParser.parse(alternative).tvgUrl);
    }

    /** Catch-up as the playlists that have it describe it. */
    @Test
    public void readsCatchUpAttributes() {
        String body = "#EXTM3U\n"
                + "#EXTINF:-1 group-title=\"央视\" catchup=\"default\" catchup-days=\"3\" "
                + "catchup-source=\"http://hotel/timeshift?utc={utc}&lutc={lutc}\",CCTV-1\nhttp://live\n";

        LiveCatalog.Channel channel = LivePlaylistParser.parse(body).groups.get(0).channels.get(0);

        assertEquals("default", channel.catchup);
        assertEquals("http://hotel/timeshift?utc={utc}&lutc={lutc}", channel.catchupSource);
        assertEquals(3, channel.catchupDays);
    }

    @Test
    public void parsesTvBoxTextGroups() {
        String body = "央视频道,#genre#\nCCTV-1,http://one\nCCTV-2,http://two\n" +
                "卫视频道,#genre#\n湖南卫视,http://hunan\n";

        LiveCatalog catalog = LivePlaylistParser.parse(body);

        assertEquals(2, catalog.groups.size());
        assertEquals(2, catalog.groups.get(0).channels.size());
        assertEquals("湖南卫视", catalog.groups.get(1).channels.get(0).name);
    }
}
