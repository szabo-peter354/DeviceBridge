package com.devicebridge.desktop;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/** Naplófájl az adatmappában (devicebridge.log, 1 MB fölött .old-ra forgatva). */
final class FileLog {
    private static File file;

    private FileLog() {}

    static synchronized void init(File dataDir) {
        file = new File(dataDir, "devicebridge.log");
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            write(String.format("%tT  VÁRATLAN HIBA a(z) %s szálon:%n%s", new Date(), t.getName(), sw));
        });
        write(String.format("%n%tF %<tT  ===== DeviceBridge indul (Java %s, %s) =====", new Date(),
                System.getProperty("java.version"), System.getProperty("os.name")));
    }

    static synchronized void write(String line) {
        if (file == null) return;
        try {
            if (file.length() > 1024 * 1024) {
                File old = new File(file.getPath() + ".old");
                //noinspection ResultOfMethodCallIgnored
                old.delete();
                //noinspection ResultOfMethodCallIgnored
                file.renameTo(old);
            }
            try (Writer w = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                w.write(line);
                w.write(System.lineSeparator());
            }
        } catch (IOException ignored) {
        }
    }

    static File file() {
        return file;
    }
}
