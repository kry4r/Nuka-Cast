package com.nukacast.app.player;

import java.util.List;

/**
 * The cycling rules of the player menu's 音轨 and 字幕 entries.
 *
 * <p>Kept apart from the activity so the rules can be tested: the menu hands out labels from
 * {@link PlayerController} (where the selected track is the one with a check mark) and this decides
 * what the next press means.
 */
public final class PlayerTrackMenu {

    /** The marker a label carries while its track is the selected one. */
    public static final String SELECTED = "\u2713";

    private PlayerTrackMenu() {
    }

    /** The selected track's name ("中文 · AAC ✓" → "中文 · AAC"), or "关" when nothing is selected. */
    public static String selectedName(List<String> labels) {
        if (labels == null) return "关";
        for (String label : labels) {
            if (label != null && label.endsWith(SELECTED)) {
                return label.substring(0, label.length() - SELECTED.length()).trim();
            }
        }
        return "关";
    }

    /**
     * The track the next press selects: each entry in turn, then off.
     *
     * <p>Off comes last so that pressing the entry again and again always ends with the picture clear of
     * text, which is what a viewer wants when the subtitles sit over the action.
     *
     * @return an index into the labels, or -1 for "off"
     */
    public static int nextIndex(List<String> labels) {
        if (labels == null || labels.isEmpty()) return -1;
        for (int i = 0; i < labels.size(); i++) {
            String label = labels.get(i);
            if (label != null && label.endsWith(SELECTED)) {
                return i + 1 < labels.size() ? i + 1 : -1;
            }
        }
        // Nothing selected yet: the first press turns the first track on, or the only one there is.
        return 0;
    }
}
