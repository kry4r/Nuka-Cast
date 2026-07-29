package com.github.catvod;

import com.nukacast.app.core.NetworkAddress;
import com.nukacast.app.core.NukaRuntime;

import android.content.Context;

public class Proxy {
    private static int port = NukaRuntime.CONTROL_PORT;

    public Proxy() {}

    public static void set(int value) {
        port = value;
    }

    public static int getPort() {
        return port > 0 ? port : NukaRuntime.CONTROL_PORT;
    }

    public static String getUrl(boolean local) {
        Context context = SpiderContext.get();
        String host = local || context == null
                ? "127.0.0.1" : NetworkAddress.findLanAddress(context);
        if ("0.0.0.0".equals(host)) host = "127.0.0.1";
        return "http://" + host + ":" + getPort() + "/proxy";
    }
}
