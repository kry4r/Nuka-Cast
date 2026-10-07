package com.nukacast.app.drama;

import com.nukacast.app.drama.model.DramaDetail;
import com.nukacast.app.drama.model.DramaEpisode;
import com.nukacast.app.drama.model.DramaProviderConfig;
import com.nukacast.app.drama.model.DramaSearchResult;

public interface DramaCatalog {
    DramaProviderConfig config();

    DramaSearchResult search(String keyword) throws Exception;

    DramaDetail detail(String dramaId) throws Exception;

    /** Category browsing for providers that publish listings; others report it as unsupported. */
    default DramaSearchResult browse(String categoryId, int page) throws Exception {
        return DramaSearchResult.failure(config().id, "", "browse_unsupported",
                "", "该目录只支持按剧名搜索");
    }

    /** Resolves one episode; only direct-play providers have to implement this. */
    default DramaEpisode episode(String dramaId, int index) throws Exception {
        throw new DramaException("play_unsupported", "该目录只提供剧目资料，播放请使用播放线路");
    }
}
