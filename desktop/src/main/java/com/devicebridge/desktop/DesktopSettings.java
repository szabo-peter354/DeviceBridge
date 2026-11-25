package com.devicebridge.desktop;

import com.devicebridge.common.crypto.CryptoUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.util.Properties;

/** A PC alkalmazás beállításai (settings.properties az adatmappában). */
public final class DesktopSettings {
    private final File file;
    private final Properties p = new Properties();

    public DesktopSettings(File dataDir) {
        this.file = new File(dataDir, "settings.properties");
        if (file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
                p.load(in);
            } catch (IOException ignored) {
            }
        }
        if (p.getProperty("ssh.password") == null) {
            p.setProperty("ssh.password", CryptoUtil.randomPassword(12));
            save();
        }
    }

    public static File defaultDataDir() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isEmpty()) return new File(appData, "DeviceBridge");
        return new File(System.getProperty("user.home"), ".devicebridge");
    }

    public synchronized String deviceName() {
        String n = p.getProperty("device.name");
        if (n != null && !n.trim().isEmpty()) return n.trim();
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            String env = System.getenv("COMPUTERNAME");
            return env != null ? env : "PC";
        }
    }

    public synchronized void setDeviceName(String name) { p.setProperty("device.name", name); }

    public synchronized boolean allowRemoteControl() { return Boolean.parseBoolean(p.getProperty("remote.allow", "true")); }
    public synchronized void setAllowRemoteControl(boolean v) { p.setProperty("remote.allow", String.valueOf(v)); }

    public synchronized boolean sshEnabled() { return Boolean.parseBoolean(p.getProperty("ssh.enabled", "false")); }
    public synchronized void setSshEnabled(boolean v) { p.setProperty("ssh.enabled", String.valueOf(v)); }

    public synchronized String sshUser() { return p.getProperty("ssh.user", "bridge"); }

    public synchronized String sshPassword() { return p.getProperty("ssh.password"); }
    public synchronized void regenerateSshPassword() { p.setProperty("ssh.password", CryptoUtil.randomPassword(12)); }

    /** Ebben a mappában indul az SSH shell és az SFTP (alapból a felhasználó saját mappája, nem a program mappája). */
    public synchronized File sshStartDir() {
        String d = p.getProperty("ssh.startdir");
        if (d != null && !d.trim().isEmpty()) {
            File f = new File(d.trim());
            if (f.isDirectory()) return f.getAbsoluteFile();
        }
        return new File(System.getProperty("user.home"));
    }

    public synchronized void setSshStartDir(File dir) { p.setProperty("ssh.startdir", dir.getAbsolutePath()); }

    public synchronized boolean startMinimized() { return Boolean.parseBoolean(p.getProperty("start.minimized", "false")); }
    public synchronized void setStartMinimized(boolean v) { p.setProperty("start.minimized", String.valueOf(v)); }

    public synchronized File downloadDir() {
        String d = p.getProperty("download.dir");
        if (d != null && !d.isEmpty()) return new File(d);
        return new File(new File(System.getProperty("user.home"), "Downloads"), "DeviceBridge");
    }

    public synchronized void setDownloadDir(File dir) { p.setProperty("download.dir", dir.getAbsolutePath()); }

    public synchronized void save() {
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            try (OutputStream out = new FileOutputStream(file)) {
                p.store(out, "DeviceBridge beallitasok");
            }
        } catch (IOException ignored) {
        }
    }
}
