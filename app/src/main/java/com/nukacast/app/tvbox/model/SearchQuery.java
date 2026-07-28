package com.nukacast.app.tvbox.model;

import java.util.ArrayList;
import java.util.List;

public final class SearchQuery {
    public String keyword = "";
    public String sourceId = "";
    public String contentType = "";
    public String year = "";
    public String region = "";
    public List<String> siteKeys = new ArrayList<String>();
    public int page = 1;
    public int pageSize = 60;
}
