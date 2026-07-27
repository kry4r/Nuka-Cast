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
        public final List<String> urls = new ArrayList<String>();
        public final Map<String, String> headers = new LinkedHashMap<String, String>();
    }
}
