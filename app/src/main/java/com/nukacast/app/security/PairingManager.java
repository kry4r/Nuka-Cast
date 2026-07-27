package com.nukacast.app.security;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class PairingManager {
    private static final String PREFS = "web_pairing";
    private static final String TOKENS = "tokens";

    private final SharedPreferences preferences;
    private final SecureRandom random = new SecureRandom();
    private final String pairingCode;

    public PairingManager(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        pairingCode = PairingSecret.generateCode(random);
    }

    public String getPairingCode() {
        return pairingCode;
    }

    public synchronized String pair(String submittedCode) {
        if (!PairingSecret.matches(pairingCode, submittedCode)) {
            return null;
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        Set<String> tokens = new HashSet<String>(preferences.getStringSet(TOKENS, new HashSet<String>()));
        tokens.add(token);
        preferences.edit().putStringSet(TOKENS, tokens).commit();
        return token;
    }

    public boolean isAuthorized(String token) {
        return token != null && preferences.getStringSet(TOKENS, new HashSet<String>()).contains(token);
    }

    public void revokeAll() {
        preferences.edit().remove(TOKENS).commit();
    }
}
