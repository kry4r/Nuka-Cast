package com.nukacast.app.tvbox;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Titles this device has already seen, indexed by pinyin initials.
 *
 * <p>CMS search matches characters, so "LLDQ" finds nothing no matter how many times it is typed.
 * The titles themselves, though, pass through the app constantly — home lists, search results,
 * drama catalogs. Keeping their initials lets an initials query be translated into a real title and
 * searched for real.
 *
 * <p>Bounded by design: the TV has 1.5 GB of RAM and this index must never become the reason it
 * runs out. Oldest titles are dropped first.
 */
public final class TitleIndex {
    private static final int MAX_TITLES = 3000;
    /** A query can legitimately match several titles; a few candidates keep the search bounded. */
    private static final int MAX_CANDIDATES = 3;

    private final Set<String> seen = new HashSet<String>();
    private final Deque<String> order = new ArrayDeque<String>();
    private final java.util.Map<String, LinkedHashSet<String>> byInitials =
            new java.util.HashMap<String, LinkedHashSet<String>>();

    /** Records a title. Cheap and safe to call for every item rendered. */
    public synchronized void add(String title) {
        String value = title == null ? "" : title.trim();
        if (value.length() < 2 || value.length() > 60) return;
        if (byInitials.size() >= MAX_TITLES && seen.contains(value)) return;
        if (!seen.add(value)) return;
        String initials = PinyinInitials.of(value);
        if (initials.isEmpty()) {
            seen.remove(value);
            return;
        }
        order.addLast(value);
        LinkedHashSet<String> bucket = byInitials.get(initials);
        if (bucket == null) {
            bucket = new LinkedHashSet<String>();
            byInitials.put(initials, bucket);
        }
        bucket.add(value);
        while (order.size() > MAX_TITLES) {
            String oldest = order.removeFirst();
            seen.remove(oldest);
            String oldestInitials = PinyinInitials.of(oldest);
            LinkedHashSet<String> values = byInitials.get(oldestInitials);
            if (values != null) {
                values.remove(oldest);
                if (values.isEmpty()) byInitials.remove(oldestInitials);
            }
        }
    }

    public synchronized void addAll(List<String> titles) {
        if (titles == null) return;
        for (String title : titles) add(title);
    }

    /**
     * Titles whose initials start with {@code initialsQuery}, best (shortest) first — "lldq" should
     * offer 流浪地球 before 流浪地球2：再次冒险.
     */
    public synchronized List<String> match(String initialsQuery, int limit) {
        List<String> result = new ArrayList<String>();
        if (initialsQuery == null || initialsQuery.isEmpty()) return result;
        String query = initialsQuery.toLowerCase(java.util.Locale.ROOT);
        for (java.util.Map.Entry<String, LinkedHashSet<String>> entry : byInitials.entrySet()) {
            if (!entry.getKey().startsWith(query)) continue;
            for (String title : entry.getValue()) {
                result.add(title);
                if (result.size() >= Math.max(1, limit)) break;
            }
            if (result.size() >= Math.max(1, limit)) break;
        }
        java.util.Collections.sort(result, new java.util.Comparator<String>() {
            @Override public int compare(String left, String right) {
                return left.length() - right.length();
            }
        });
        return result.size() > MAX_CANDIDATES ? new ArrayList<String>(result.subList(0, MAX_CANDIDATES))
                : result;
    }

    public synchronized int size() {
        return byInitials.size();
    }

    public synchronized void clear() {
        seen.clear();
        order.clear();
        byInitials.clear();
    }
}
