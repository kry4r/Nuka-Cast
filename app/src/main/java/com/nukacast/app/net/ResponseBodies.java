package com.nukacast.app.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

import okhttp3.ResponseBody;

public final class ResponseBodies {
    private ResponseBodies() {}

    public static byte[] bytes(ResponseBody body, int maximumBytes) throws IOException {
        if (body == null) throw new IOException("响应体为空");
        if (maximumBytes <= 0) throw new IllegalArgumentException("maximumBytes");
        long declared = body.contentLength();
        if (declared > maximumBytes) {
            throw new IOException("响应过大: " + declared + " > " + maximumBytes);
        }
        InputStream input = body.byteStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                declared > 0 ? (int) Math.min(declared, maximumBytes) : 8192);
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > maximumBytes) throw new IOException("响应超过限制: " + maximumBytes);
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    public static String string(ResponseBody body, int maximumBytes, Charset charset)
            throws IOException {
        return new String(bytes(body, maximumBytes), charset);
    }
}
