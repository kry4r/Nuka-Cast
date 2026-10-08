package com.nukacast.app.net;

import com.nukacast.app.diagnostics.AppLog;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetches a URL from the device and reports what came back.
 *
 * <p>Debugging a stream URL from a development machine answers the wrong question: the device may
 * have a different DNS, a different route to the CDN, or no IPv6, and "it works on my laptop" is
 * exactly how a black screen stays unexplained. This runs the request where playback happens and
 * returns status, timing, headers and the first bytes, which is enough to tell a 403 apart from a
 * redirect loop or an HTML page served where an m3u8 was expected.
 *
 * <p>It goes through {@link HttpStack}, the same client the app uses. Measured on Android 4.4: the
 * platform's {@code HttpURLConnection} cannot negotiate TLS 1.2 at all
 * ({@code SSL23_GET_SERVER_HELLO:unsupported protocol}) while this client — which carries Conscrypt —
 * reaches those sites fine, so a probe built on {@code HttpURLConnection} reported "TLS 失败" for
 * sites the app uses every day.
 */
public final class ProbeTool {
    /** Enough to identify a playlist or a JSON body without pulling a whole video. */
    private static final int MAX_BYTES = 64 * 1024;
    private static final int PREVIEW_CHARS = 600;

    private ProbeTool() {}

    public static Map<String, Object> run(String url, String method) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("url", url);
        result.put("method", method);
        result.put("stack", "okhttp+" + (ConscryptTls.isInstalled() ? "conscrypt" : "platform-tls"));
        long startedAt = System.currentTimeMillis();
        String verb = method == null || method.trim().isEmpty() ? "GET" : method.trim().toUpperCase(java.util.Locale.US);
        Request.Builder request = new Request.Builder().url(url)
                .header("User-Agent", "NukaCast/Probe")
                .header("Accept", "*/*");
        // OkHttp insists on a body for the methods that carry one; an empty body keeps the probe the
        // same shape as a real request from here.
        if ("GET".equals(verb) || "HEAD".equals(verb)) {
            request.method(verb, null);
        } else {
            request.method(verb, okhttp3.RequestBody.create(null, new byte[0]));
        }
        try {
            result.put("host", request.build().url().host());
        } catch (Throwable ignored) {
            // A malformed URL is reported below, by the call itself.
        }
        try (Response response = HttpStack.client().newCall(request.build()).execute()) {
            result.put("status", response.code());
            result.put("finalUrl", response.request().url().toString());
            result.put("contentType", response.header("Content-Type"));
            result.put("contentLength", contentLength(response));
            Map<String, String> headers = new LinkedHashMap<String, String>();
            for (String name : response.headers().names()) {
                headers.put(name, response.header(name));
            }
            result.put("headers", headers);
            result.put("elapsedMs", System.currentTimeMillis() - startedAt);
            result.put("protocol", response.protocol().toString());
            ResponseBody body = response.body();
            if (body == null) {
                result.put("bytes", 0);
                result.put("preview", "");
                return result;
            }
            byte[] collected = read(body.byteStream(), MAX_BYTES);
            result.put("bytes", collected.length);
            result.put("truncated", collected.length >= MAX_BYTES);
            result.put("looksLikePlaylist", looksLikePlaylist(collected));
            result.put("preview", decoded(collected, PREVIEW_CHARS));
        } catch (Throwable error) {
            result.put("error", error.getClass().getSimpleName() + ": " + error.getMessage());
            result.put("errorCode", com.nukacast.app.diagnostics.ErrorCodes.of(error));
            result.put("elapsedMs", System.currentTimeMillis() - startedAt);
            AppLog.d("网络探测", "探测失败 " + url + "：" + error.getClass().getSimpleName());
        }
        return result;
    }

    private static long contentLength(Response response) {
        ResponseBody body = response.body();
        if (body == null) return -1;
        long length = body.contentLength();
        return length < 0 ? -1 : length;
    }

    private static byte[] read(InputStream stream, int maximum) throws Exception {
        if (stream == null) return new byte[0];
        try {
            byte[] buffer = new byte[8192];
            java.io.ByteArrayOutputStream collected = new java.io.ByteArrayOutputStream();
            int total = 0;
            int read;
            while (total < maximum && (read = stream.read(buffer)) > 0) {
                collected.write(buffer, 0, read);
                total += read;
            }
            return collected.toByteArray();
        } finally {
            stream.close();
        }
    }

    /** True when the body starts with an HLS or DASH manifest marker. */
    static boolean looksLikePlaylist(byte[] body) {
        if (body == null || body.length == 0) return false;
        String text = decoded(body, 400).trim();
        return text.startsWith("#EXTM3U") || text.contains("#EXTINF") || text.contains("<MPD");
    }

    private static String decoded(byte[] body, int maximum) {
        if (body == null || body.length == 0) return "";
        try {
            return new String(body, 0, Math.min(body.length, maximum), "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            return "";
        }
    }
}
