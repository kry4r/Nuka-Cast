package com.github.catvod.net;

import androidx.collection.ArrayMap;

import com.nukacast.app.net.HttpStack;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Network ABI used by CatVod jars, backed by NukaCast's Android 4.4-safe client. */
public class OkHttp {
    private static final long TIMEOUT = TimeUnit.SECONDS.toMillis(30);
    private static OkDns dns;
    private static OkHttpClient client;

    public OkHttp() {}

    public static synchronized OkDns dns() {
        if (dns == null) dns = new OkDns();
        return dns;
    }

    public static synchronized OkHttpClient client() {
        if (client == null) client = HttpStack.client().newBuilder().dns(dns()).build();
        return client;
    }

    public static OkHttpClient player() { return client(); }

    public static OkHttpClient client(long timeout) {
        return client().newBuilder()
                .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .writeTimeout(timeout, TimeUnit.MILLISECONDS).build();
    }

    public static OkHttpClient noRedirect() { return noRedirect(TIMEOUT); }

    public static OkHttpClient noRedirect(long timeout) {
        return client(timeout).newBuilder().followRedirects(false).followSslRedirects(false).build();
    }

    public static synchronized void reset() {
        client = null;
        dns = null;
    }

    public static synchronized void resetClient() { client = null; }

    public static OkHttpClient client(boolean redirect, long timeout) {
        return redirect ? client(timeout) : noRedirect(timeout);
    }

    public static String string(String url) { return string(url, (Map<String, String>) null); }

    public static String string(String url, long timeout) {
        if (url == null || !url.startsWith("http")) return "";
        return responseString(newCall(client(timeout), url));
    }

    public static String string(String url, Map<String, String> headers) {
        if (url == null || !url.startsWith("http")) return "";
        return responseString(newCall(url, headers));
    }

    public static Call newCall(String url) {
        return client().newCall(new Request.Builder().url(url).build());
    }

    public static Call newCall(String url, String tag) {
        return client().newCall(new Request.Builder().url(url).tag(tag).build());
    }

    public static Call newCall(OkHttpClient value, String url) {
        return value.newCall(new Request.Builder().url(url).build());
    }

    public static Call newCall(OkHttpClient value, String url, String tag) {
        return value.newCall(new Request.Builder().url(url).tag(tag).build());
    }

    public static Call newCall(String url, Map<String, String> headers) {
        return client().newCall(new Request.Builder().url(url).headers(headers(headers)).build());
    }

    public static Call newCall(String url, Map<String, String> headers,
                               ArrayMap<String, String> params) {
        return client().newCall(new Request.Builder().url(buildUrl(url, params))
                .headers(headers(headers)).build());
    }

    public static Call newCall(String url, Map<String, String> headers, RequestBody body) {
        return client().newCall(new Request.Builder().url(url).headers(headers(headers)).post(body).build());
    }

    public static Call newCall(String url, RequestBody body, String tag) {
        return client().newCall(new Request.Builder().url(url).post(body).tag(tag).build());
    }

    public static Call newCall(OkHttpClient value, String url, RequestBody body) {
        return value.newCall(new Request.Builder().url(url).post(body).build());
    }

    public static void cancel(String tag) { cancel(client(), tag); }

    public static void cancel(OkHttpClient value, String tag) {
        if (value == null || tag == null) return;
        for (Call call : value.dispatcher().queuedCalls()) {
            if (tag.equals(call.request().tag())) call.cancel();
        }
        for (Call call : value.dispatcher().runningCalls()) {
            if (tag.equals(call.request().tag())) call.cancel();
        }
    }

    public static void cancelAll() { cancelAll(client()); }

    public static void cancelAll(OkHttpClient value) {
        if (value != null) value.dispatcher().cancelAll();
    }

    public static FormBody toBody(ArrayMap<String, String> params) {
        FormBody.Builder body = new FormBody.Builder();
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                body.add(entry.getKey(), entry.getValue());
            }
        }
        return body.build();
    }

    private static String responseString(Call call) {
        try (Response response = call.execute()) {
            return response.body() == null ? "" : response.body().string();
        } catch (Exception error) {
            return "";
        }
    }

    private static Headers headers(Map<String, String> values) {
        return values == null ? new Headers.Builder().build() : Headers.of(values);
    }

    private static HttpUrl buildUrl(String url, ArrayMap<String, String> params) {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) throw new IllegalArgumentException("Invalid URL: " + url);
        HttpUrl.Builder builder = parsed.newBuilder();
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                builder.addQueryParameter(entry.getKey(), entry.getValue());
            }
        }
        return builder.build();
    }
}
