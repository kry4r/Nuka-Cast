package com.nukacast.app.live.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LiveCatalog {
    public String sourceId = "";
    public String sourceName = "";
    public final List<Group> groups = new ArrayList<Group>();

    public static final class Group {
        public String name = "";
        public final List<Channel> channels = new ArrayList<Channel>();
    }

    public static final class Channel {
        public String id = "";
        public String name = "";
        public String epgId = "";
        public String logo = "";
        public String group = "";
        /** Every mirror of this channel, in the order the playlist listed them. */
        public final List<String> urls = new ArrayList<String>();
        public final Map<String, String> headers = new LinkedHashMap<String, String>();
        /** Catch-up support as the playlist describes it: {@code append}, {@code default}, {@code shift}… */
        public String catchup = "";
        /** The URL template for catch-up playback, with {@code {utc}} / {@code {start}} placeholders. */
        public String catchupSource = "";
        /** How many days back the playlist says catch-up goes; 7 when it does not say. */
        public int catchupDays = 7;
    }
}
