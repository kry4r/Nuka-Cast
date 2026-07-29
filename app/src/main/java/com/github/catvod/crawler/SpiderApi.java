package com.github.catvod.crawler;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.util.Base64;

import com.github.catvod.SpiderContext;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.nukacast.app.core.NetworkAddress;
import com.nukacast.app.core.NukaRuntime;
import com.nukacast.app.net.HttpStack;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Host callbacks expected by current TVBoxOS-compatible Spider jars. */
public class SpiderApi {
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private final Context context;

    public SpiderApi() {
        this(SpiderContext.get());
    }

    public SpiderApi(Context context) {
        this.context = context == null ? null : context.getApplicationContext();
    }

    public String getAddress(boolean local) {
        String host = "127.0.0.1";
        if (!local && context != null) {
            host = NetworkAddress.findLanAddress(context);
            if ("0.0.0.0".equals(host)) host = "127.0.0.1";
        }
        return "http://" + host + ":" + NukaRuntime.CONTROL_PORT + "/";
    }

    public String getPort() {
        return String.valueOf(NukaRuntime.CONTROL_PORT);
    }

    public void log(String message) {
        SpiderDebug.log(message);
    }

    public int getScreenOrientation() {
        try {
            if (context == null) return ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
            int orientation = context.getResources().getConfiguration().orientation;
            if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            }
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            }
            return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        } catch (Throwable ignored) {
            return ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        }
    }

    public String multiReq(JsonArray requests) {
        if (requests == null || requests.size() == 0) return "";
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(requests.size(), 6));
        try {
            ArrayList<Future<String>> futures = new ArrayList<Future<String>>();
            for (JsonElement element : requests) {
                if (element == null || !element.isJsonObject()) continue;
                final JsonObject request = element.getAsJsonObject();
                futures.add(executor.submit(new java.util.concurrent.Callable<String>() {
                    @Override public String call() { return execute(request); }
                }));
            }
            JsonArray result = new JsonArray();
            for (Future<String> future : futures) result.add(toResult(future.get()));
            return result.toString();
        } catch (Throwable error) {
            SpiderDebug.log(error);
            return "";
        } finally {
            executor.shutdownNow();
        }
    }

    public String webParse(String url, String flag) {
        try {
            if (url == null || url.isEmpty()) return "";
            String encoded = Base64.encodeToString(url.getBytes(UTF_8),
                    Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP);
            return "proxy://go=SuperParse&flag=" + (flag == null ? "" : flag)
                    + "&url=" + encoded;
        } catch (Throwable error) {
            SpiderDebug.log(error);
            return "";
        }
    }

    private static String execute(JsonObject object) {
        try {
            String url = string(object, "url");
            if (url.isEmpty()) return "";
            Request.Builder builder = new Request.Builder().url(url).headers(headers(object.get("headers")));
            if ("POST".equalsIgnoreCase(string(object, "method"))) builder.post(body(object));
            try (Response response = HttpStack.client().newCall(builder.build()).execute()) {
                return response.body() == null ? "" : response.body().string();
            }
        } catch (Throwable error) {
            SpiderDebug.log(error);
            return "";
        }
    }

    private static JsonElement toResult(String text) {
        if (text == null) return new JsonPrimitive("");
        try {
            String value = text.trim();
            if (value.startsWith("{") || value.startsWith("[")) {
                return new JsonParser().parse(value);
            }
        } catch (Throwable ignored) {}
        return new JsonPrimitive(text);
    }

    private static RequestBody body(JsonObject object) {
        JsonElement data = object.get("data");
        if (data == null || data.isJsonNull()) return RequestBody.create(null, "");
        if ("form".equalsIgnoreCase(string(object, "postType")) && data.isJsonObject()) {
            FormBody.Builder builder = new FormBody.Builder();
            for (Map.Entry<String, JsonElement> entry : data.getAsJsonObject().entrySet()) {
                builder.add(entry.getKey(), entry.getValue().getAsString());
            }
            return builder.build();
        }
        String value = data.isJsonPrimitive() ? data.getAsString() : data.toString();
        return RequestBody.create(null, value);
    }

    private static Headers headers(JsonElement value) {
        Headers.Builder headers = new Headers.Builder();
        if (value == null || !value.isJsonObject()) return headers.build();
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            if (entry.getValue() != null && entry.getValue().isJsonPrimitive()) {
                headers.add(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return headers.build();
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }
}
