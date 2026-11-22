package com.devicebridge.common.feature;

import com.devicebridge.common.PayloadReader;
import com.devicebridge.common.PayloadWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A beépített SSH szerver elérési adatai. A titkosított, párosított csatornán keresztül
 * jut el a másik eszközhöz, így az automatikusan be tud lépni.
 */
public final class SshInfo {
    public final boolean enabled;
    public final String user;
    public final String password;
    public final String hostKeyFingerprint;
    /** Elérési pontok "host:port" formában, előnyben részesített sorrendben. */
    public final List<String> endpoints;

    public SshInfo(boolean enabled, String user, String password, String hostKeyFingerprint, List<String> endpoints) {
        this.enabled = enabled;
        this.user = user;
        this.password = password;
        this.hostKeyFingerprint = hostKeyFingerprint;
        this.endpoints = Collections.unmodifiableList(new ArrayList<>(endpoints));
    }

    public static SshInfo disabled() {
        return new SshInfo(false, "", "", "", new ArrayList<>());
    }

    void write(PayloadWriter w) {
        w.bool(enabled).str(user).str(password).str(hostKeyFingerprint).i32(endpoints.size());
        for (String e : endpoints) w.str(e);
    }

    static SshInfo read(PayloadReader r) throws IOException {
        boolean enabled = r.bool();
        String user = r.str();
        String pass = r.str();
        String fp = r.str();
        int n = r.i32();
        if (n < 0 || n > 64) throw new IOException("Hibás SSH info");
        List<String> eps = new ArrayList<>();
        for (int i = 0; i < n; i++) eps.add(r.str());
        return new SshInfo(enabled, user, pass, fp, eps);
    }
}
