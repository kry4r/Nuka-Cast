package com.nukacast.app.security;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.nukacast.app.util.Digests;

public final class PairingManager {
    private static final String PREFS = "web_pairing";
    private static final String TOKEN_PREFIX = "token_";
    private static final long TOKEN_LIFETIME_MS = 12L * 60L * 60L * 1000L;
    private static final long ATTEMPT_WINDOW_MS = 5L * 60L * 1000L;
    private static final long LOCKOUT_MS = 10L * 60L * 1000L;
    private static final int MAX_FAILURES = 5;
    private static final int MAX_TOKENS = 8;

    private final SharedPreferences preferences;
    private final String pairingCode;
    private final Map<String, Attempts> attempts = new HashMap<String, Attempts>();

    public PairingManager(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        pairingCode = String.format(Locale.US, "%06d", SecureEntropy.nextInt(1000000));
    }

    public String getPairingCode() {
        return pairingCode;
    }

    public synchronized String pair(String submittedCode, String clientAddress) {
        String client = normalizeClient(clientAddress);
        long now = System.currentTimeMillis();
        Attempts current = attempts.get(client);
        if (current != null && current.lockedUntil > now) {
            throw new SecurityException("配对失败次数过多，请稍后重试");
        }
        if (!PairingSecret.matches(pairingCode, submittedCode)) {
            recordFailure(client, now);
            return null;
        }
        attempts.remove(client);
        prune(now);
        byte[] secret = new byte[32];
        SecureEntropy.nextBytes(secret);
        String token = hex(secret);
        String key = TOKEN_PREFIX + Digests.sha256(token.getBytes());
        preferences.edit().putString(key, (now + TOKEN_LIFETIME_MS) + "\n" + client).apply();
        return token;
    }

    public synchronized boolean isAuthorized(String token, String clientAddress) {
        if (token == null || token.length() != 64) return false;
        String key = TOKEN_PREFIX + Digests.sha256(token.getBytes());
        String record = preferences.getString(key, null);
        if (record == null) return false;
        int separator = record.indexOf('\n');
        if (separator <= 0) {
            preferences.edit().remove(key).apply();
            return false;
        }
        try {
            long expiresAt = Long.parseLong(record.substring(0, separator));
            String expectedClient = record.substring(separator + 1);
            if (expiresAt <= System.currentTimeMillis()
                    || !expectedClient.equals(normalizeClient(clientAddress))) {
                if (expiresAt <= System.currentTimeMillis()) preferences.edit().remove(key).apply();
                return false;
            }
            return true;
        } catch (NumberFormatException invalid) {
            preferences.edit().remove(key).apply();
            return false;
        }
    }

    public void revokeAll() {
        preferences.edit().clear().apply();
    }

    private void recordFailure(String client, long now) {
        Attempts value = attempts.get(client);
        if (value == null || now - value.windowStartedAt > ATTEMPT_WINDOW_MS) {
            value = new Attempts(now);
            attempts.put(client, value);
        }
        value.failures++;
        if (value.failures >= MAX_FAILURES) value.lockedUntil = now + LOCKOUT_MS;
    }

    private void prune(long now) {
        SharedPreferences.Editor editor = preferences.edit();
        int active = 0;
        String earliestKey = null;
        long earliestExpiry = Long.MAX_VALUE;
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (!entry.getKey().startsWith(TOKEN_PREFIX) || !(entry.getValue() instanceof String)) continue;
            String value = (String) entry.getValue();
            int separator = value.indexOf('\n');
            long expiry = 0;
            try { expiry = Long.parseLong(separator < 0 ? "0" : value.substring(0, separator)); }
            catch (NumberFormatException ignored) {}
            if (expiry <= now) editor.remove(entry.getKey());
            else {
                active++;
                if (expiry < earliestExpiry) {
                    earliestExpiry = expiry;
                    earliestKey = entry.getKey();
                }
            }
        }
        if (active >= MAX_TOKENS && earliestKey != null) editor.remove(earliestKey);
        editor.apply();
    }

    private static String normalizeClient(String value) {
        return value == null || value.trim().isEmpty() ? "unknown" : value.trim();
    }

    private static String hex(byte[] value) {
        StringBuilder output = new StringBuilder(value.length * 2);
        for (byte current : value) output.append(String.format(java.util.Locale.US, "%02x", current & 0xff));
        return output.toString();
    }

    private static final class Attempts {
        final long windowStartedAt;
        int failures;
        long lockedUntil;

        Attempts(long windowStartedAt) { this.windowStartedAt = windowStartedAt; }
    }
}
