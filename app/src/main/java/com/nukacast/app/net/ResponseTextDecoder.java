package com.nukacast.app.net;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;

import okhttp3.MediaType;

public final class ResponseTextDecoder {
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final Charset UTF_16_BE = Charset.forName("UTF-16BE");
    private static final Charset UTF_16_LE = Charset.forName("UTF-16LE");
    private static final Charset GB_18030 = Charset.forName("GB18030");

    private ResponseTextDecoder() {}

    public static String decode(byte[] bytes, String contentType) {
        if (bytes == null) throw new IllegalArgumentException("bytes");
        if (startsWith(bytes, 0xef, 0xbb, 0xbf)) return text(bytes, 3, UTF_8);
        if (startsWith(bytes, 0xfe, 0xff)) return text(bytes, 2, UTF_16_BE);
        if (startsWith(bytes, 0xff, 0xfe)) return text(bytes, 2, UTF_16_LE);

        Charset declared = declaredCharset(contentType);
        if (declared != null) return text(bytes, 0, declared);
        try {
            return UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalidUtf8) {
            return text(bytes, 0, GB_18030);
        }
    }

    private static Charset declaredCharset(String contentType) {
        if (contentType == null || contentType.trim().isEmpty()) return null;
        try {
            MediaType mediaType = MediaType.parse(contentType);
            return mediaType == null ? null : mediaType.charset(null);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static String text(byte[] bytes, int offset, Charset charset) {
        return new String(bytes, offset, bytes.length - offset, charset);
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xff) != prefix[i]) return false;
        }
        return true;
    }
}
