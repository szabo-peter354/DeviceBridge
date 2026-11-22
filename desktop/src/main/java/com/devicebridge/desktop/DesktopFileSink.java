package com.devicebridge.desktop;

import com.devicebridge.common.feature.FileTransferService;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.function.Supplier;

/** A fogadott fájlokat a letöltési mappába menti (alapból: Letöltések/DeviceBridge). */
final class DesktopFileSink implements FileTransferService.FileSink {
    private final Supplier<File> dir;

    DesktopFileSink(Supplier<File> dir) {
        this.dir = dir;
    }

    private static synchronized File uniqueTarget(File folder, String name) {
        File f = new File(folder, name);
        if (!f.exists() && !new File(folder, name + ".part").exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            f = new File(folder, base + " (" + i + ")" + ext);
            if (!f.exists() && !new File(folder, f.getName() + ".part").exists()) return f;
        }
    }

    @Override
    public FileTransferService.IncomingFile begin(String name, long size) throws IOException {
        File folder = dir.get();
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Nem hozható létre: " + folder);
        if (size > 0 && folder.getUsableSpace() < size) throw new IOException("Nincs elég szabad hely");
        File target = uniqueTarget(folder, name);
        File part = new File(folder, target.getName() + ".part");
        OutputStream out = new BufferedOutputStream(new FileOutputStream(part), 256 * 1024);
        return new FileTransferService.IncomingFile() {
            @Override
            public OutputStream stream() {
                return out;
            }

            @Override
            public String complete() throws IOException {
                out.close();
                if (!part.renameTo(target)) throw new IOException("Nem nevezhető át: " + part);
                return target.getAbsolutePath();
            }

            @Override
            public void abort() {
                try { out.close(); } catch (IOException ignored) { }
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            }
        };
    }
}
