package com.nukacast.app.live;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a playlist channel name into the names an EPG service recognises.
 *
 * <p>EPG sites are keyed on plain names ({@code CCTV1}, {@code 湖南卫视}) while playlists decorate them
 * ({@code CCTV-13 (1080p)}, {@code CCTV-1 综合}, {@code 湖南卫视高清}). A single strict lookup therefore
 * almost never matches; candidates are produced best-first and tried in order.
 */
public final class EpgChannelId {
    private EpgChannelId() {}

    /** Quality/format markers that are never part of the EPG key. */
    private static final String[] NOISE = {
            "(1080p)", "(720p)", "(540p)", "(4k)", "(hd)", "(sd)",
            "1080p", "720p", "540p", "4k", "uhd", "fhd", "hd", "sd",
            "高清", "超清", "蓝光", "标清", "流畅", "备用", "测试", "线路",
    };

    /**
     * Candidate EPG keys for {@code raw}, most likely first and without duplicates.
     *
     * <p>The first candidate is always the name as written; the rest are progressively looser.
     */
    public static List<String> candidates(String raw) {
        Set<String> result = new LinkedHashSet<String>();
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return new ArrayList<String>(result);
        result.add(value);

        String stripped = value;
        for (String noise : NOISE) {
            // A marker can appear at either end, sometimes twice (e.g. "CCTV1 高清 1080p").
            while (true) {
                String next = strip(stripped, noise);
                if (next.equals(stripped)) break;
                stripped = next;
            }
        }
        stripped = stripped.replace("　", " ").trim();
        if (!stripped.isEmpty()) result.add(stripped);

        // "CCTV-1 综合" → "CCTV-1" → "CCTV1"; the same for 卫视 suffixes.
        String withoutSpace = stripped.replace(" ", "");
        if (!withoutSpace.isEmpty()) result.add(withoutSpace);
        String firstToken = stripped.split("[\\s/|]+")[0].trim();
        if (!firstToken.isEmpty()) result.add(firstToken);
        String noDash = withoutSpace.replace("-", "");
        if (!noDash.isEmpty()) result.add(noDash);
        // Many services key the main channels without the dash ("CCTV1", "CCTV1综合"), so every
        // CCTV-shaped candidate also gets a dashless variant.
        for (String candidate : new ArrayList<String>(result)) {
            if (candidate.toUpperCase(java.util.Locale.US).startsWith("CCTV")) {
                String plain = candidate.replace("-", "").trim();
                if (!plain.isEmpty()) result.add(plain);
            }
        }
        result.remove("");
        return new ArrayList<String>(result);
    }

    private static String strip(String value, String noise) {
        String lower = value.toLowerCase(java.util.Locale.US);
        String target = noise.toLowerCase(java.util.Locale.US);
        int at = lower.indexOf(target);
        if (at < 0) return value;
        return (value.substring(0, at) + value.substring(at + noise.length())).trim();
    }
}
