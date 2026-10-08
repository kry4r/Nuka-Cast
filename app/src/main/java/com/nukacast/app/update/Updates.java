package com.nukacast.app.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tells whether a published release is newer than the running build.
 *
 * <p>A sideloaded television app has no store to update it: without this, "is there a newer version?"
 * can only be answered by whoever built it. The check is a plain read of the project's releases feed and
 * never installs anything by itself.
 */
public final class Updates {

    /** Where releases are published; also shown to the user so they can look it up by hand. */
    public static final String REPO = "kry4r/Nuka-Cast";
    public static final String RELEASES_API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    public static final String RELEASES_PAGE = "https://github.com/" + REPO + "/releases/latest";

    private Updates() {
    }

    /**
     * Compares two version names the way a person reads them: {@code 0.10.0} is newer than {@code 0.9.9}.
     *
     * @return a positive number when {@code right} is newer, negative when it is older, 0 when equal
     */
    public static int compare(String left, String right) {
        int[] a = numbers(left);
        int[] b = numbers(right);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int one = i < a.length ? a[i] : 0;
            int other = i < b.length ? b[i] : 0;
            if (one != other) return one - other;
        }
        return 0;
    }

    /** The numeric parts of a version, ignoring a leading "v" and any suffix like "-debug". */
    static int[] numbers(String version) {
        if (version == null) return new int[0];
        String text = version.trim();
        if (text.startsWith("v") || text.startsWith("V")) text = text.substring(1);
        int end = 0;
        while (end < text.length() && (Character.isDigit(text.charAt(end)) || text.charAt(end) == '.')) {
            end++;
        }
        String numeric = text.substring(0, end);
        if (numeric.isEmpty()) return new int[0];
        String[] parts = numeric.split("\\.");
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                values[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException notANumber) {
                values[i] = 0;
            }
        }
        return values;
    }

    /** What a release feed says about the newest build. */
    public static final class Release {
        public String tag = "";
        public String version = "";
        public String pageUrl = RELEASES_PAGE;
        public String apkUrl = "";
        public String notes = "";
    }

    /**
     * Reads the newest release out of the API response.
     *
     * <p>Kept apart from the network call so the parsing can be tested against what the feed actually
     * returns, including the shapes that matter (a release without assets, a tag with a "v" prefix).
     * Gson rather than {@code org.json}: the app already ships Gson, and {@code org.json} is a stub off
     * the device, which would leave this untested.
     *
     * @throws IllegalArgumentException when the payload is not a release object
     */
    public static Release parse(String json) {
        JsonElement parsed = JsonParser.parseString(json == null ? "" : json);
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("不是发布信息：" + json);
        JsonObject object = parsed.getAsJsonObject();
        Release release = new Release();
        release.tag = text(object, "tag_name").trim();
        release.version = release.tag.startsWith("v") || release.tag.startsWith("V")
                ? release.tag.substring(1) : release.tag;
        String page = text(object, "html_url");
        if (!page.isEmpty()) release.pageUrl = page;
        release.notes = text(object, "body");
        JsonElement assets = object.get("assets");
        if (assets != null && assets.isJsonArray()) {
            JsonArray array = assets.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                JsonElement element = array.get(i);
                if (element == null || !element.isJsonObject()) continue;
                JsonObject asset = element.getAsJsonObject();
                String name = text(asset, "name");
                String url = text(asset, "browser_download_url");
                if (name.toLowerCase(java.util.Locale.US).endsWith(".apk") && !url.isEmpty()) {
                    release.apkUrl = url;
                    break;
                }
            }
        }
        return release;
    }

    /** A string field, or "" when it is missing or null. */
    private static String text(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    /** The result of a check: either a release to compare against, or the reason there is none. */
    public static final class Result {
        public boolean updateAvailable;
        public String currentVersion = "";
        public String latestVersion = "";
        public String pageUrl = RELEASES_PAGE;
        public String apkUrl = "";
        public String notes = "";
        /** Non-empty when the check could not be done (no network, no releases). */
        public String error = "";
        public long checkedAt;
        /**
         * One line for the settings page and the console.
         *
         * <p>A field rather than a method: the console reads this JSON, and a method is not serialised —
         * it showed up as {@code undefined} there while the television was fine.
         */
        public String summary = "";

        /** Fills in the one-line description; called by everything that builds a result. */
        void describe() {
            if (!error.isEmpty()) {
                summary = "检查更新失败：" + error;
            } else if (latestVersion.isEmpty()) {
                summary = "还没有发布版本";
            } else if (updateAvailable) {
                summary = "发现新版本 " + latestVersion + "（当前 " + currentVersion + "）";
            } else {
                summary = "已是最新版本 " + currentVersion;
            }
        }

        /** A failed check, with the reason the user sees. */
        static Result failure(String currentVersion, String error) {
            Result result = new Result();
            result.currentVersion = currentVersion == null ? "" : currentVersion;
            result.error = error == null ? "" : error;
            result.checkedAt = System.currentTimeMillis();
            result.describe();
            return result;
        }
    }

    /** Compares the running version against the newest release. */
    public static Result evaluate(String currentVersion, Release release) {
        Result result = new Result();
        result.currentVersion = currentVersion == null ? "" : currentVersion;
        result.checkedAt = System.currentTimeMillis();
        if (release == null || release.version.isEmpty()) {
            result.error = "没有可用的发布信息";
            result.describe();
            return result;
        }
        result.latestVersion = release.version;
        result.pageUrl = release.pageUrl;
        result.apkUrl = release.apkUrl;
        result.notes = release.notes;
        // A debug build carries a "-debug" suffix: it must not look like a newer version.
        result.updateAvailable = compare(result.currentVersion, release.version) < 0;
        result.describe();
        return result;
    }
}
