package com.nukacast.app.player;

import com.nukacast.app.tvbox.model.MediaDetail;

/**
 * Picks the next play line when the current one cannot be played.
 *
 * <p>A CMS entry usually offers several lines ("gsm3u8", "gsyun", …) and the first is regularly dead
 * or region-blocked, which on the TV looked like "点进去放不了". The decision is separated from the
 * activity so the rules are testable: never the same line twice, always the same episode, and a
 * bounded number of attempts.
 */
public final class LinePicker {
    private LinePicker() {}

    /**
     * The line after {@code currentName} that actually has episodes, or null when there is none.
     *
     * <p>Wraps around once, so a failure on the last line retries the earlier ones instead of stopping.
     */
    public static MediaDetail.PlaySource next(MediaDetail detail, String currentName) {
        if (detail == null || detail.playSources == null || detail.playSources.size() < 2) return null;
        int count = detail.playSources.size();
        int current = -1;
        for (int i = 0; i < count; i++) {
            MediaDetail.PlaySource candidate = detail.playSources.get(i);
            if (candidate != null && candidate.name != null && candidate.name.equals(currentName)) {
                current = i;
                break;
            }
        }
        for (int step = 1; step <= count; step++) {
            MediaDetail.PlaySource candidate = detail.playSources.get((current + step) % count);
            if (candidate == null || candidate.name == null) continue;
            if (candidate.name.equals(currentName)) continue;
            if (candidate.episodes == null || candidate.episodes.isEmpty()) continue;
            return candidate;
        }
        return null;
    }

    /**
     * The episode that follows {@code episodeId} in the same line, or null at the end.
     *
     * <p>Auto-advance is the behaviour that makes watching a series possible; this is its rule set.
     */
    public static MediaDetail.Episode nextEpisode(MediaDetail.PlaySource line, String episodeId) {
        if (line == null || line.episodes == null || line.episodes.isEmpty()) return null;
        for (int i = 0; i < line.episodes.size(); i++) {
            if (line.episodes.get(i).id.equals(episodeId)) {
                return i + 1 < line.episodes.size() ? line.episodes.get(i + 1) : null;
            }
        }
        // Position unknown (for example right after a line switch): resume at the second episode.
        return line.episodes.size() > 1 ? line.episodes.get(1) : null;
    }

    /** The episode at {@code index + delta}, clamped to the list. Null when there is nothing to do. */
    public static MediaDetail.Episode stepEpisode(MediaDetail.PlaySource line, String episodeId,
                                                  int delta) {
        if (line == null || line.episodes == null || line.episodes.isEmpty()) return null;
        if (delta == 0) return null;
        int index = -1;
        for (int i = 0; i < line.episodes.size(); i++) {
            if (line.episodes.get(i).id.equals(episodeId)) index = i;
        }
        if (index < 0) index = 0;
        int target = index + delta;
        if (target < 0) target = 0;
        if (target >= line.episodes.size()) target = line.episodes.size() - 1;
        if (target == index) return null;
        return line.episodes.get(target);
    }

    /** The line of a detail that matches {@code name}, or the first line when nothing matches. */
    public static MediaDetail.PlaySource lineOf(MediaDetail detail, String name) {
        if (detail == null || detail.playSources == null || detail.playSources.isEmpty()) return null;
        for (MediaDetail.PlaySource candidate : detail.playSources) {
            if (candidate.name != null && candidate.name.equals(name)) return candidate;
        }
        return detail.playSources.get(0);
    }

    /** The same episode in another line, or the first episode when the line numbers differ. */
    public static MediaDetail.Episode episodeOf(MediaDetail.PlaySource line, String episodeId) {
        if (line == null || line.episodes == null || line.episodes.isEmpty()) return null;
        if (episodeId != null && !episodeId.isEmpty()) {
            for (MediaDetail.Episode episode : line.episodes) {
                if (episodeId.equals(episode.id)) return episode;
            }
        }
        return line.episodes.get(0);
    }
}
