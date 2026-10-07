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

    /**
     * Decodes a text response, honouring the charset the server declares and falling back to GBK.
     *
     * <p>Chinese IPTV playlists and CMS responses are frequently GBK while claiming nothing at all in
     * the headers; decoding them as UTF-8 turned every channel name into mojibake ("ֱ���й�" on the
     * TV). UTF-8 is tried strictly first, and only a decode failure falls back, so the common case is
     * unchanged.
     */
    public static String text(ResponseBody body, int maximumBytes) throws IOException {
        byte[] raw = bytes(body, maximumBytes);
        String declared = body.contentType() == null || body.contentType().charset() == null
                ? "" : body.contentType().charset().name();
        if (!declared.isEmpty()) {
            try {
                return new String(raw, Charset.forName(declared));
            } catch (Exception ignored) {
                // Unsupported name: fall through to detection.
            }
        }
        try {
            return UTF8_NEW_DECODER.get().decode(java.nio.ByteBuffer.wrap(raw)).toString();
        } catch (Exception invalidUtf8) {
            for (String name : FALLBACK_CHARSETS) {
                try {
                    return new String(raw, Charset.forName(name));
                } catch (Exception ignored) {
                    // Try the next candidate.
                }
            }
            return new String(raw, Charset.forName("UTF-8"));
        }
    }

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    /** GBK first: it is what Chinese playlists actually use when they are not UTF-8. */
    private static final String[] FALLBACK_CHARSETS = {"GBK", "GB18030", "ISO-8859-1"};
    private static final ThreadLocal<java.nio.charset.CharsetDecoder> UTF8_NEW_DECODER =
            new ThreadLocal<java.nio.charset.CharsetDecoder>() {
                @Override protected java.nio.charset.CharsetDecoder initialValue() {
                    return UTF_8.newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
                }
            };
}
