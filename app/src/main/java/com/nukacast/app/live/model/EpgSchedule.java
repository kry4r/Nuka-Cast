package com.nukacast.app.live.model;

import java.util.ArrayList;
import java.util.List;

public final class EpgSchedule {
    public String channel = "";
    public String date = "";
    public final List<Program> programs = new ArrayList<Program>();

    /**
     * Why there is no listing, when the reason is not the channel itself.
     *
     * <p>Empty means "the service answered and simply has no programmes for this channel"; a message here
     * means the guide could not be fetched at all, which the page must say differently — otherwise a
     * broken guide service looks like a broken playlist.
     */
    public String error = "";

    public static final class Program {
        public String title = "";
        public String start = "";
        public String end = "";
        public String description = "";
    }
}
