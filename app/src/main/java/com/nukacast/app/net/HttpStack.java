package com.nukacast.app.net;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

public final class HttpStack {
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build();

    private HttpStack() {}

    public static OkHttpClient client() {
        return CLIENT;
    }
}
