package com.devicebridge.common;

/** Protokoll-szintű állandók, amelyeket mindkét oldal (PC és Android) használ. */
public final class Protocol {
    private Protocol() {}

    /** "DBRG" ASCII-ban. */
    public static final int MAGIC = 0x44425247;
    public static final int VERSION = 1;

    /** A DeviceBridge TCP szerver portja (LAN / PAN / internet). */
    public static final int TCP_PORT = 47801;
    /** UDP port a helyi hálózati eszközkereséshez. */
    public static final int DISCOVERY_PORT = 47800;
    /** A beépített SSH szerver portja (csak helyi hálózatról érhető el). */
    public static final int SSH_PORT = 47822;

    /** USB (ADB forward): PC localhost:47811 -> telefon:47801. */
    public static final int USB_BRIDGE_FORWARD_PORT = 47811;
    /** USB (ADB forward): PC localhost:47823 -> telefon SSH:47822. */
    public static final int USB_SSH_FORWARD_PORT = 47823;
    /** USB (ADB reverse): telefon localhost:47824 -> PC SSH:47822. */
    public static final int USB_SSH_REVERSE_PORT = 47824;

    /** Szabványos Bluetooth SPP (soros port) UUID – Windows erre hoz létre COM portot. */
    public static final String SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB";

    public static final int MAX_RECORD_SIZE = 8 * 1024 * 1024;

    public static final String DEVICE_TYPE_ANDROID = "android";
    public static final String DEVICE_TYPE_DESKTOP = "desktop";
}
