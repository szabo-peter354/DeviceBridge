package com.devicebridge.desktop;

import com.devicebridge.common.feature.ControlService;

import java.awt.Desktop;
import java.awt.Toolkit;
import java.net.URI;
import java.util.Locale;

/** Operációs rendszer szintű műveletek: zárolás, médiavezérlés, link megnyitás, hangjelzés. */
final class SystemActions {
    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private volatile Thread ringThread;

    static boolean isWindows() {
        return OS.contains("win");
    }

    static boolean isMac() {
        return OS.contains("mac");
    }

    private static void exec(String... cmd) {
        try {
            new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (Exception ignored) {
        }
    }

    void lock() {
        if (isWindows()) exec("rundll32.exe", "user32.dll,LockWorkStation");
        else if (isMac()) exec("pmset", "displaysleepnow");
        else exec("loginctl", "lock-session");
    }

    void media(int action) {
        if (isWindows()) {
            int ch;
            switch (action) {
                case ControlService.MEDIA_PLAY_PAUSE: ch = 179; break;
                case ControlService.MEDIA_NEXT: ch = 176; break;
                case ControlService.MEDIA_PREVIOUS: ch = 177; break;
                case ControlService.VOLUME_MUTE: ch = 173; break;
                case ControlService.VOLUME_DOWN: ch = 174; break;
                case ControlService.VOLUME_UP: ch = 175; break;
                default: return;
            }
            exec("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "(New-Object -ComObject WScript.Shell).SendKeys([char]" + ch + ")");
        } else if (!isMac()) {
            switch (action) {
                case ControlService.MEDIA_PLAY_PAUSE: exec("playerctl", "play-pause"); break;
                case ControlService.MEDIA_NEXT: exec("playerctl", "next"); break;
                case ControlService.MEDIA_PREVIOUS: exec("playerctl", "previous"); break;
                case ControlService.VOLUME_UP: exec("amixer", "-q", "set", "Master", "5%+"); break;
                case ControlService.VOLUME_DOWN: exec("amixer", "-q", "set", "Master", "5%-"); break;
                case ControlService.VOLUME_MUTE: exec("amixer", "-q", "set", "Master", "toggle"); break;
                default: break;
            }
        }
    }

    void openUrl(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
            } else if (isWindows()) {
                exec("rundll32", "url.dll,FileProtocolHandler", url);
            } else {
                exec("xdg-open", url);
            }
        } catch (Exception ignored) {
        }
    }

    synchronized void ring(boolean start) {
        Thread t = ringThread;
        if (t != null) t.interrupt();
        ringThread = null;
        if (!start) return;
        t = new Thread(() -> {
            long end = System.currentTimeMillis() + 15_000;
            try {
                while (System.currentTimeMillis() < end) {
                    Toolkit.getDefaultToolkit().beep();
                    Thread.sleep(400);
                }
            } catch (InterruptedException ignored) {
            }
        }, "bridge-ring");
        t.setDaemon(true);
        ringThread = t;
        t.start();
    }
}
