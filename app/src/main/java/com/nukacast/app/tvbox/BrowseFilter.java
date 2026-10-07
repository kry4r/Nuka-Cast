package com.nukacast.app.tvbox;

import com.nukacast.app.tvbox.model.SearchItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The 年份 / 地区 / 语言 selection of the browse page.
 *
 * <p>MacCMS JSON APIs ignore {@code &year=}, {@code &area=} and {@code &lang=} — measured on a live
 * site where every combination returned the same 5330 records — so the filtering has to happen on the
 * device, over the records the site does return (each carries {@code vod_year} and {@code vod_area}).
 */
public final class BrowseFilter {
    /** Years offered, newest first. */
    public static final List<String> YEARS = Arrays.asList(
            "2026", "2025", "2024", "2023", "2022", "2021", "2020", "2019", "2018", "2017", "2016", "2015");

    public static final List<String> AREAS = Arrays.asList(
            "大陆", "香港", "台湾", "美国", "韩国", "日本", "英国", "法国", "泰国", "印度", "其它");

    public static final List<String> LANGS = Arrays.asList("国语", "粤语", "英语", "日语", "韩语");

    public String year = "";
    public String area = "";
    public String lang = "";

    public BrowseFilter() {}

    public BrowseFilter(String year, String area, String lang) {
        this.year = year == null ? "" : year;
        this.area = area == null ? "" : area;
        this.lang = lang == null ? "" : lang;
    }

    public boolean isEmpty() {
        return year.isEmpty() && area.isEmpty() && lang.isEmpty();
    }

    /** A copy with the year replaced, or cleared when {@code value} is empty. */
    public BrowseFilter withYear(String value) {
        return new BrowseFilter(value, area, lang);
    }

    public BrowseFilter withArea(String value) {
        return new BrowseFilter(year, value, lang);
    }

    public BrowseFilter withLang(String value) {
        return new BrowseFilter(year, area, value);
    }

    /** Stable key for caching the scanned pages of one filter combination. */
    public String key() {
        return year + "|" + area + "|" + lang;
    }

    /** True when the record satisfies every selected value. */
    public boolean matches(SearchItem item) {
        if (item == null) return false;
        if (!year.isEmpty() && !year.equals(safe(item.year))) return false;
        if (!area.isEmpty() && !areaMatches(item.area)) return false;
        if (!lang.isEmpty() && !langMatches(item.lang)) return false;
        return true;
    }

    /**
     * Region matching is deliberately loose: sites write 大陆, 中国大陆, China, 内地 for the same thing,
     * and a strict comparison would silently drop most of the catalogue.
     */
    boolean areaMatches(String value) {
        String candidate = safe(value);
        if (candidate.isEmpty()) return false;
        if (candidate.contains(area)) return true;
        if ("大陆".equals(area)) {
            // Measured on a live site: 中国香港 and 中国台湾 carry the 中国 prefix, so a plain
            // "contains 中国" test put Hong Kong and Taiwan films under 大陆.
            if (candidate.contains("香港") || candidate.contains("台湾") || candidate.contains("澳门")) {
                return false;
            }
            return candidate.contains("中国") || candidate.contains("内地")
                    || candidate.equalsIgnoreCase("china") || candidate.contains("大陆");
        }
        if ("美国".equals(area)) return candidate.equalsIgnoreCase("usa") || candidate.contains("美");
        if ("英国".equals(area)) return candidate.contains("英");
        if ("韩国".equals(area)) return candidate.contains("韩");
        if ("日本".equals(area)) return candidate.contains("日");
        if ("其它".equals(area)) {
            for (String known : AREAS) {
                if (!"其它".equals(known) && candidate.contains(known)) return false;
            }
            return true;
        }
        return false;
    }

    boolean langMatches(String value) {
        String candidate = safe(value);
        if (candidate.isEmpty()) return false;
        if (candidate.contains(lang)) return true;
        if ("国语".equals(lang)) return candidate.contains("普通话") || candidate.contains("汉语");
        if ("英语".equals(lang)) return candidate.contains("英文") || candidate.equalsIgnoreCase("english");
        return false;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    /** The filter's values as a display string, e.g. {@code 2024 · 大陆}. */
    public String label() {
        List<String> parts = new ArrayList<String>();
        if (!year.isEmpty()) parts.add(year);
        if (!area.isEmpty()) parts.add(area);
        if (!lang.isEmpty()) parts.add(lang);
        StringBuilder text = new StringBuilder();
        for (String part : parts) {
            if (text.length() > 0) text.append(" · ");
            text.append(part);
        }
        return text.toString();
    }
}
