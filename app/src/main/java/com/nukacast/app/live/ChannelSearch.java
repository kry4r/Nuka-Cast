package com.nukacast.app.live;

import com.nukacast.app.live.model.LiveCatalog;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds channels in a playlist by name.
 *
 * <p>Public IPTV playlists carry thousands of channels (the built-in iptv-org list has 11k), so
 * scrolling is not a way to find one. Chinese channel names are matched by substring and by pinyin
 * initials ("湖南卫视" also answers to "hunan" or "hnws"), while Latin names are matched
 * case-insensitively.
 */
public final class ChannelSearch {
    /** Upper bound on results: the live grid pages at 120 entries, this keeps a search to a few pages. */
    private static final int MAX_RESULTS = 600;

    private ChannelSearch() {}

    /** Channels of {@code catalog} whose name matches {@code query}, in playlist order. */
    public static List<LiveCatalog.Channel> find(LiveCatalog catalog, String query) {
        List<LiveCatalog.Channel> result = new ArrayList<LiveCatalog.Channel>();
        if (catalog == null || query == null) return result;
        String needle = normalize(query);
        if (needle.isEmpty()) return result;
        for (LiveCatalog.Group group : catalog.groups) {
            for (LiveCatalog.Channel channel : group.channels) {
                if (result.size() >= MAX_RESULTS) return result;
                if (matches(channel.name, needle)) {
                    if (channel.group == null || channel.group.isEmpty()) channel.group = group.name;
                    result.add(channel);
                }
            }
        }
        return result;
    }

    /** True when a channel name answers to the query, by substring or by pinyin initials. */
    static boolean matches(String name, String needle) {
        if (name == null) return false;
        String lower = normalize(name);
        if (lower.contains(needle)) return true;
        // Initials are only meaningful for Chinese names; "cctv1" typed as "cctv1" already matched.
        if (needle.length() <= 6) {
            String initials = normalize(com.nukacast.app.tvbox.PinyinInitials.of(name));
            if (!initials.isEmpty() && initials.contains(needle)) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT)
                .replace(" ", "").replace("-", "").replace("_", "");
    }
}
