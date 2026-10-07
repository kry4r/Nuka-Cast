package com.nukacast.app.dlna;

import com.nukacast.app.diagnostics.AppLog;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SSDP: makes the TV visible as a DLNA renderer, and answers searches.
 *
 * <p>Control points find renderers by multicasting an M-SEARCH to 239.255.255.250:1900 and waiting for
 * a unicast reply carrying the device description URL. The renderer also announces itself
 * periodically; without both, a phone's "cast" list stays empty.
 */
public final class DlnaSsdp {
    private static final String GROUP = "239.255.255.250";
    private static final int PORT = 1900;
    private static final int MAX_AGE_SECONDS = 1800;
    /** Alive announcements are repeated well inside the max-age window. */
    private static final long ANNOUNCE_INTERVAL_MS = 5 * 60 * 1000L;

    private final DlnaDescription.Device device;
    private final String location;
    private final String uuid;
    private Thread thread;
    private volatile boolean running;
    /** How many M-SEARCH requests were seen and answered, plus the last target. */
    private final java.util.concurrent.atomic.AtomicInteger searchesSeen =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger searchesAnswered =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile String lastSearchTarget = "";
    private volatile long lastSearchAt;

    public DlnaSsdp(DlnaDescription.Device device, String location, String uuid) {
        this.device = device;
        this.location = location;
        this.uuid = uuid;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(new Runnable() {
            @Override public void run() {
                loop();
            }
        }, "nukacast-dlna-ssdp");
        thread.setDaemon(true);
        thread.start();
        AppLog.i("投屏", "DLNA 已启动：" + device.friendlyName + " → " + location);
    }

    public synchronized void stop() {
        running = false;
        Thread current = thread;
        thread = null;
        if (current != null) current.interrupt();
        sendAll(byebyeMessages());
    }

    public boolean isRunning() {
        return running;
    }

    public int searchesSeen() {
        return searchesSeen.get();
    }

    public int searchesAnswered() {
        return searchesAnswered.get();
    }

    public String lastSearchTarget() {
        return lastSearchTarget;
    }

    public long lastSearchAt() {
        return lastSearchAt;
    }

    /**
     * Sends an M-SEARCH from this device and waits for a reply, which is how a control point finds the
     * renderer. Used by the diagnostics endpoint: without a second device on the LAN there is otherwise
     * no way to prove the discovery path works.
     *
     * @return the LOCATION from the first reply, or an empty string when nothing answered.
     */
    public static String probe(int timeoutMs) {
        java.net.DatagramSocket socket = null;
        try {
            socket = new java.net.DatagramSocket();
            socket.setSoTimeout(timeoutMs);
            socket.setBroadcast(true);
            String search = "M-SEARCH * HTTP/1.1\r\n"
                    + "HOST: " + GROUP + ":" + PORT + "\r\n"
                    + "MAN: \"ssdp:discover\"\r\n"
                    + "MX: 2\r\n"
                    + "ST: " + DlnaDescription.DEVICE_TYPE_RENDERER + "\r\n\r\n";
            byte[] bytes = search.getBytes("UTF-8");
            java.net.InetAddress group = java.net.InetAddress.getByName(GROUP);
            socket.send(new java.net.DatagramPacket(bytes, bytes.length, group, PORT));
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                byte[] buffer = new byte[2048];
                java.net.DatagramPacket reply = new java.net.DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(reply);
                } catch (java.net.SocketTimeoutException timeout) {
                    break;
                }
                String message = new String(reply.getData(), 0, reply.getLength(), "UTF-8");
                String location = headerValue(message, "LOCATION");
                if (!location.isEmpty()) return location;
            }
        } catch (Exception error) {
            AppLog.d("投屏", "SSDP 自检失败：" + error.getClass().getSimpleName());
        } finally {
            if (socket != null) socket.close();
        }
        return "";
    }

    /** The search targets this device answers to. */
    static List<String> searchTargets(String deviceType) {
        List<String> targets = new ArrayList<String>();
        targets.add("upnp:rootdevice");
        targets.add(deviceType);
        targets.add("urn:schemas-upnp-org:service:AVTransport:1");
        targets.add("urn:schemas-upnp-org:service:RenderingControl:1");
        targets.add("urn:schemas-upnp-org:service:ConnectionManager:1");
        return targets;
    }

    /** True when an M-SEARCH's ST asks for something this renderer provides. */
    static boolean matchesSearchTarget(String searchTarget, String deviceType) {
        if (searchTarget == null) return false;
        String st = searchTarget.trim();
        if (st.isEmpty()) return false;
        if ("ssdp:all".equals(st)) return true;
        return searchTargets(deviceType).contains(st);
    }

    private void loop() {
        MulticastSocket socket = null;
        try {
            socket = new MulticastSocket(PORT);
            socket.setReuseAddress(true);
            try {
                // Loopback stays enabled so the device's own probe (see probe()) is answered: without a
                // second device on the LAN there is otherwise no way to prove discovery works.
                socket.setLoopbackMode(false);
            } catch (Exception ignored) {
                // Not fatal: discovery from other devices still works.
            }
            InetAddress group = InetAddress.getByName(GROUP);
            bindToInterfaces(socket, group);
            sendAll(aliveMessages());
            long lastAnnounce = System.currentTimeMillis();
            byte[] buffer = new byte[2048];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.setSoTimeout(20000);
                try {
                    socket.receive(packet);
                } catch (java.net.SocketTimeoutException timeout) {
                    if (System.currentTimeMillis() - lastAnnounce > ANNOUNCE_INTERVAL_MS) {
                        sendAll(aliveMessages());
                        lastAnnounce = System.currentTimeMillis();
                    }
                    continue;
                }
                String message = new String(packet.getData(), 0, packet.getLength(), "UTF-8");
                if (!message.startsWith("M-SEARCH")) continue;
                String searchTarget = headerValue(message, "ST");
                searchesSeen.incrementAndGet();
                lastSearchTarget = searchTarget;
                lastSearchAt = System.currentTimeMillis();
                if (!matchesSearchTarget(searchTarget, DlnaDescription.DEVICE_TYPE_RENDERER)) continue;
                respondTo(socket, packet, searchTarget);
                searchesAnswered.incrementAndGet();
            }
        } catch (IOException error) {
            AppLog.w("投屏", "DLNA 服务结束：" + error.getClass().getSimpleName());
        } finally {
            if (socket != null) socket.close();
            running = false;
        }
    }

    /** Joins the multicast group on every suitable interface; Wi-Fi and Ethernet may both be up. */
    private void bindToInterfaces(MulticastSocket socket, InetAddress group) {
        int joined = 0;
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback() || !network.supportsMulticast()) continue;
                try {
                    socket.joinGroup(new java.net.InetSocketAddress(group, PORT), network);
                    joined++;
                } catch (IOException ignored) {
                    // Interfaces come and go (Wi-Fi dropping); one working interface is enough.
                }
            }
        } catch (Exception error) {
            AppLog.w("投屏", "DLNA 加入组播失败：" + error.getClass().getSimpleName());
        }
        if (joined == 0) {
            try {
                socket.joinGroup(group);
            } catch (IOException error) {
                AppLog.w("投屏", "DLNA 无法加入组播组");
            }
        }
    }

    private void respondTo(MulticastSocket socket, DatagramPacket request, String searchTarget) {
        List<String> targets = "ssdp:all".equals(searchTarget)
                ? new ArrayList<String>() {{
                    add("upnp:rootdevice");
                    add(DlnaDescription.DEVICE_TYPE_RENDERER);
                }}
                : Collections.singletonList(searchTarget);
        for (String target : targets) {
            String usn = "upnp:rootdevice".equals(target)
                    ? uuid + "::upnp:rootdevice"
                    : uuid + "::" + target;
            String response = DlnaDescription.searchResponse(device, location, target, MAX_AGE_SECONDS)
                    .replace(uuid + "::" + target, usn);
            try {
                byte[] bytes = response.getBytes("UTF-8");
                socket.send(new DatagramPacket(bytes, bytes.length,
                        request.getAddress(), request.getPort()));
            } catch (IOException error) {
                AppLog.d("投屏", "DLNA 回应失败：" + error.getClass().getSimpleName());
            }
        }
    }

    private List<String> aliveMessages() {
        List<String> messages = new ArrayList<String>();
        for (String target : searchTargets(DlnaDescription.DEVICE_TYPE_RENDERER)) {
            String usn = "upnp:rootdevice".equals(target)
                    ? uuid + "::upnp:rootdevice"
                    : uuid + "::" + target;
            messages.add(DlnaDescription.alive(target, usn, location, MAX_AGE_SECONDS));
        }
        return messages;
    }

    private List<String> byebyeMessages() {
        List<String> messages = new ArrayList<String>();
        for (String target : searchTargets(DlnaDescription.DEVICE_TYPE_RENDERER)) {
            String usn = "upnp:rootdevice".equals(target)
                    ? uuid + "::upnp:rootdevice"
                    : uuid + "::" + target;
            messages.add(DlnaDescription.byebye(target, usn));
        }
        return messages;
    }

    /** Sends NOTIFY datagrams to the multicast group. */
    private void sendAll(List<String> messages) {
        MulticastSocket socket = null;
        try {
            socket = new MulticastSocket();
            socket.setTimeToLive(4);
            InetAddress group = InetAddress.getByName(GROUP);
            for (String message : messages) {
                byte[] bytes = message.getBytes("UTF-8");
                socket.send(new DatagramPacket(bytes, bytes.length, group, PORT));
            }
        } catch (IOException error) {
            AppLog.d("投屏", "DLNA 通告失败：" + error.getClass().getSimpleName());
        } finally {
            if (socket != null) socket.close();
        }
    }

    static String headerValue(String message, String name) {
        if (message == null) return "";
        for (String line : message.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            if (line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return "";
    }
}
