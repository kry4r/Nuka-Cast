package com.github.catvod.crawler;

import android.annotation.SuppressLint;
import android.content.Context;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Dns;
import okhttp3.OkHttpClient;

/** ABI-compatible base class used by common TVBox Spider jars. */
public class Spider {
    public static JSONObject empty = new JSONObject();
    public String siteKey;

    @SuppressLint("StaticFieldLeak")
    protected static Context mContext;
    /** Kept for older spiders that used the early NukaCast compatibility field. */
    protected Context context;

    public void init(Context context) {
        mContext = context;
        this.context = context;
    }

    public void init(Context context, String extend) {
        init(context);
    }

    public void initApi(SpiderApi api) {}

    public String homeContent(boolean filter) { return ""; }
    public String homeVideoContent() { return ""; }
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) { return ""; }
    public String detailContent(List<String> ids) { return ""; }
    public String searchContent(String key, boolean quick) { return ""; }
    public String searchContent(String key, boolean quick, String page) {
        return searchContent(key, quick);
    }
    public String playerContent(String flag, String id, List<String> vipFlags) { return ""; }
    public boolean manualVideoCheck() { return false; }
    public boolean isVideoFormat(String url) { return false; }
    public String liveContent(String url) { return ""; }

    public static Dns safeDns() {
        return com.github.catvod.net.OkHttp.dns();
    }

    public static OkHttpClient client() {
        return com.github.catvod.net.OkHttp.client();
    }

    public void cancelByTag() {}
    public void destroy() {}

    public Object[] proxyLocal(Map<String, String> params) { return null; }

    public Object[] proxy(Map<String, String> params) {
        return proxyLocal(params);
    }

    public String action(String action) { return null; }
}
