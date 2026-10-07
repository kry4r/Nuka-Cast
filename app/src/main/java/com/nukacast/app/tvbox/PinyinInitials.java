package com.nukacast.app.tvbox;

import java.util.Locale;

/**
 * Pinyin initials of Chinese titles, computed from the GB2312 code range.
 *
 * <p>MacCMS search matches {@code vod_name LIKE %wd%}, so a query of initials finds nothing: the
 * characters in the database are Chinese. Plugin sites sometimes implement initials themselves
 * ({@code "py":1} in the config), but the plain CMS sites behind most sources do not, which is why
 * searching "LLDQ" returned nothing while "流浪地球" worked.
 *
 * <p>The level-one GB2312 block is ordered by pinyin, so a title's initials can be derived without
 * shipping a dictionary: map the bytes to the classic boundary table and read off the letter.
 * Only ~3755 common characters are covered, which is exactly the set used in film titles; anything
 * else (rare hanzi, Latin, digits) is passed through or skipped.
 */
public final class PinyinInitials {
    /** GB2312 level-one block starts here (0xB0A1). */
    private static final int FIRST_CODE = 1601;
    /** Pinyin group boundaries, one per letter in {@link #LETTERS}. */
    private static final int[] GROUP_START = {
            1601, 1637, 1833, 2078, 2274, 2302, 2433, 2594, 2787, 3106, 3212, 3472,
            3635, 3722, 3730, 3858, 4027, 4086, 4390, 4558, 4684, 4925, 5249, 5600,
    };
    /** Level-one block ends here; beyond it the codes are not pinyin-ordered. */
    private static final int LAST_CODE = 5590;
    private static final char[] LETTERS = {
            'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'j', 'k', 'l', 'm', 'n', 'o', 'p',
            'q', 'r', 's', 't', 'w', 'x', 'y', 'z',
    };

    private PinyinInitials() {}

    /** Initials of {@code title}: hanzi are converted, ASCII letters kept, everything else dropped. */
    public static String of(String title) {
        if (title == null || title.isEmpty()) return "";
        StringBuilder initials = new StringBuilder();
        for (int i = 0; i < title.length(); i++) {
            char character = title.charAt(i);
            if (isAsciiLetter(character)) {
                initials.append(Character.toLowerCase(character));
                continue;
            }
            char initial = initialOf(character);
            if (initial != 0) initials.append(initial);
        }
        return initials.toString();
    }

    /**
     * True when a query is initials rather than a title: two or more ASCII letters with no spaces,
     * digits or hanzi. Such a query is a candidate for expansion against indexed titles.
     */
    public static boolean isInitialQuery(String keyword) {
        if (keyword == null) return false;
        String value = keyword.trim();
        if (value.length() < 2 || value.length() > 12) return false;
        for (int i = 0; i < value.length(); i++) {
            if (!isAsciiLetter(value.charAt(i))) return false;
        }
        return true;
    }

    /**
     * True when the indexed title's initials start with the query — "LLDQ" matches 流浪地球 and
     * 流浪地球2, and also 刘姥姥的趣事 ("lldq"), which is how a user expects initials to behave.
     */
    public static boolean matches(String title, String initialsQuery) {
        if (title == null || initialsQuery == null) return false;
        String initials = of(title);
        if (initials.isEmpty()) return false;
        return initials.startsWith(initialsQuery.toLowerCase(Locale.ROOT));
    }

    /** Initial letter of one hanzi, or 0 when it is outside the pinyin-ordered block. */
    static char initialOf(char character) {
        // GB2312 bytes are reconstructed from the Unicode value via the classic encoding table
        // shortcut: the level-one block maps linearly, so an offset lookup is enough.
        int code = gb2312Code(character);
        if (code < FIRST_CODE || code > LAST_CODE) return 0;
        for (int i = GROUP_START.length - 1; i >= 0; i--) {
            if (code >= GROUP_START[i]) return LETTERS[i];
        }
        return 0;
    }

    /**
     * Position of a hanzi inside the GB2312 level-one block, expressed as
     * {@code (high-0xA0)*100 + (low-0xA0)} — the unit used by {@link #GROUP_START}.
     *
     * <p>Computing it needs the GB2312 encoding of the character. {@code String.getBytes("GB2312")}
     * would allocate per character, so a cached single-character array is used instead.
     */
    private static int gb2312Code(char character) {
        if (character < 0x4E00 || character > 0x9FFF) return 0;
        try {
            byte[] bytes = encode(character);
            if (bytes.length != 2) return 0;
            int high = bytes[0] & 0xFF;
            int low = bytes[1] & 0xFF;
            if (high < 0xA1 || low < 0xA1) return 0;
            return (high - 0xA0) * 100 + (low - 0xA0);
        } catch (Exception error) {
            return 0;
        }
    }

    private static byte[] encode(char character) throws java.io.UnsupportedEncodingException {
        return new String(new char[]{character}).getBytes("GB2312");
    }

    private static boolean isAsciiLetter(char character) {
        return (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z');
    }
}
