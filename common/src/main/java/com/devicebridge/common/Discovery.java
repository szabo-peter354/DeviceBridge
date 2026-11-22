package com.devicebridge.common;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Eszközkeresés a helyi hálózaton (LAN / Wi-Fi / Bluetooth PAN / USB tethering) UDP broadcasttal. */
public final class Discovery {

    public static final class Device {
        public final String name;
        public final String type;
        public final String fingerprint;
        public final InetAddress address;
        public final int port;

        Device(String name, String type, String fingerprint, InetAddress address, int port) {
            this.name = name;
            this.type = type;
            this.fingerprint = fingerprint;
            this.address = address;
            this.port = port;
        }

        @Override
        public String toString() {
            return name + "  (" + address.getHostAddress() + ")";
        }
    }

    private static final String QUERY = "DBRG?1";
    private static final String REPLY = "DBRG!1";

    private final Supplier<LocalInfo> local;
    private final String fingerprint;
    private final int tcpPort;
    private DatagramSocket socket;

    public Discovery(Supplier<LocalInfo> local, String fingerprint, int tcpPort) {
        this.local = local;
        this.fingerprint = fingerprint;
        this.tcpPort = tcpPort;
    }

    /** Válaszol más eszközök keresésére. */
    public synchronized void startResponder() throws IOException {
        if (socket != null) return;
        DatagramSocket s = new DatagramSocket(null);
        s.setReuseAddress(true);
        s.setBroadcast(true);
        s.bind(new InetSocketAddress(Protocol.DISCOVERY_PORT));
        socket = s;
        Thread t = new Thread(() -> {
            byte[] buf = new byte[512];
            while (!s.isClosed()) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    if (!msg.startsWith(QUERY)) continue;
                    String[] parts = msg.split("\\|");
                    if (parts.length > 1 && parts[1].equals(fingerprint)) continue; // saját magunk
                    LocalInfo li = local.get();
                    String reply = REPLY + "|" + clean(li.name) + "|" + li.deviceType + "|" + tcpPort + "|" + fingerprint;
                    byte[] out = reply.getBytes(StandardCharsets.UTF_8);
                    s.send(new DatagramPacket(out, out.length, p.getAddress(), p.getPort()));
                } catch (IOException e) {
                    if (s.isClosed()) break;
                }
            }
        }, "bridge-discovery");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void stopResponder() {
        if (socket != null) socket.close();
        socket = null;
    }

    private static String clean(String s) {
        return s.replace('|', '/');
    }

    /** Eszközök keresése; blokkol {@code timeoutMs} ideig. */
    public static List<Device> scan(int timeoutMs, String ownFingerprint) {
        Map<String, Device> found = new LinkedHashMap<>();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(200);
            byte[] q = (QUERY + "|" + ownFingerprint).getBytes(StandardCharsets.UTF_8);
            Set<InetAddress> targets = new LinkedHashSet<>(NetUtil.broadcastAddresses());
            targets.add(InetAddress.getByName("255.255.255.255"));
            long end = System.currentTimeMillis() + timeoutMs;
            long nextSend = 0;
            int sends = 0;
            byte[] buf = new byte[512];
            while (System.currentTimeMillis() < end) {
                if (sends < 3 && System.currentTimeMillis() >= nextSend) {
                    for (InetAddress target : targets) {
                        try {
                            s.send(new DatagramPacket(q, q.length, target, Protocol.DISCOVERY_PORT));
                        } catch (IOException ignored) {
                        }
                    }
                    sends++;
                    nextSend = System.currentTimeMillis() + 400;
                }
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    String[] parts = msg.split("\\|");
                    if (parts.length < 5 || !parts[0].equals(REPLY)) continue;
                    if (parts[4].equals(ownFingerprint)) continue;
                    int port;
                    try { port = Integer.parseInt(parts[3]); } catch (NumberFormatException e) { continue; }
                    found.put(parts[4], new Device(parts[1], parts[2], parts[4], p.getAddress(), port));
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return new ArrayList<>(found.values());
    }
}
