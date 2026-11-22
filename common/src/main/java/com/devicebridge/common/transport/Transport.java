package com.devicebridge.common.transport;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;

/**
 * Egy kétirányú bájtfolyam a két eszköz között. A konkrét megvalósítás lehet TCP socket
 * (LAN/PAN/internet/USB-ADB), Bluetooth RFCOMM socket vagy Bluetooth soros (COM) port.
 */
public interface Transport extends Closeable {
    InputStream input() throws IOException;

    OutputStream output() throws IOException;

    String description();

    TransportKind kind();

    /** A távoli fél IP-címe, ha hálózati kapcsolat; egyébként null. */
    default InetAddress remoteAddress() {
        return null;
    }

    /** Olvasási időkorlát (ms), 0 = végtelen. Nem minden átvitel támogatja. */
    default void setReadTimeout(int millis) {
    }
}
