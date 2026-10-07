package com.nukacast.app.net;

import com.nukacast.app.diagnostics.AppLog;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches a URL from the device and reports what came back.
 *
 * <p>Debugging a stream URL from a development machine answers the wrong question: the device may
 * have a different DNS, a different route to the CDN, or no IPv6, and "it works on my laptop" is
 * exactly how a black screen stays unexplained. This runs the request where playback happens and
 * returns status, timing, headers and the first bytes, which is enough to tell a 403 apart from a
 * redirect loop or an HTML page served where an m3u8 was expected.
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
        long startedAt = System.currentTimeMillis();
        HttpURLConnection connection = null;
        try {
            URL target = new URL(url);
            result.put("host", target.getHost());
            connection = (HttpURLConnection) target.openConnection();
            connection.setRequestMethod(method);
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(12_000);
            connection.setReadTimeout(20_000);
            connection.setRequestProperty("User-Agent", "NukaCast/Probe");
            connection.setRequestProperty("Accept", "*/*");
            int status = connection.getResponseCode();
            result.put("status", status);
            result.put("finalUrl", connection.getURL() == null
                    ? url : connection.getURL().toString());
            result.put("contentType", connection.getContentType());
            result.put("contentLength", connection.getContentLength());
            Map<String, String> headers = new LinkedHashMap<String, String>();
            for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isEmpty()) {
                    continue;
                }
                headers.put(entry.getKey(), entry.getValue().get(0));
            }
            result.put("headers", headers);
            result.put("elapsedMs", System.currentTimeMillis() - startedAt);

            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) {
                result.put("bytes", 0);
                result.put("preview", "");
            } else {
                try {
                    byte[] buffer = new byte[8192];
                    java.io.ByteArrayOutputStream collected = new java.io.ByteArrayOutputStream();
                    int total = 0;
                    int read;
                    while (total < MAX_BYTES && (read = stream.read(buffer)) > 0) {
                        collected.write(buffer, 0, read);
                        total += read;
                    }
                    result.put("bytes", total);
                    result.put("truncated", total >= MAX_BYTES);
                    byte[] body = collected.toByteArray();
                    result.put("looksLikePlaylist", looksLikePlaylist(body));
                    result.put("preview", decoded(body, PREVIEW_CHARS));
                } finally {
                    stream.close();
                }
            }
        } catch (Throwable error) {
            result.put("error", error.getClass().getSimpleName() + ": " + error.getMessage());
            result.put("errorCode", com.nukacast.app.diagnostics.ErrorCodes.of(error));
            result.put("elapsedMs", System.currentTimeMillis() - startedAt);
            AppLog.d("网络探测", "探测失败 " + url + "：" + error.getClass().getSimpleName());
        } finally {
            if (connection != null) connection.disconnect();
        }
        return result;
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
