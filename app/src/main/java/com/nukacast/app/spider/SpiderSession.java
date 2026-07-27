package com.nukacast.app.spider;

import java.util.HashMap;
import java.util.List;

interface SpiderSession {
    String home(boolean filter) throws Exception;
    String category(String id, String page, boolean filter, HashMap<String, String> extend) throws Exception;
    String detail(List<String> ids) throws Exception;
    String search(String keyword, boolean quick, String page) throws Exception;
    String play(String flag, String id, List<String> vipFlags) throws Exception;
    void destroy();
}
