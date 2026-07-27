package com.nukacast.app.live.model;

import java.util.ArrayList;
import java.util.List;

public final class EpgSchedule {
    public String channel = "";
    public String date = "";
    public final List<Program> programs = new ArrayList<Program>();

    public static final class Program {
        public String title = "";
        public String start = "";
        public String end = "";
        public String description = "";
    }
}
