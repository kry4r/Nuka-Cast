package com.github.catvod.crawler;

import android.content.Context;

import com.nukacast.app.net.HttpStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.OkHttpClient;

/** ABI-compatible base class used by common TVBox Spider jars. */
public class Spider {
    protected Context context;

    public void init(Context context) throws Exception {
        this.context = context;
    }

    public void init(Context context, String extend) throws Exception {
        init(context);
    }

    public String homeContent(boolean filter) throws Exception { return "{}"; }
    public String homeVideoContent() throws Exception { return "{}"; }
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception { return "{}"; }
    public String detailContent(List<String> ids) throws Exception { return "{}"; }
    public String searchContent(String key, boolean quick) throws Exception { return "{}"; }
    public String searchContent(String key, boolean quick, String page) throws Exception {
        return searchContent(key, quick);
    }
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception { return "{}"; }
    public boolean manualVideoCheck() throws Exception { return false; }
    public boolean isVideoFormat(String url) throws Exception { return false; }
    public Object[] proxyLocal(Map<String, String> params) throws Exception { return new Object[0]; }
    public void destroy() {}

    public OkHttpClient client() {
        return HttpStack.client();
    }
}
