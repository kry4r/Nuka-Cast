import io

p = 'app/src/main/java/com/nukacast/app/MainActivity.java'
s = io.open(p, encoding='utf-8', newline='').read().replace('\r\n', '\n')


def swap(old, new, count=1):
    global s
    assert old in s, 'MISSING: ' + old[:130]
    s = s.replace(old, new, count)


# ---- state -------------------------------------------------------------------------------------
swap("""    private String browseCategoryId = "";""",
"""    private String browseCategoryId = "";
    /** 年份/地区/语言 selection of the browse page (filtering happens on the device). */
    private com.nukacast.app.tvbox.BrowseFilter browseFilter = new com.nukacast.app.tvbox.BrowseFilter();""")

# ---- the filter bar ----------------------------------------------------------------------------
swap("""    private void renderCategoryGrid(String siteKey, String categoryId, int page,
                                    List<SearchItem> items) {""",
"""    /**
     * The 年份/地区/语言 bar of the browse page.
     *
     * <p>Shown only for plain CMS sites: plugin sites return whatever they like, and a filter that
     * silently matches nothing is worse than no filter.
     */
    private void renderBrowseFilterBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        filtersRow(bar, "年份", com.nukacast.app.tvbox.BrowseFilter.YEARS, browseFilter.year, "year");
        filtersRow(bar, "地区", com.nukacast.app.tvbox.BrowseFilter.AREAS, browseFilter.area, "area");
        moviesContent.addView(bar);
    }

    private void filtersRow(LinearLayout bar, String label, List<String> values,
                            String selected, final String kind) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = bodyText(label);
        title.setTextSize(12);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(dp(46), dp(30));
        title.setLayoutParams(titleParams);
        row.addView(title);
        Button all = actionButton("全部", 0);
        all.setSelected(selected.isEmpty());
        all.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { applyBrowseFilter(kind, ""); }
        });
        row.addView(all);
        for (final String value : values) {
            Button chip = actionButton(value, 0);
            chip.setSelected(value.equals(selected));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { applyBrowseFilter(kind, value); }
            });
            row.addView(chip);
        }
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34));
        params.bottomMargin = dp(2);
        bar.addView(scroll, params);
    }

    /** Applies one filter value and reloads the first page. */
    private void applyBrowseFilter(String kind, String value) {
        if ("year".equals(kind)) browseFilter = browseFilter.withYear(value);
        else if ("area".equals(kind)) browseFilter = browseFilter.withArea(value);
        else browseFilter = browseFilter.withLang(value);
        AppLog.i("影视", "分类筛选：" + (browseFilter.isEmpty() ? "全部" : browseFilter.label()));
        loadCategoryPage(1);
    }

    private void renderCategoryGrid(String siteKey, String categoryId, int page,
                                    List<SearchItem> items) {""")

# The grid header gains the filter bar and a status line.
swap("""        if (page <= 1) {
            moviesContent.removeAllViews();
            moviesContent.addView(sectionTitle(siteName + " · " + categoryName));
        } else {""",
"""        if (page <= 1) {
            moviesContent.removeAllViews();
            moviesContent.addView(sectionTitle(siteName + " · " + categoryName
                    + (browseFilter.isEmpty() ? "" : " · " + browseFilter.label())));
            renderBrowseFilterBar();
        } else {""")

swap("""        if (items.isEmpty() && page <= 1) {
            moviesContent.addView(bodyText("该分类没有返回内容，可换一个分类或站点。"));
            return;
        }""",
"""        if (items.isEmpty() && page <= 1) {
            moviesContent.addView(bodyText(browseFilter.isEmpty()
                    ? "该分类没有返回内容，可换一个分类或站点。"
                    : "这个筛选条件下暂时没有内容：本站点忽略年份/地区筛选条件，"
                            + "所以结果是在已抓取的记录里匹配出来的，可取消筛选或换一个站点。"));
            return;
        }""")

# ---- loading with the filter -------------------------------------------------------------------
swap("""                List<SearchItem> items = new java.util.ArrayList<SearchItem>();
                String failure = "";
                String categoryId = browseCategoryId;
                int attempts = 0;
                while (true) {
                    try {
                        List<SearchItem> fetched = runtime.getContentService()
                                .browse(sourceId, siteKey, categoryId, page);
                        items = fetched == null
                                ? new java.util.ArrayList<SearchItem>() : fetched;
                    } catch (Exception error) {
                        failure = error.getMessage() == null ? "加载失败" : error.getMessage();
                        break;
                    }""",
"""                List<SearchItem> items = new java.util.ArrayList<SearchItem>();
                String failure = "";
                String categoryId = browseCategoryId;
                final com.nukacast.app.tvbox.BrowseFilter filter = browseFilter;
                int attempts = 0;
                while (true) {
                    try {
                        List<SearchItem> fetched = runtime.getContentService()
                                .browseFiltered(sourceId, siteKey, categoryId, page, filter);
                        items = fetched == null
                                ? new java.util.ArrayList<SearchItem>() : fetched;
                        if (!filter.isEmpty() && items.isEmpty()) {
                            // The scan may simply not have reached this page yet; only a finished scan
                            // means "there is nothing".
                            if (!runtime.getContentService().filterScanComplete(
                                    sourceId, siteKey, categoryId, filter)) {
                                failure = "筛选扫描中，请稍后重试";
                                break;
                            }
                        }
                    } catch (Exception error) {
                        failure = error.getMessage() == null ? "加载失败" : error.getMessage();
                        break;
                    }""")

# Changing category or site resets the filter, which belongs to a category.
swap("""    private void loadCategoryPage(final int page) {""",
"""    private void loadCategoryPage(final int page) {""")

io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('browse filter bar wired')
