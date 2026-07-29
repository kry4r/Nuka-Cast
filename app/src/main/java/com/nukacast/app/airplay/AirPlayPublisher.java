package com.nukacast.app.airplay;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;

import com.nukacast.app.core.NetworkAddress;

import java.net.InetAddress;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

final class AirPlayPublisher {
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String IDENTITY_PREFS = "airplay_identity";
    private static final String DEVICE_ID = "device_id";
    private final Context context;
    private JmDNS jmdns;
    private WifiManager.MulticastLock multicastLock;
    private String publishedAddress = "";

    AirPlayPublisher(Context context) {
        this.context = context.getApplicationContext();
    }

    synchronized void start(int port, String publicKey) throws Exception {
        String addressText = NetworkAddress.findLanAddress(context);
        if (!isUsableAddress(addressText)) {
            throw new IllegalStateException("局域网地址尚未就绪");
        }
        if (jmdns != null && addressText.equals(publishedAddress)) return;
        if (jmdns != null) stop();
        WifiManager wifi = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        try {
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("nukacast-airplay-mdns");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
            InetAddress address = InetAddress.getByName(addressText);
            String mac = deviceId();
            String compactMac = mac.replace(":", "").toUpperCase(Locale.ROOT);
            String pairId = UUID.nameUUIDFromBytes(("NukaCast|" + mac).getBytes(UTF_8)).toString();
            jmdns = JmDNS.create(address, "NukaCast");

            Map<String, String> airplay = new LinkedHashMap<String, String>();
            airplay.put("deviceid", mac);
            airplay.put("features", "0x5A7FFFF7,0x1E");
            airplay.put("srcvers", "220.68");
            airplay.put("flags", "0x4");
            airplay.put("vv", "2");
            airplay.put("model", "AppleTV3,2");
            airplay.put("pw", "false");
            airplay.put("pk", publicKey);
            airplay.put("pi", pairId);

            Map<String, String> raop = new LinkedHashMap<String, String>();
            raop.put("ch", "2");
            raop.put("cn", "0,1,2,3");
            raop.put("da", "true");
            raop.put("et", "0,3,5");
            raop.put("vv", "2");
            raop.put("ft", "0x5A7FFFF7,0x1E");
            raop.put("am", "AppleTV3,2");
            raop.put("md", "0,1,2");
            raop.put("pw", "false");
            raop.put("sr", "44100");
            raop.put("ss", "16");
            raop.put("sv", "false");
            raop.put("tp", "UDP");
            raop.put("txtvers", "1");
            raop.put("sf", "0x4");
            raop.put("vs", "220.68");
            raop.put("vn", "65537");
            raop.put("pk", publicKey);

            jmdns.registerService(ServiceInfo.create(
                    "_airplay._tcp.local.", "NukaCast", port, 0, 0, airplay));
            jmdns.registerService(ServiceInfo.create(
                    "_raop._tcp.local.", compactMac + "@NukaCast", port, 0, 0, raop));
            publishedAddress = addressText;
        } catch (Exception failure) {
            stop();
            throw failure;
        }
    }

    synchronized boolean needsPublish() {
        String address = NetworkAddress.findLanAddress(context);
        return jmdns == null || !address.equals(publishedAddress);
    }

    synchronized void stop() {
        if (jmdns != null) {
            try { jmdns.unregisterAllServices(); } catch (RuntimeException ignored) {}
            try { jmdns.close(); } catch (Exception ignored) {}
            jmdns = null;
        }
        publishedAddress = "";
        if (multicastLock != null) {
            try { if (multicastLock.isHeld()) multicastLock.release(); } catch (RuntimeException ignored) {}
            multicastLock = null;
        }
    }

    static boolean isUsableAddress(String address) {
        return address != null && !address.isEmpty() && !"0.0.0.0".equals(address);
    }

    private String deviceId() {
        SharedPreferences preferences = context.getSharedPreferences(
                IDENTITY_PREFS, Context.MODE_PRIVATE);
        String saved = preferences.getString(DEVICE_ID, "");
        if (saved.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) {
            return saved.toUpperCase(Locale.ROOT);
        }
        UUID value = UUID.randomUUID();
        long bits = value.getLeastSignificantBits();
        String generated = String.format(Locale.US, "02:%02X:%02X:%02X:%02X:%02X",
                (bits >>> 32) & 0xff, (bits >>> 24) & 0xff, (bits >>> 16) & 0xff,
                (bits >>> 8) & 0xff, bits & 0xff);
        preferences.edit().putString(DEVICE_ID, generated).apply();
        return generated;
    }
}
