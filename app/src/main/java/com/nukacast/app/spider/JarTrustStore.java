package com.nukacast.app.spider;

import android.content.Context;
import android.content.SharedPreferences;

import com.nukacast.app.util.Digests;

final class JarTrustStore {
    enum Verdict { TRUSTED, FIRST_USE_TRUSTED, CHANGED }

    private final SharedPreferences preferences;

    JarTrustStore(Context context) {
        preferences = context.getSharedPreferences("spider_jar_trust", Context.MODE_PRIVATE);
    }

    synchronized Verdict verify(String url, String sha256) {
        String key = Digests.sha256(url.getBytes());
        String trustedHash = preferences.getString(key, null);
        if (trustedHash == null) {
            preferences.edit().putString(key, sha256).commit();
            return Verdict.FIRST_USE_TRUSTED;
        }
        return trustedHash.equalsIgnoreCase(sha256) ? Verdict.TRUSTED : Verdict.CHANGED;
    }

    synchronized void forget(String url) {
        preferences.edit().remove(Digests.sha256(url.getBytes())).commit();
    }
}
