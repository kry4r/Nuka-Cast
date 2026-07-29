package com.github.catvod.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.nukacast.app.net.HttpStack;

import okhttp3.Dns;

public class OkDns implements Dns {
    private final ConcurrentHashMap<String, String> hosts = new ConcurrentHashMap<String, String>();

    public void addAll(List<String> values) {
        if (values == null) return;
        for (String value : values) {
            if (value == null) continue;
            String[] parts = value.split("=", 2);
            if (parts.length == 2) hosts.put(parts[0].trim(), parts[1].trim());
        }
    }

    public void clear() {
        hosts.clear();
    }

    @Override public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        String target = hosts.get(hostname);
        if (target == null) {
            for (Map.Entry<String, String> entry : hosts.entrySet()) {
                if (hostname.contains(entry.getKey())) {
                    target = entry.getValue();
                    break;
                }
            }
        }
        return HttpStack.dns().lookup(target == null ? hostname : target);
    }
}
