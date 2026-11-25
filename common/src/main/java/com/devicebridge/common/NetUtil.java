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

    private static boolean isVirtual(NetworkInterface ni) {
        String n = (ni.getDisplayName() + " " + ni.getName()).toLowerCase();
        return ni.isVirtual() || n.contains("virtualbox") || n.contains("vmware") || n.contains("hyper-v")
                || n.contains("vethernet") || n.contains("wsl") || n.contains("docker") || n.contains("tap-");
    }

    /** Az eszköz saját, nem-loopback IPv4 címei (valódi hálózat elöl, virtuális és link-local a végén). */
    public static List<InetAddress> localIPv4Addresses() {
        List<InetAddress> real = new ArrayList<>();
        List<InetAddress> other = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                boolean virtual = isVirtual(ni);
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    (virtual || a.isLinkLocalAddress() ? other : real).add(a);
                }
            }
        } catch (Exception ignored) {
        }
        real.addAll(other);
        return real;
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

    /**
     * A helyi IPv4 alhálózatok összes lehetséges gépcíme (saját cím nélkül), legfeljebb {@code max} darab.
     * Csak /22-es vagy kisebb hálózatokat jár be. Ott használjuk, ahol a broadcastot szűrik
     * (pl. egyes Xiaomi telefonok, routerek sávok között).
     */
    public static List<InetAddress> subnetHosts(int max) {
        List<InetAddress> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    int prefix = ia.getNetworkPrefixLength();
                    if (!(a instanceof Inet4Address) || prefix < 22 || prefix > 30 || a.isLinkLocalAddress()) continue;
                    byte[] b = a.getAddress();
                    int ip = ((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16) | ((b[2] & 0xff) << 8) | (b[3] & 0xff);
                    int mask = prefix == 0 ? 0 : -1 << (32 - prefix);
                    int net = ip & mask;
                    int count = ~mask;
                    for (int i = 1; i < count && out.size() < max; i++) {
                        int h = net | i;
                        if (h == ip) continue;
                        out.add(InetAddress.getByAddress(new byte[]{(byte) (h >>> 24), (byte) (h >>> 16), (byte) (h >>> 8), (byte) h}));
                    }
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
