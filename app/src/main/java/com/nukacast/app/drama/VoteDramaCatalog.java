package com.nukacast.app.drama;

import com.nukacast.app.drama.model.DramaDetail;
import com.nukacast.app.drama.model.DramaItem;
import com.nukacast.app.drama.model.DramaProviderConfig;
import com.nukacast.app.drama.model.DramaSearchResult;
import com.nukacast.app.net.HttpStack;
import com.nukacast.app.net.ResponseBodies;

import java.io.IOException;
import java.nio.charset.Charset;

import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Read-only catalog adapter for the vote-style reference site (observed contract 2026-10-06):
 * {@code /api/search?q=}, {@code /api/drama?id=} and {@code /api/related?id=&title=}.
 *
 * <p>The site ranks Red-Fruit short dramas through Nostr events and does not publish episodes or
 * playback urls. This adapter therefore only maps catalog metadata; playback is matched against
 * the user's own TVBox sources.
 */
public final class VoteDramaCatalog implements DramaCatalog {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 4.4; NukaCast) AppleWebKit/537.36";

    private final DramaProviderConfig config;

    public VoteDramaCatalog(DramaProviderConfig config) {
        this.config = config;
    }

    @Override public DramaProviderConfig config() { return config; }

    @Override public DramaSearchResult search(String keyword) throws Exception {
        HttpUrl url = endpoint("search").addQueryParameter("q", safe(keyword)).build();
        return DramaCatalogParser.parseSearch(get(url), config.id, keyword);
    }

    @Override public DramaDetail detail(String dramaId) throws Exception {
        HttpUrl url = endpoint("drama").addQueryParameter("id", safe(dramaId)).build();
        DramaDetail detail = DramaCatalogParser.parseDetail(get(url), config.id, dramaId);
        attachRelated(detail);
        return detail;
    }

    /**
     * Related entries are optional: a failure here must degrade to an empty list and never turn a
     * successful detail lookup into an error.
     */
    private void attachRelated(DramaDetail detail) {
        try {
            HttpUrl url = endpoint("related")
                    .addQueryParameter("id", safe(detail.item.dramaId))
                    .addQueryParameter("title", safe(detail.item.title))
                    .build();
            detail.relatedTotal = DramaCatalogParser.parseRelated(
                    get(url), config.id, detail.related);
        } catch (Exception error) {
            detail.relatedPartial = true;
            detail.relatedTotal = -1;
        }
    }

    private HttpUrl.Builder endpoint(String name) {
        HttpUrl base = HttpUrl.parse(config.baseUrl);
        if (base == null) {
            throw new IllegalArgumentException("目录地址无效");
        }
        return base.newBuilder().addPathSegment("api").addPathSegment(name);
    }

    private String get(HttpUrl url) throws DramaException, IOException {
        Request request = new Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .build();
        try (Response response = HttpStack.client().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new DramaException("http_error", "目录 HTTP " + response.code()
                        + "（" + url.host() + "）");
            }
            return ResponseBodies.string(response.body(), MAX_BYTES, UTF_8);
        }
    }

    static DramaItem firstItem(DramaDetail detail) {
        return detail == null ? null : detail.item;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
