package com.nukacast.app.tvbox.model;

import java.util.ArrayList;
import java.util.List;

public final class MediaDetail {
    public String siteKey = "";
    public String siteName = "";
    public String sourceId = "";
    public String vodId = "";
    public String name = "";
    public String poster = "";
    public String remarks = "";
    public String year = "";
    public String area = "";
    public String typeName = "";
    public String actor = "";
    public String director = "";
    public String score = "";
    public String plot = "";
    public final List<PlaySource> playSources = new ArrayList<PlaySource>();

    public static final class PlaySource {
        public String name = "";
        public final List<Episode> episodes = new ArrayList<Episode>();
    }

    public static final class Episode {
        public String name = "";
        public String id = "";
    }
}
