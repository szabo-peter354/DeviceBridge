package com.devicebridge.common;

/** A helyi eszköz megjelenített adatai. */
public final class LocalInfo {
    public final String name;
    public final String deviceType;

    public LocalInfo(String name, String deviceType) {
        this.name = name;
        this.deviceType = deviceType;
    }
}
