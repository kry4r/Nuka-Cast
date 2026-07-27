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
        for (String rawLine : body.replace("\r", "").split("\n")) {
            String line = rawLine.trim();
            if (line.startsWith("#EXTINF:")) {
                pendingInfo = line;
                pendingUserAgent = null;
            } else if (line.startsWith("#EXTVLCOPT:http-user-agent=")) {
                pendingUserAgent = line.substring(line.indexOf('=') + 1).trim();
            } else if (!line.isEmpty() && !line.startsWith("#") && pendingInfo != null) {
                Map<String, String> attributes = attributes(pendingInfo);
                String name = pendingInfo.indexOf(',') >= 0
                        ? pendingInfo.substring(pendingInfo.indexOf(',') + 1).trim() : "频道";
                String groupName = value(attributes, "group-title", "未分组");
                LiveCatalog.Channel channel = new LiveCatalog.Channel();
                channel.name = name;
                channel.epgId = value(attributes, "tvg-id", name);
                channel.logo = value(attributes, "tvg-logo", "");
                channel.group = groupName;
                addUrls(channel, line);
                if (pendingUserAgent != null) channel.headers.put("User-Agent", pendingUserAgent);
                group(groups, groupName).channels.add(channel);
                pendingInfo = null;
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
            LiveCatalog.Channel channel = new LiveCatalog.Channel();
            channel.name = name;
            channel.epgId = name;
            channel.group = currentGroup;
            addUrls(channel, value);
            if (!channel.urls.isEmpty()) group(groups, currentGroup).channels.add(channel);
        }
        catalog.groups.addAll(groups.values());
        return catalog;
    }

    private static void addUrls(LiveCatalog.Channel channel, String encoded) {
        for (String url : encoded.split("#")) {
            String value = url.trim();
            if (!value.isEmpty()) channel.urls.add(value);
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
