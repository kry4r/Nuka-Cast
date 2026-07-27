package com.nukacast.app.security;

import java.security.SecureRandom;
import java.util.Locale;

final class PairingSecret {
    private PairingSecret() {}

    static String generateCode(SecureRandom random) {
        return String.format(Locale.US, "%06d", random.nextInt(1000000));
    }

    static boolean matches(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        int mismatch = expected.length() ^ actual.length();
        int length = Math.min(expected.length(), actual.length());
        for (int i = 0; i < length; i++) {
            mismatch |= expected.charAt(i) ^ actual.charAt(i);
        }
        return mismatch == 0;
    }
}
