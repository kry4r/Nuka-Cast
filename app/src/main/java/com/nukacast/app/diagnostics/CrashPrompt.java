package com.nukacast.app.diagnostics;

import java.security.MessageDigest;

/**
 * Decides whether the stored crash report should pop up again.
 *
 * <p>The report used to be shown on every start until the viewer pressed 清除记录, which is a modal
 * dialog in the middle of the screen — it stole the focus and blocked playback controls. It is now
 * shown once per distinct crash; the text stays on disk for the diagnostics export.
 */
public final class CrashPrompt {

    private CrashPrompt() {}

    /** A short, stable signature of a report; empty for an empty report. */
    public static String signature(String report) {
        if (report == null || report.trim().isEmpty()) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(report.getBytes("UTF-8"));
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 8 && i < bytes.length; i++) {
                text.append(String.format("%02x", bytes[i]));
            }
            return text.toString();
        } catch (Exception error) {
            // No digest available: fall back to the report length, which still separates most crashes.
            return "len" + report.length();
        }
    }

    /** True when this report has never been shown to the viewer. */
    public static boolean shouldPrompt(String report, String lastShownSignature) {
        String signature = signature(report);
        if (signature.isEmpty()) return false;
        return !signature.equals(lastShownSignature == null ? "" : lastShownSignature);
    }
}
