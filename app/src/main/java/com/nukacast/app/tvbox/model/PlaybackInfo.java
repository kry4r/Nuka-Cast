package com.nukacast.app.tvbox.model;

import java.util.LinkedHashMap;
import java.util.Map;

public final class PlaybackInfo {
    public String siteKey = "";
    public String title = "";
    public String url = "";
    public int parse;
    public boolean direct;
    public String sniffUrl = "";
    public String error = "";
    public Map<String, String> headers = new LinkedHashMap<String, String>();
}
