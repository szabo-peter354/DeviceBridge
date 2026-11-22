package com.devicebridge.common.feature;

import com.devicebridge.common.PayloadReader;
import com.devicebridge.common.PayloadWriter;

import java.io.IOException;

/** Állapotinformáció, amit az eszközök időnként elküldenek egymásnak. */
public final class DeviceStatus {
    public final String model;
    public final String os;
    /** Akkumulátor töltöttség (%), vagy -1 ha nincs akkumulátor / ismeretlen. */
    public final int battery;
    public final boolean charging;

    public DeviceStatus(String model, String os, int battery, boolean charging) {
        this.model = model;
        this.os = os;
        this.battery = battery;
        this.charging = charging;
    }

    void write(PayloadWriter w) {
        w.str(model).str(os).i32(battery).bool(charging);
    }

    static DeviceStatus read(PayloadReader r) throws IOException {
        return new DeviceStatus(r.str(), r.str(), r.i32(), r.bool());
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(model).append(" · ").append(os);
        if (battery >= 0) sb.append(" · Akku: ").append(battery).append('%').append(charging ? " (töltődik)" : "");
        return sb.toString();
    }
}
