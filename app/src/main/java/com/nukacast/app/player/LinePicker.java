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
