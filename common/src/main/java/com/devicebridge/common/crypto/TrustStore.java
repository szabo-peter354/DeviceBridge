package com.devicebridge.common.crypto;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

/** A párosított (megbízható) eszközök listája, fájlban tárolva. */
public final class TrustStore {

    public static final class TrustedDevice {
        public final String fingerprint;
        public final String name;
        public final long addedAt;

        TrustedDevice(String fingerprint, String name, long addedAt) {
            this.fingerprint = fingerprint;
            this.name = name;
            this.addedAt = addedAt;
        }

        @Override
        public String toString() {
            return name + "  [" + fingerprint + "]";
        }
    }

    private final File file;
    private final Properties props = new Properties();

    public TrustStore(File file) {
        this.file = file;
        if (file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
                props.load(in);
            } catch (IOException ignored) {
            }
        }
    }

    public synchronized boolean isTrusted(String fingerprint, byte[] publicKey) {
        String stored = props.getProperty(fingerprint + ".key");
        return stored != null && stored.equals(Base64.getEncoder().encodeToString(publicKey));
    }

    public synchronized void add(String fingerprint, String name, byte[] publicKey) {
        props.setProperty(fingerprint + ".key", Base64.getEncoder().encodeToString(publicKey));
        props.setProperty(fingerprint + ".name", name);
        props.setProperty(fingerprint + ".added", String.valueOf(System.currentTimeMillis()));
        save();
    }

    public synchronized void remove(String fingerprint) {
        props.remove(fingerprint + ".key");
        props.remove(fingerprint + ".name");
        props.remove(fingerprint + ".added");
        save();
    }

    public synchronized List<TrustedDevice> list() {
        List<TrustedDevice> out = new ArrayList<>();
        for (String k : props.stringPropertyNames()) {
            if (!k.endsWith(".key")) continue;
            String fp = k.substring(0, k.length() - 4);
            long added = 0;
            try { added = Long.parseLong(props.getProperty(fp + ".added", "0")); } catch (NumberFormatException ignored) { }
            out.add(new TrustedDevice(fp, props.getProperty(fp + ".name", "?"), added));
        }
        out.sort((a, b) -> Long.compare(b.addedAt, a.addedAt));
        return out;
    }

    private void save() {
        try {
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null) parent.mkdirs();
            File tmp = new File(file.getPath() + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                props.store(out, "DeviceBridge - parositott eszkozok");
            }
            if (file.exists() && !file.delete()) return;
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(file);
        } catch (IOException ignored) {
        }
    }
}
