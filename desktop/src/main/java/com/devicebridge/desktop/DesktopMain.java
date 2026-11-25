package com.devicebridge.desktop;

import com.formdev.flatlaf.FlatLightLaf;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.Arrays;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/** A PC alkalmazás belépési pontja. Indítás: java -jar DeviceBridge-desktop.jar [--minimized] */
public final class DesktopMain {
    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private static FileLock instanceLock;

    private DesktopMain() {}

    public static void main(String[] args) throws Exception {
        boolean minimized = Arrays.asList(args).contains("--minimized");
        System.setProperty("awt.useSystemAAFontSettings", "on");
        FlatLightLaf.setup();

        File dataDir = DesktopSettings.defaultDataDir();
        //noinspection ResultOfMethodCallIgnored
        dataDir.mkdirs();
        FileLog.init(dataDir);

        // Egyszerre csak egy példány futhat (a portok miatt)
        FileChannel ch = new RandomAccessFile(new File(dataDir, "app.lock"), "rw").getChannel();
        instanceLock = ch.tryLock();
        if (instanceLock == null) {
            JOptionPane.showMessageDialog(null, "A DeviceBridge már fut (nézd meg a tálcán).", "DeviceBridge",
                    JOptionPane.INFORMATION_MESSAGE);
            System.exit(0);
            return;
        }

        DesktopApp app;
        try {
            app = new DesktopApp(dataDir);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "Indítási hiba: " + e.getMessage(), "DeviceBridge", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
            return;
        }

        SwingUtilities.invokeLater(() -> {
            MainWindow window = new MainWindow(app);
            app.setUi(window);
            app.start();
            boolean hide = (minimized || app.settings.startMinimized()) && window.hasTray();
            if (!hide) window.setVisible(true);
        });
    }
}
