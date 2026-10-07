package com.nukacast.app.net;

import java.net.IDN;
import java.util.Locale;

/**
 * Normalises user-supplied URLs before they reach OkHttp.
 *
 * <p>Two real failures from an Android TV: a config URL pasted together with its title, and a URL
 * whose host contained non-ASCII characters. OkHttp rejects the latter with
 * {@code IllegalArgumentException: Invalid URL host} — accurate but useless to the user, and it
 * surfaces as a source error with no hint about what to fix. Here the host is converted to punycode
 * when that is possible ({@code 中文域名.中国} works) and otherwise reported with an explanation.
 */
public final class UrlNormalizer {
    private UrlNormalizer() {}

    public static final class InvalidUrlException extends IllegalArgumentException {
        public final String code;

        InvalidUrlException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** Returns a canonical http(s) URL or throws with a message the UI can show as-is. */
    public static String normalize(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) throw new InvalidUrlException("empty", "地址为空");
        value = firstUrlToken(value);
        // OkHttp canonicalises non-ASCII hosts to punycode itself, so the parsed form is returned
        // rather than the raw input: the rest of the app then deals with one representation.
        okhttp3.HttpUrl parsed = okhttp3.HttpUrl.parse(value);
        if (parsed != null) return parsed.toString();

        String ascii = asciiUrl(value);
        if (ascii != null) {
            okhttp3.HttpUrl asciiParsed = okhttp3.HttpUrl.parse(ascii);
            if (asciiParsed != null) return asciiParsed.toString();
        }
        throw new InvalidUrlException("host",
                "地址域名包含无法识别的字符，请检查是否复制了乱码：" + hostOf(value));
    }

    /**
     * Picks the URL out of a pasted line such as {@code 我的仓库 https://example.com/a.json}, and
     * adds the scheme when the user typed a bare host.
     */
    static String firstUrlToken(String value) {
        String candidate = value;
        String[] tokens = value.split("\\s+");
        if (tokens.length > 1) {
            String found = null;
            for (String token : tokens) {
                String lower = token.toLowerCase(Locale.ROOT);
                if (lower.startsWith("http://") || lower.startsWith("https://")) {
                    found = token;
                    break;
                }
            }
            if (found != null) candidate = found;
        }
        String lower = candidate.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            candidate = "https://" + candidate;
        }
        return candidate;
    }

    /**
     * Converts a non-ASCII host to punycode by hand: {@link java.net.URI} does not expose a host for
     * non-ASCII authorities, so the URL is split into scheme, authority and the rest here.
     * Returns {@code null} when the host cannot be represented at all.
     */
    static String asciiUrl(String url) {
        int schemeEnd = url.indexOf("://");
        if (schemeEnd <= 0) return null;
        String scheme = url.substring(0, schemeEnd);
        String rest = url.substring(schemeEnd + 3);
        int cut = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char character = rest.charAt(i);
            if (character == '/' || character == '?' || character == '#') {
                cut = i;
                break;
            }
        }
        String authority = rest.substring(0, cut);
        String remainder = rest.substring(cut);

        String userInfo = "";
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            userInfo = authority.substring(0, at + 1);
            authority = authority.substring(at + 1);
        }
        String port = "";
        int colon = authority.lastIndexOf(':');
        if (colon > 0 && authority.substring(colon + 1).matches("\\d+")) {
            port = authority.substring(colon);
            authority = authority.substring(0, colon);
        }
        if (authority.isEmpty()) return null;
        boolean ascii = true;
        for (int i = 0; i < authority.length(); i++) {
            if (authority.charAt(i) > 127) {
                ascii = false;
                break;
            }
        }
        if (ascii) return null;
        String punycode;
        try {
            punycode = IDN.toASCII(authority, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException error) {
            // IDN rejects hosts that are already mojibake; the caller reports it to the user.
            return null;
        }
        if (punycode.isEmpty() || punycode.indexOf('.') < 0) return null;
        return scheme + "://" + userInfo + punycode + port + remainder;
    }

    static String hostOf(String url) {
        String value = url == null ? "" : url;
        int scheme = value.indexOf("://");
        if (scheme >= 0) value = value.substring(scheme + 3);
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        return value;
    }
}
