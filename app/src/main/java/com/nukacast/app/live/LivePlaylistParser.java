package com.nukacast.app.live;

import com.nukacast.app.live.model.LiveCatalog;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LivePlaylistParser {
    private static final Pattern ATTRIBUTE = Pattern.compile("([A-Za-z0-9_-]+)=\\\"([^\\\"]*)\\\"");

    private LivePlaylistParser() {}

    public static LiveCatalog parse(String body) {
        if (body == null) throw new IllegalArgumentException("直播清单为空");
        return body.trim().toUpperCase(Locale.US).startsWith("#EXTM3U")
                ? parseM3u(body) : parseText(body);
    }

    private static LiveCatalog parseM3u(String body) {
        LiveCatalog catalog = new LiveCatalog();
        Map<String, LiveCatalog.Group> groups = new LinkedHashMap<String, LiveCatalog.Group>();
        String pendingInfo = null;
        String pendingUserAgent = null;
        // One #EXTINF can be followed by several address lines, one per mirror — that is how an m3u lists a
        // channel's alternatives, and stopping after the first line threw the rest away (measured: a channel
        // with three addresses reached the device with one, so only the dead first was ever tried).
        LiveCatalog.Channel pendingChannel = null;
        for (String rawLine : body.replace("\r", "").split("\n")) {
            String line = rawLine.trim();
            if (line.startsWith("#EXTINF:")) {
                pendingInfo = line;
                pendingUserAgent = null;
                pendingChannel = null;
            } else if (line.startsWith("#EXTVLCOPT:http-user-agent=")) {
                pendingUserAgent = line.substring(line.indexOf('=') + 1).trim();
                if (pendingChannel != null) pendingChannel.headers.put("User-Agent", pendingUserAgent);
            } else if (!line.isEmpty() && !line.startsWith("#") && pendingInfo != null) {
                if (pendingChannel == null) {
                    Map<String, String> attributes = attributes(pendingInfo);
                    String name = pendingInfo.indexOf(',') >= 0
                            ? pendingInfo.substring(pendingInfo.indexOf(',') + 1).trim() : "频道";
                    String groupName = value(attributes, "group-title", "未分组");
                    pendingChannel = mergeable(groups, groupName, name);
                    if (pendingChannel == null) {
                        pendingChannel = new LiveCatalog.Channel();
                        pendingChannel.name = name;
                        pendingChannel.group = groupName;
                        group(groups, groupName).channels.add(pendingChannel);
                    }
                    if (pendingChannel.epgId.isEmpty()) {
                        pendingChannel.epgId = value(attributes, "tvg-id", name);
                    }
                    pendingChannel.logo = value(attributes, "tvg-logo", pendingChannel.logo);
                    pendingChannel.catchup = value(attributes, "catchup", pendingChannel.catchup);
                    pendingChannel.catchupSource =
                            value(attributes, "catchup-source", pendingChannel.catchupSource);
                    pendingChannel.catchupDays =
                            parseDays(value(attributes, "catchup-days", ""), pendingChannel.catchupDays);
                }
                addUrls(pendingChannel, line);
                if (pendingUserAgent != null) {
                    pendingChannel.headers.put("User-Agent", pendingUserAgent);
                }
            }
        }
        catalog.groups.addAll(groups.values());
        return catalog;
    }

    private static LiveCatalog parseText(String body) {
        LiveCatalog catalog = new LiveCatalog();
        Map<String, LiveCatalog.Group> groups = new LinkedHashMap<String, LiveCatalog.Group>();
        String currentGroup = "未分组";
        for (String rawLine : body.replace("\r", "").split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int separator = line.indexOf(',');
            if (separator < 0) continue;
            String name = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if ("#genre#".equalsIgnoreCase(value)) {
                currentGroup = name.isEmpty() ? "未分组" : name;
                group(groups, currentGroup);
                continue;
            }
            LiveCatalog.Channel channel = mergeable(groups, currentGroup, name);
            if (channel == null) {
                channel = new LiveCatalog.Channel();
                channel.name = name;
                channel.epgId = name;
                channel.group = currentGroup;
                group(groups, currentGroup).channels.add(channel);
            }
            addUrls(channel, value);
        }
        catalog.groups.addAll(groups.values());
        return catalog;
    }

    private static void addUrls(LiveCatalog.Channel channel, String encoded) {
        for (String url : encoded.split("#")) {
            String value = url.trim();
            // The same address shows up twice in plenty of playlists; a duplicate is not a second source
            // and retrying it just wastes a failed attempt.
            if (!value.isEmpty() && !channel.urls.contains(value)) channel.urls.add(value);
        }
    }

    /**
     * The channel with this name in this group, when it already exists.
     *
     * <p>Free playlists list one channel once per mirror ({@code CCTV1,url} three times). Merging them is
     * what gives the player somewhere to fall back to, and keeps the grid showing one entry per channel.
     */
    private static LiveCatalog.Channel mergeable(Map<String, LiveCatalog.Group> groups, String groupName,
                                                String name) {
        LiveCatalog.Group group = groups.get(groupName);
        if (group == null) return null;
        for (LiveCatalog.Channel candidate : group.channels) {
            if (candidate.name.equals(name)) return candidate;
        }
        return null;
    }

    private static int parseDays(String value, int fallback) {
        try {
            int days = Integer.parseInt(value.trim());
            return days > 0 ? Math.min(days, 31) : fallback;
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    private static Map<String, String> attributes(String info) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        Matcher matcher = ATTRIBUTE.matcher(info);
        while (matcher.find()) result.put(matcher.group(1).toLowerCase(Locale.US), matcher.group(2));
        return result;
    }

    private static String value(Map<String, String> values, String key, String fallback) {
        String value = values.get(key);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static LiveCatalog.Group group(Map<String, LiveCatalog.Group> groups, String name) {
        LiveCatalog.Group group = groups.get(name);
        if (group == null) {
            group = new LiveCatalog.Group();
            group.name = name;
            groups.put(name, group);
        }
        return group;
    }
}
