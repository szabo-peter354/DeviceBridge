package com.devicebridge.desktop;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * USB kapcsolat az Android Debug Bridge-en (adb) keresztül: port továbbítás a telefon
 * DeviceBridge szerveréhez. A telefonon az USB hibakeresést engedélyezni kell.
 */
final class AdbHelper {

    static final class Device {
        final String serial;
        final String description;

        Device(String serial, String description) {
            this.serial = serial;
            this.description = description;
        }

        @Override
        public String toString() {
            return description + "  [" + serial + "]";
        }
    }

    private String adbPath;

    synchronized String adb() throws IOException {
        if (adbPath != null) return adbPath;
        String exe = SystemActions.isWindows() ? "adb.exe" : "adb";
        List<String> roots = new ArrayList<>();
        for (String env : new String[]{"ANDROID_HOME", "ANDROID_SDK_ROOT"}) {
            String v = System.getenv(env);
            if (v != null) roots.add(v);
        }
        String local = System.getenv("LOCALAPPDATA");
        if (local != null) roots.add(local + File.separator + "Android" + File.separator + "Sdk");
        String home = System.getProperty("user.home");
        roots.add(home + File.separator + "Android" + File.separator + "Sdk");
        roots.add(home + File.separator + "Library" + File.separator + "Android" + File.separator + "sdk");
        roots.add(new File("platform-tools").getAbsoluteFile().getParent());
        for (String r : roots) {
            if (r == null) continue;
            File f = new File(new File(r, "platform-tools"), exe);
            if (f.isFile()) return adbPath = f.getAbsolutePath();
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                File f = new File(dir, exe);
                if (f.isFile()) return adbPath = f.getAbsolutePath();
            }
        }
        throw new IOException("Az adb nem található. Telepítsd az Android SDK Platform-Tools csomagot.");
    }

    private String run(String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(adb());
        cmd.addAll(Arrays.asList(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        }
        try {
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("Az adb nem válaszol");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Megszakítva");
        }
        String out = bos.toString(StandardCharsets.UTF_8.name()).trim();
        if (p.exitValue() != 0) throw new IOException("adb hiba: " + out);
        return out;
    }

    List<Device> devices() throws IOException {
        List<Device> list = new ArrayList<>();
        for (String line : run("devices", "-l").split("\\r?\\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("List of") || line.startsWith("*")) continue;
            String[] parts = line.split("\\s+");
            if (parts.length < 2) continue;
            String state = parts[1];
            String desc = parts[0];
            for (String p : parts) if (p.startsWith("model:")) desc = p.substring(6).replace('_', ' ');
            if (!"device".equals(state)) desc += " (" + state + (state.equals("unauthorized") ? " – engedélyezd a telefonon" : "") + ")";
            list.add(new Device(parts[0], desc));
        }
        return list;
    }

    void forward(String serial, int localPort, int remotePort) throws IOException {
        run("-s", serial, "forward", "tcp:" + localPort, "tcp:" + remotePort);
    }

    void reverse(String serial, int remotePort, int localPort) throws IOException {
        run("-s", serial, "reverse", "tcp:" + remotePort, "tcp:" + localPort);
    }
}
