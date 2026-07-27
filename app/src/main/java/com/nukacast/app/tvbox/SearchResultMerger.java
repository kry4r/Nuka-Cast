package com.nukacast.app.tvbox;

import com.nukacast.app.tvbox.model.SearchItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SearchResultMerger {
    private SearchResultMerger() {}

    static List<SearchItem> merge(List<List<SearchItem>> groups, int pageSize) {
        Map<String, SearchItem> deduped = new LinkedHashMap<String, SearchItem>();
        if (groups != null) {
            for (List<SearchItem> group : groups) {
                if (group == null) continue;
                for (SearchItem item : group) {
                    if (item == null) continue;
                    String key = item.dedupeKey();
                    if (!deduped.containsKey(key)) {
                        deduped.put(key, item);
                    }
                }
            }
        }
        List<SearchItem> result = new ArrayList<SearchItem>(deduped.values());
        int limit = Math.max(1, pageSize);
        if (result.size() > limit) {
            result.subList(limit, result.size()).clear();
        }
        return result;
    }
}
