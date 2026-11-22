package com.devicebridge.common;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class NetUtil {
    private NetUtil() {}

    /**
     * Igaz, ha a cím helyi hálózati (LAN, Bluetooth PAN, USB tethering, link-local vagy loopback).
     * Az SSH szerver csak ilyen címekről fogad kapcsolatot.
     */
    public static boolean isLocalAddress(InetAddress a) {
        if (a == null) return false;
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        if (a instanceof Inet6Address) {
            byte b0 = a.getAddress()[0];
            return (b0 & 0xFE) == 0xFC; // fc00::/7 egyedi helyi cím
        }
        return false;
    }

    public static boolean isLocalAddress(SocketAddress sa) {
        return sa instanceof InetSocketAddress && isLocalAddress(((InetSocketAddress) sa).getAddress());
    }

    /** Az eszköz saját, nem-loopback IPv4 címei. */
    public static List<InetAddress> localIPv4Addresses() {
        List<InetAddress> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) out.add(a);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** IPv4 broadcast címek minden aktív interfészen. */
    public static List<InetAddress> broadcastAddresses() {
        List<InetAddress> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b != null && !out.contains(b)) out.add(b);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** "host" vagy "host:port" felbontása. */
    public static String[] splitHostPort(String input, int defaultPort) {
        String s = input.trim();
        String host = s;
        int port = defaultPort;
        if (s.startsWith("[")) { // [IPv6]:port
            int end = s.indexOf(']');
            if (end > 0) {
                host = s.substring(1, end);
                if (end + 2 < s.length() && s.charAt(end + 1) == ':') port = Integer.parseInt(s.substring(end + 2));
            }
        } else {
            int idx = s.lastIndexOf(':');
            if (idx > 0 && s.indexOf(':') == idx) {
                host = s.substring(0, idx);
                port = Integer.parseInt(s.substring(idx + 1));
            }
        }
        return new String[]{host, String.valueOf(port)};
    }
}
