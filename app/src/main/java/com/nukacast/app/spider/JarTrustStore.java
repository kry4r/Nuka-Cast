package com.nukacast.app.spider;

import android.content.Context;
import android.content.SharedPreferences;

import com.nukacast.app.util.Digests;

public final class JarTrustStore {
    public enum Verdict { TRUSTED, FIRST_USE_TRUSTED, CHANGED }

    private final SharedPreferences preferences;

    public JarTrustStore(Context context) {
        preferences = context.getSharedPreferences("spider_jar_trust", Context.MODE_PRIVATE);
    }

    public synchronized Verdict verify(String url, String sha256) {
        String key = Digests.sha256(url.getBytes());
        String trustedHash = preferences.getString(key, null);
        if (trustedHash == null) {
            preferences.edit().putString(key, sha256).commit();
            return Verdict.FIRST_USE_TRUSTED;
        }
        return trustedHash.equalsIgnoreCase(sha256) ? Verdict.TRUSTED : Verdict.CHANGED;
    }

    public synchronized void approve(String url, String sha256) {
        String key = Digests.sha256(url.getBytes());
        preferences.edit().putString(key, sha256).commit();
    }
}
