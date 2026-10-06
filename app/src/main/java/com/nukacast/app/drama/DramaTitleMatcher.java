package com.nukacast.app.drama;

import com.nukacast.app.drama.model.DramaLine;
import com.nukacast.app.tvbox.model.SearchItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ranks playback candidates for a drama title across TVBox search hits.
 *
 * <p>Only normalized exact, prefix and contains matches become candidates, and season markers must
 * agree when both sides carry one. The caller still has to let the user confirm the choice: this
 * class exists to present evidence, not to silently auto-play "a similar drama".
 */
public final class DramaTitleMatcher {
    public static final int SCORE_EXACT = 100;
    public static final int SCORE_PREFIX = 80;
    public static final int SCORE_CONTAINS = 70;
    private static final int MIN_OVERLAP_LENGTH = 4;

    private DramaTitleMatcher() {}

    public static final class Match {
        public final int score;
        public final String kind;

        Match(int score, String kind) {
            this.score = score;
            this.kind = kind;
        }

        public boolean isCandidate() { return score > 0; }
    }

    public static Match match(String target, String candidate) {
        String left = normalize(target);
        String right = normalize(candidate);
        if (left.isEmpty() || right.isEmpty()) return new Match(0, "");
        int leftSeason = season(left);
        int rightSeason = season(right);
        boolean seasonConflict = leftSeason > 0 && rightSeason > 0 && leftSeason != rightSeason;
        if (seasonConflict) return new Match(0, "");
        boolean seasonUncertain = (leftSeason > 0) != (rightSeason > 0);
        if (left.equals(right)) {
            return new Match(seasonUncertain ? SCORE_CONTAINS : SCORE_EXACT,
                    seasonUncertain ? "contains" : "exact");
        }
        String shorter = left.length() <= right.length() ? left : right;
        String longer = shorter == left ? right : left;
        if (shorter.length() < MIN_OVERLAP_LENGTH) return new Match(0, "");
        if (!longer.contains(shorter)) return new Match(0, "");
        if (seasonUncertain) return new Match(SCORE_CONTAINS, "contains");
        if (longer.startsWith(shorter)) return new Match(SCORE_PREFIX, "prefix");
        return new Match(SCORE_CONTAINS, "contains");
    }

    /** Builds deduplicated, score-ordered playback candidates. */
    public static List<DramaLine> rank(List<SearchItem> items, String targetTitle) {
        List<DramaLine> result = new ArrayList<DramaLine>();
        if (items == null || items.isEmpty()) return result;
        Set<String> seen = new LinkedHashSet<String>();
        for (SearchItem item : items) {
            if (item == null) continue;
            Match match = match(targetTitle, item.name);
            if (!match.isCandidate()) continue;
            String key = safe(item.siteKey) + "|" + safe(item.vodId);
            if (!seen.add(key)) continue;
            DramaLine line = new DramaLine();
            line.sourceId = safe(item.sourceId);
            line.siteKey = safe(item.siteKey);
            line.siteName = safe(item.siteName);
            line.vodId = safe(item.vodId);
            line.name = safe(item.name);
            line.remarks = safe(item.remarks);
            line.poster = safe(item.poster);
            line.year = safe(item.year);
            line.typeName = safe(item.typeName);
            line.episodeHint = safe(item.remarks);
            line.matchScore = match.score;
            line.matchKind = match.kind;
            result.add(line);
        }
        Collections.sort(result, new Comparator<DramaLine>() {
            @Override public int compare(DramaLine left, DramaLine right) {
                if (left.matchScore != right.matchScore) {
                    return right.matchScore - left.matchScore;
                }
                int bySite = safe(left.siteName).compareToIgnoreCase(safe(right.siteName));
                if (bySite != 0) return bySite;
                return safe(left.vodId).compareTo(safe(right.vodId));
            }
        });
        return result;
    }

    /** Lowercase, half-width, punctuation-free form used for matching. */
    public static String normalize(String value) {
        if (value == null) return "";
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character >= 0xFF01 && character <= 0xFF5E) {
                character = (char) (character - 0xFEE0);
            } else if (character == 0x3000) {
                character = ' ';
            }
            if (Character.isLetterOrDigit(character)) {
                builder.append(Character.toLowerCase(character));
            }
        }
        return normalizeSeasons(builder.toString());
    }

    /** Rewrites second/part markers to Arabic digits so 第二季 and 第2季 compare equal. */
    static String normalizeSeasons(String title) {
        StringBuilder output = new StringBuilder(title.length());
        int index = 0;
        while (index < title.length()) {
            char character = title.charAt(index);
            if (character == '第') {
                int digitsEnd = index + 1;
                StringBuilder digits = new StringBuilder();
                while (digitsEnd < title.length() && Character.isDigit(title.charAt(digitsEnd))) {
                    digits.append(title.charAt(digitsEnd));
                    digitsEnd++;
                }
                if (digits.length() > 0 && digitsEnd < title.length()
                        && isSeasonUnit(title.charAt(digitsEnd))) {
                    output.append('第').append(Integer.parseInt(digits.toString()))
                            .append(title.charAt(digitsEnd));
                    index = digitsEnd + 1;
                    continue;
                }
                int packed = chineseNumber(title, index + 1);
                if (packed > 0) {
                    int end = packed % 1000;
                    if (end + 1 < title.length() && isSeasonUnit(title.charAt(end + 1))) {
                        output.append('第').append(packed / 1000)
                                .append(title.charAt(end + 1));
                        index = end + 2;
                        continue;
                    }
                }
            }
            output.append(character);
            index++;
        }
        return output.toString();
    }

    private static boolean isSeasonUnit(char character) {
        return character == '季' || character == '部';
    }

    /** Extracts the season number from 第N季/第N部, or 0 when the title has no season marker. */
    public static int season(String normalizedTitle) {
        String title = safe(normalizedTitle);
        for (int i = 0; i + 2 <= title.length(); i++) {
            if (title.charAt(i) != '第') continue;
            int end = i + 1;
            StringBuilder digits = new StringBuilder();
            while (end < title.length() && Character.isDigit(title.charAt(end))) {
                digits.append(title.charAt(end));
                end++;
            }
            if (digits.length() > 0 && end < title.length()
                    && (title.charAt(end) == '季' || title.charAt(end) == '部')) {
                try {
                    return Integer.parseInt(digits.toString());
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
            int packed = chineseNumber(title, i + 1);
            if (packed > 0) {
                int packedEnd = packed % 1000;
                if (packedEnd + 1 < title.length()) {
                    char unit = title.charAt(packedEnd + 1);
                    if (unit == '季' || unit == '部') return packed / 1000;
                }
            }
        }
        return 0;
    }

    /**
     * Parses a leading Chinese numeral (1-99) and returns {@code <number> * 1000 + lastIndex} so
     * callers can also find where the numeral ended; returns 0 when there is no numeral.
     */
    private static int chineseNumber(String title, int start) {
        int index = start;
        int value = 0;
        boolean found = false;
        while (index < title.length() && value < 100) {
            int digit = chineseDigit(title.charAt(index));
            if (digit < 1 || digit > 9) break;
            value = value * 10 + digit;
            index++;
            found = true;
        }
        if (index < title.length() && title.charAt(index) == '十') {
            int tens = value == 0 ? 1 : value;
            value = tens * 10;
            index++;
            found = true;
            if (index < title.length()) {
                int digit = chineseDigit(title.charAt(index));
                if (digit >= 0 && digit <= 9) {
                    value += digit;
                    index++;
                }
            }
        }
        if (!found || value <= 0) return 0;
        return value * 1000 + (index - 1);
    }

    private static int chineseDigit(char character) {
        switch (character) {
            case '零': return 0;
            case '一': return 1;
            case '二': case '两': return 2;
            case '三': return 3;
            case '四': return 4;
            case '五': return 5;
            case '六': return 6;
            case '七': return 7;
            case '八': return 8;
            case '九': return 9;
            case '十': return 10;
            default: return -1;
        }
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
