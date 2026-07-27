package com.nukacast.app.spider;

import com.github.catvod.crawler.Spider;

import java.util.HashMap;
import java.util.List;

final class JavaSpiderSession implements SpiderSession {
    private final Spider spider;

    JavaSpiderSession(Spider spider) {
        this.spider = spider;
    }

    @Override public String home(boolean filter) throws Exception {
        return spider.homeContent(filter);
    }

    @Override public String category(String id, String page, boolean filter,
                                     HashMap<String, String> extend) throws Exception {
        return spider.categoryContent(id, page, filter, extend);
    }

    @Override public String detail(List<String> ids) throws Exception {
        return spider.detailContent(ids);
    }

    @Override public String search(String keyword, boolean quick, String page) throws Exception {
        return spider.searchContent(keyword, quick, page);
    }

    @Override public String play(String flag, String id, List<String> vipFlags) throws Exception {
        return spider.playerContent(flag, id, vipFlags);
    }

    @Override public void destroy() {
        spider.destroy();
    }
}
