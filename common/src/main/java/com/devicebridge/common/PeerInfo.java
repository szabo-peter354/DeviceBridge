package com.devicebridge.common;

import com.devicebridge.common.transport.TransportKind;

import java.net.InetAddress;

/** A kapcsolódott (vagy párosítandó) távoli eszköz adatai. */
public final class PeerInfo {
    public final String name;
    public final String deviceType;
    public final String fingerprint;
    public final String transportDescription;
    public final TransportKind kind;
    public final InetAddress address;

    public PeerInfo(String name, String deviceType, String fingerprint, String transportDescription,
                    TransportKind kind, InetAddress address) {
        this.name = name;
        this.deviceType = deviceType;
        this.fingerprint = fingerprint;
        this.transportDescription = transportDescription;
        this.kind = kind;
        this.address = address;
    }

    public boolean isAndroid() {
        return Protocol.DEVICE_TYPE_ANDROID.equals(deviceType);
    }

    @Override
    public String toString() {
        return name + " (" + transportDescription + ")";
    }
}
