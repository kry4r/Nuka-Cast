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
