package com.nukacast.app.airplay;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.Charset;
import java.util.Locale;
import java.util.UUID;

/**
 * Single identity/capability snapshot shared by mDNS advertising and the native {@code /info}
 * response. Keeping both sides on one snapshot prevents the receiver from answering with
 * hard-coded AppleTV values while advertising itself as NukaCast.
 *
 * <p>The device id is a locally generated unicast MAC kept in preferences: stable across restarts,
 * unique per install, and never the hard-coded value that used to be baked into the native plist.
 */
final class AirPlayIdentity {
    static final String NAME = "NukaCast";
    static final String MODEL = "AppleTV3,2";
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String PREFS = "airplay_identity";
    private static final String DEVICE_ID = "device_id";
    private static final String MAC_PATTERN = "(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}";

    final String deviceId;
    final String compactMac;
    final String name;
    final String model;
    final String pairId;

    private AirPlayIdentity(String deviceId, String name, String model) {
        this.deviceId = (deviceId == null ? "" : deviceId).toUpperCase(Locale.ROOT);
        this.compactMac = this.deviceId.replace(":", "");
        this.name = name;
        this.model = model;
        this.pairId = UUID.nameUUIDFromBytes(
                (name + "|" + deviceId).getBytes(UTF_8)).toString();
    }

    static AirPlayIdentity load(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);
        String saved = preferences.getString(DEVICE_ID, "");
        if (saved == null || !saved.matches(MAC_PATTERN)) {
            saved = generateDeviceId();
            preferences.edit().putString(DEVICE_ID, saved).apply();
        }
        return new AirPlayIdentity(saved, NAME, MODEL);
    }

    static AirPlayIdentity of(String deviceId, String name, String model) {
        return new AirPlayIdentity(deviceId, name, model);
    }

    static String generateDeviceId() {
        UUID value = UUID.randomUUID();
        long bits = value.getLeastSignificantBits();
        return String.format(Locale.US, "02:%02X:%02X:%02X:%02X:%02X",
                (bits >>> 32) & 0xff, (bits >>> 24) & 0xff, (bits >>> 16) & 0xff,
                (bits >>> 8) & 0xff, bits & 0xff);
    }

    boolean isUsable() {
        return deviceId != null && deviceId.matches(MAC_PATTERN) && !name.isEmpty();
    }
}
