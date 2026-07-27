package com.nukacast.app.core;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

public final class NetworkAddress {
    private NetworkAddress() {}

    public static String findLanAddress(Context context) {
        String wifiAddress = fromWifi(context);
        if (wifiAddress != null) {
            return wifiAddress;
        }
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // The UI will keep showing the wildcard address until networking is ready.
        }
        return "0.0.0.0";
    }

    private static String fromWifi(Context context) {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) {
            return null;
        }
        WifiInfo info = wifiManager.getConnectionInfo();
        int ip = info == null ? 0 : info.getIpAddress();
        if (ip == 0) {
            return null;
        }
        return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "."
                + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
    }
}
