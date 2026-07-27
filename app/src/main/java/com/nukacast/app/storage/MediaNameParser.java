package com.nukacast.app.storage;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MediaNameParser {
    private static final Pattern SEASON_EPISODE = Pattern.compile(
            "(?i)(?:^|[ ._\\-])S(\\d{1,2})[ ._\\-]*E(\\d{1,3})(?:$|[ ._\\-])");
    private static final Pattern CHINESE_EPISODE = Pattern.compile("第\\s*(\\d{1,3})\\s*集");
    private static final Pattern YEAR = Pattern.compile("(?:^|[ ._\\-\\(])(19\\d{2}|20\\d{2})(?:$|[ ._\\-\\)])");
    private static final Pattern BRACKETS = Pattern.compile("[\\[【].*?[\\]】]");
    private static final Pattern RELEASE_TAGS = Pattern.compile(
            "(?i)(?:2160p|1080p|720p|4k|uhd|bluray|blu-ray|web[- .]?dl|webrip|hdtv|x26[45]|h\\.26[45]|hevc|avc|aac|dts|hdr10?|dv|dolby|remux)");

    private MediaNameParser() {}

    public static ParsedName parse(String fileName) {
        String stem = fileName == null ? "" : fileName.trim();
        int slash = Math.max(stem.lastIndexOf('/'), stem.lastIndexOf('\\'));
        if (slash >= 0) stem = stem.substring(slash + 1);
        int dot = stem.lastIndexOf('.');
        if (dot > 0) stem = stem.substring(0, dot);

        ParsedName result = new ParsedName();
        Matcher episode = SEASON_EPISODE.matcher(" " + stem + " ");
        if (episode.find()) {
            result.season = integer(episode.group(1));
            result.episode = integer(episode.group(2));
            stem = stem.substring(0, Math.max(0, episode.start() - 1));
        } else {
            Matcher chinese = CHINESE_EPISODE.matcher(stem);
            if (chinese.find()) {
                result.episode = integer(chinese.group(1));
                stem = stem.substring(0, chinese.start());
            }
        }

        Matcher year = YEAR.matcher(" " + stem + " ");
        if (year.find()) result.year = year.group(1);
        stem = BRACKETS.matcher(stem).replaceAll(" ");
        stem = RELEASE_TAGS.matcher(stem).replaceAll(" ");
        stem = YEAR.matcher(" " + stem + " ").replaceAll(" ");
        stem = stem.replaceAll("[._]+", " ").replaceAll("\\s*-\\s*", " ")
                .replaceAll("\\s+", " ").trim();

        result.title = stem.isEmpty() ? "未命名影片" : stem;
        result.typeName = result.episode > 0 ? "电视剧" : "电影";
        return result;
    }

    public static boolean isVideo(String name) {
        String value = name == null ? "" : name.toLowerCase(Locale.US);
        return value.endsWith(".mp4") || value.endsWith(".mkv") || value.endsWith(".avi")
                || value.endsWith(".mov") || value.endsWith(".m4v") || value.endsWith(".ts")
                || value.endsWith(".m2ts") || value.endsWith(".webm") || value.endsWith(".flv");
    }

    private static int integer(String value) {
        try { return Integer.parseInt(value); } catch (Exception ignored) { return 0; }
    }

    public static final class ParsedName {
        public String title = "";
        public String year = "";
        public String typeName = "电影";
        public int season;
        public int episode;
    }
}
