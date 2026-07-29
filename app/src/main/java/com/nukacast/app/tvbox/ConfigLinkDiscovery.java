package com.nukacast.app.tvbox;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.HttpUrl;

public final class ConfigLinkDiscovery {
    private static final int MAX_CANDIDATES = 8;

    private ConfigLinkDiscovery() {}

    public static List<String> candidates(String html, String baseUrl) {
        if (html == null || html.isEmpty()) return Collections.emptyList();
        Document document = Jsoup.parse(html, baseUrl == null ? "" : baseUrl);
        Map<String, Candidate> unique = new LinkedHashMap<String, Candidate>();
        int order = 0;
        for (Element link : document.select("a[href]")) {
            String resolved = link.absUrl("href");
            HttpUrl url = HttpUrl.parse(resolved);
            if (url == null) continue;
            int rank = rank(url);
            if (rank < 0 || unique.containsKey(url.toString())) continue;
            unique.put(url.toString(), new Candidate(url.toString(), rank, order++));
        }
        List<Candidate> ranked = new ArrayList<Candidate>(unique.values());
        Collections.sort(ranked, new Comparator<Candidate>() {
            @Override public int compare(Candidate left, Candidate right) {
                int byRank = left.rank - right.rank;
                return byRank != 0 ? byRank : left.order - right.order;
            }
        });
        List<String> result = new ArrayList<String>();
        for (Candidate candidate : ranked) {
            if (result.size() >= MAX_CANDIDATES) break;
            result.add(candidate.url);
        }
        return result;
    }

    private static int rank(HttpUrl url) {
        String path = url.encodedPath().toLowerCase(Locale.US);
        String value = url.toString().toLowerCase(Locale.US);
        if (path.endsWith(".json")) return 0;
        if (value.contains("tvbox") || value.contains("config")) return 1;
        if (path.endsWith(".php")) return 2;
        return -1;
    }

    private static final class Candidate {
        final String url;
        final int rank;
        final int order;

        Candidate(String url, int rank, int order) {
            this.url = url;
            this.rank = rank;
            this.order = order;
        }
    }
}
