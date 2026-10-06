package com.nukacast.app.drama;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nukacast.app.drama.model.DramaLine;
import com.nukacast.app.tvbox.model.SearchItem;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DramaTitleMatcherTest {
    @Test public void exactMatchIgnoresPunctuationAndSpacing() {
        DramaTitleMatcher.Match match = DramaTitleMatcher.match(
                "重生2000：靠山吃山成首富", "重生2000 靠山吃山成首富");
        assertEquals(DramaTitleMatcher.SCORE_EXACT, match.score);
        assertEquals("exact", match.kind);
    }

    @Test public void exactMatchHandlesFullWidthCharacters() {
        DramaTitleMatcher.Match match = DramaTitleMatcher.match("测试ＡＢ", "测试ab");
        assertEquals(DramaTitleMatcher.SCORE_EXACT, match.score);
    }

    @Test public void prefixMatchIsCandidateButNotExact() {
        DramaTitleMatcher.Match match = DramaTitleMatcher.match(
                "重生2000：靠山吃山成首富", "重生2000：靠山吃山成首富全集");
        assertEquals(DramaTitleMatcher.SCORE_PREFIX, match.score);
        assertFalse(new DramaLine().isExact());
    }

    @Test public void shortOverlapIsRejected() {
        assertEquals(0, DramaTitleMatcher.match("重生", "重生七零").score);
        assertEquals(0, DramaTitleMatcher.match("剧", "剧集").score);
    }

    @Test public void containsMatchAlsoQualifies() {
        DramaTitleMatcher.Match match = DramaTitleMatcher.match(
                "重生七零小辣媳第二季", "重生七零小辣媳第二季完结版");
        assertTrue(match.score >= DramaTitleMatcher.SCORE_CONTAINS);
    }

    @Test public void seasonConflictIsRejected() {
        assertEquals(0, DramaTitleMatcher.match(
                "重生七零小辣媳第二季", "重生七零小辣媳第三季").score);
        assertEquals(0, DramaTitleMatcher.match(
                "测试剧第2季", "测试剧第3季").score);
    }

    @Test public void matchingSeasonNumberIsAccepted() {
        DramaTitleMatcher.Match match = DramaTitleMatcher.match(
                "重生七零小辣媳第二季", "重生七零小辣媳第2季");
        assertTrue(match.score > 0);
    }

    @Test public void missingSeasonOnOneSideIsOnlyWeakEvidence() {
        DramaTitleMatcher.Match match = DramaTitleMatcher.match(
                "重生七零小辣媳第二季", "重生七零小辣媳第二季");
        assertEquals("exact", match.kind);
        DramaTitleMatcher.Match oneSided = DramaTitleMatcher.match(
                "重生七零小辣媳第二季", "重生七零小辣媳");
        assertEquals(DramaTitleMatcher.SCORE_CONTAINS, oneSided.score);
        assertEquals("contains", oneSided.kind);
    }

    @Test public void chineseNumeralsAreParsed() {
        assertEquals(2, DramaTitleMatcher.season("重生七零小辣媳第二季"));
        assertEquals(12, DramaTitleMatcher.season("测试剧第十二季"));
        assertEquals(20, DramaTitleMatcher.season("测试剧第二十季"));
        assertEquals(10, DramaTitleMatcher.season("测试剧第十季"));
        assertEquals(0, DramaTitleMatcher.season("测试剧"));
    }

    @Test public void rankSortsExactFirstAndDeduplicates() {
        List<SearchItem> items = new ArrayList<SearchItem>();
        items.add(item("s1", "重生2000：靠山吃山成首富全集", "site-a", "10"));
        items.add(item("s1", "重生2000：靠山吃山成首富", "site-b", "11"));
        items.add(item("s1", "重生2000：靠山吃山成首富", "site-b", "11"));
        items.add(item("s1", "完全无关的剧", "site-c", "12"));
        List<DramaLine> lines = DramaTitleMatcher.rank(items, "重生2000：靠山吃山成首富");
        assertEquals(2, lines.size());
        assertEquals("site-b", lines.get(0).siteName);
        assertTrue(lines.get(0).isExact());
        assertEquals("11", lines.get(0).vodId);
        assertEquals("site-a", lines.get(1).siteName);
        assertFalse(lines.get(1).isExact());
    }

    @Test public void rankKeepsSameTitleFromDifferentSites() {
        List<SearchItem> items = Arrays.asList(
                item("s1", "同名短剧", "site-a", "1"),
                item("s2", "同名短剧", "site-b", "2"));
        List<DramaLine> lines = DramaTitleMatcher.rank(items, "同名短剧");
        assertEquals(2, lines.size());
        assertEquals("1", lines.get(0).vodId);
        assertEquals("2", lines.get(1).vodId);
    }

    @Test public void rankTreatsNullInputAsEmpty() {
        assertTrue(DramaTitleMatcher.rank(null, "任意").isEmpty());
        assertTrue(DramaTitleMatcher.rank(new ArrayList<SearchItem>(), "任意").isEmpty());
    }

    private static SearchItem item(String sourceId, String name, String siteName, String vodId) {
        SearchItem item = new SearchItem();
        item.sourceId = sourceId;
        item.name = name;
        item.siteKey = "site-" + sourceId;
        item.siteName = siteName;
        item.vodId = vodId;
        item.remarks = "全80集";
        return item;
    }
}
