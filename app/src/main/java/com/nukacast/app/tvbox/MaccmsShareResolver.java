package com.nukacast.app.tvbox;

import com.nukacast.app.diagnostics.AppLog;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a MacCMS "share" player page into the media URL it wraps.
 *
 * <p>Several large Chinese CMS back ends (非凡资源, 电影天堂 and their clones) do not publish episode
 * URLs that point at media. They publish URLs like {@code https://host/share/<hash>} which return a
 * small HTML page whose script block carries the real address:
 *
 * <pre>{@code const url = "/20260918/49391_69162221/index.m3u8?sign=..."}</pre>
 *
 * <p>Handing that HTML page to a player produces a black screen with no error, which is what makes
 * such sources look broken. This resolver fetches the page and returns the absolute media URL so
 * ordinary playback works.
 */
public final class MaccmsShareResolver {
    private static final String TAG = "播放解析";
    /** Share pages are a few kilobytes of HTML; anything larger is not one. */
    private static final int MAX_PAGE_BYTES = 256 * 1024;
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;
    private static final int MAX_CACHE = 64;
    private static final Pattern[] MEDIA_PATTERNS = {
            // const url = "/20260918/49391_69162221/index.m3u8?sign=..." (current share theme)
            Pattern.compile("(?:const|let|var|window\\.)?\\s*url\\s*[:=]\\s*[\"']([^\"']+)[\"']"),
            // player_aaaa = {"url":"https:\\/\\/...\\/index.m3u8","url_next":...}
            Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\""),
            // Any absolute media link in the page.
            Pattern.compile("(https?:\\\\?/\\\\?/[^\"'\\\\\\s]+\\.(?:m3u8|mp4|flv)[^\"'\\\\\\s]*)"),
    };
    private static final Pattern RELATIVE_MEDIA = Pattern.compile(
            "[\"'](/[^\"']*\\.(?:m3u8|mp4|flv)(?:\\?[^\"']*)?)[\"']");

    private static final Map<String, Cached> CACHE = new LinkedHashMap<String, Cached>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > MAX_CACHE;
        }
    };

    private MaccmsShareResolver() {}

    private static final class Cached {
        String url;
        String error;
        long at;
    }

    /**
     * True when the address looks like a CMS player page rather than media. Only such URLs are
     * fetched, so a normal {@code .m3u8} never costs an extra request.
     */
    public static boolean looksLikeSharePage(String url) {
        if (url == null) return false;
        String value = url.trim().toLowerCase(java.util.Locale.ROOT);
        if (!value.startsWith("http://") && !value.startsWith("https://")) return false;
        int query = value.indexOf('?');
        String path = query >= 0 ? value.substring(0, query) : value;
        if (path.endsWith(".m3u8") || path.endsWith(".mp4") || path.endsWith(".flv")) return false;
        if (path.endsWith(".m3u") || path.endsWith(".ts")) return false;
        return path.contains("/share/") || path.contains("/play/") || path.endsWith(".html")
                || path.endsWith(".htm") || path.endsWith("/index.php");
    }

    /**
     * Returns a playable media URL for {@code url}: the URL itself when it is already media, the
     * resolved address when it is a player page, or null when the page carried no address.
     */
    public static String resolve(String url, String referer) {
        if (url == null || url.trim().isEmpty()) return null;
        if (!looksLikeSharePage(url)) return url;
        find: {
            synchronized (CACHE) {
                Cached cached = CACHE.get(url);
                if (cached != null && System.currentTimeMillis() - cached.at < CACHE_TTL_MS) {
                    if (cached.url != null) return cached.url;
                    break find;
                }
            }
            String resolved = fetch(url, referer);
            synchronized (CACHE) {
                Cached entry = new Cached();
                entry.url = resolved;
                entry.error = resolved == null ? "未找到播放地址" : null;
                entry.at = System.currentTimeMillis();
                CACHE.put(url, entry);
            }
            if (resolved != null) {
                AppLog.d(TAG, "已从播放页解析出地址：" + shortHost(resolved));
                return resolved;
            }
            return null;
        }
        return null;
    }

    private static String fetch(String url, String referer) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 9; TV) AppleWebKit/537.36 Chrome/120 Safari/537.36");
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*");
            if (referer != null && !referer.isEmpty()) {
                connection.setRequestProperty("Referer", referer);
            }
            if (connection.getResponseCode() >= 400) {
                AppLog.d(TAG, "播放页返回 HTTP " + connection.getResponseCode());
                return null;
            }
            InputStream stream = connection.getInputStream();
            StringBuilder page = new StringBuilder();
            try {
                byte[] buffer = new byte[8192];
                int read;
                while (page.length() < MAX_PAGE_BYTES && (read = stream.read(buffer)) > 0) {
                    page.append(new String(buffer, 0, read, "UTF-8"));
                }
            } finally {
                stream.close();
            }
            return extract(page.toString(), url);
        } catch (Throwable error) {
            AppLog.d(TAG, "播放页解析失败：" + error.getClass().getSimpleName());
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Pulls the media address out of a share page and makes it absolute. */
    static String extract(String html, String pageUrl) {
        if (html == null || html.isEmpty()) return null;
        String body = html.replace("\\/", "/");
        for (Pattern pattern : MEDIA_PATTERNS) {
            Matcher matcher = pattern.matcher(body);
            while (matcher.find()) {
                String candidate = matcher.group(1);
                if (candidate == null) continue;
                String absolute = absolute(candidate, pageUrl);
                if (absolute != null && isMedia(absolute)) return absolute;
            }
        }
        Matcher relative = RELATIVE_MEDIA.matcher(body);
        while (relative.find()) {
            String absolute = absolute(relative.group(1), pageUrl);
            if (absolute != null && isMedia(absolute)) return absolute;
        }
        return null;
    }

    static String absolute(String candidate, String pageUrl) {
        if (candidate == null) return null;
        String value = candidate.trim();
        if (value.isEmpty()) return null;
        if (value.startsWith("//")) {
            String scheme = pageUrl.startsWith("https") ? "https:" : "http:";
            return scheme + value;
        }
        if (value.startsWith("http://") || value.startsWith("https://")) return value;
        if (!value.startsWith("/")) return null;
        try {
            URL base = new URL(pageUrl);
            return base.getProtocol() + "://" + base.getAuthority() + value;
        } catch (Exception error) {
            return null;
        }
    }

    private static boolean isMedia(String url) {
        String value = url.toLowerCase(java.util.Locale.ROOT);
        int query = value.indexOf('?');
        String path = query >= 0 ? value.substring(0, query) : value;
        return path.endsWith(".m3u8") || path.endsWith(".mp4") || path.endsWith(".flv")
                || path.endsWith(".m3u") || path.endsWith(".ts");
    }

    private static String shortHost(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception error) {
            return url;
        }
    }
}
