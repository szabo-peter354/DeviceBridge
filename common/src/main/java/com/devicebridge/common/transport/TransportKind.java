package com.devicebridge.common.transport;

public enum TransportKind {
    NETWORK("Hálózat"),
    BLUETOOTH("Bluetooth"),
    USB("USB");

    private final String label;

    TransportKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
