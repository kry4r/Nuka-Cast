package com.nukacast.app.library;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class LibraryItems {
    private LibraryItems() {}

    public static List<LibraryItem> upsert(List<LibraryItem> current, LibraryItem updated, int limit) {
        List<LibraryItem> result = current == null
                ? new ArrayList<LibraryItem>() : new ArrayList<LibraryItem>(current);
        String key = updated.stableKey();
        for (int i = result.size() - 1; i >= 0; i--) {
            LibraryItem item = result.get(i);
            if (item == null || key.equals(item.stableKey())) result.remove(i);
        }
        result.add(updated);
        Collections.sort(result, new Comparator<LibraryItem>() {
            @Override public int compare(LibraryItem left, LibraryItem right) {
                return left.updatedAt == right.updatedAt ? 0 : left.updatedAt > right.updatedAt ? -1 : 1;
            }
        });
        if (result.size() > Math.max(1, limit)) {
            result.subList(Math.max(1, limit), result.size()).clear();
        }
        return result;
    }

    /** True when this entry is the one the caller asked to remove. */
    public static boolean matches(LibraryItem item, String key) {
        if (item == null || key == null || key.isEmpty()) return false;
        // The web console and the debug API may know either the site id or just the title.
        return key.equals(item.vodId) || key.equals(item.name) || key.equals(item.stableKey());
    }

    /**
     * Keeps everything that does not match {@code key}.
     *
     * @return the surviving list (never null)
     */
    public static List<LibraryItem> removeMatching(List<LibraryItem> current, String key) {
        List<LibraryItem> result = current == null
                ? new ArrayList<LibraryItem>() : new ArrayList<LibraryItem>(current);
        for (int i = result.size() - 1; i >= 0; i--) {
            if (matches(result.get(i), key)) result.remove(i);
        }
        return result;
    }

    /** How many entries {@code removeMatching} would drop for {@code key}. */
    public static int countMatching(List<LibraryItem> current, String key) {
        if (current == null) return 0;
        int count = 0;
        for (LibraryItem item : current) {
            if (matches(item, key)) count++;
        }
        return count;
    }

    public static List<LibraryItem> remove(List<LibraryItem> current, String stableKey) {
        List<LibraryItem> result = current == null
                ? new ArrayList<LibraryItem>() : new ArrayList<LibraryItem>(current);
        for (int i = result.size() - 1; i >= 0; i--) {
            LibraryItem item = result.get(i);
            if (item == null || stableKey.equals(item.stableKey())) result.remove(i);
        }
        return result;
    }
}
