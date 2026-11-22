package com.devicebridge.desktop;

import com.devicebridge.common.feature.RemoteDesktopService;

import java.awt.AWTException;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/** Egér- és billentyűzetesemények szimulálása a PC-n (java.awt.Robot). */
final class DesktopInputInjector implements RemoteDesktopService.InputInjector {
    private final Robot robot;
    private final DesktopApp app;

    DesktopInputInjector(DesktopApp app) throws AWTException {
        this.app = app;
        this.robot = new Robot();
        robot.setAutoDelay(0);
    }

    private static Rectangle bounds() {
        return DesktopScreenSource.primaryBounds();
    }

    @Override
    public void mouseMove(int x, int y) {
        Rectangle b = bounds();
        robot.mouseMove(b.x + x, b.y + y);
    }

    @Override
    public void mouseButton(int button, boolean down, int x, int y) {
        mouseMove(x, y);
        int mask;
        switch (button) {
            case RemoteDesktopService.BUTTON_RIGHT: mask = InputEvent.BUTTON3_DOWN_MASK; break;
            case RemoteDesktopService.BUTTON_MIDDLE: mask = InputEvent.BUTTON2_DOWN_MASK; break;
            default: mask = InputEvent.BUTTON1_DOWN_MASK;
        }
        if (down) robot.mousePress(mask);
        else robot.mouseRelease(mask);
    }

    @Override
    public void wheel(int amount, int x, int y) {
        mouseMove(x, y);
        robot.mouseWheel(amount);
    }

    @Override
    public void key(int keyCode, boolean down) {
        try {
            if (down) robot.keyPress(keyCode);
            else robot.keyRelease(keyCode);
        } catch (IllegalArgumentException ignored) {
            // ismeretlen billentyűkód
        }
    }

    private void tap(int code) {
        key(code, true);
        key(code, false);
    }

    @Override
    public void text(String text) {
        boolean simple = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == ' ')) {
                simple = false;
                break;
            }
        }
        if (simple) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                boolean upper = Character.isUpperCase(c);
                int code = KeyEvent.getExtendedKeyCodeForChar(Character.toLowerCase(c));
                if (upper) key(KeyEvent.VK_SHIFT, true);
                tap(code);
                if (upper) key(KeyEvent.VK_SHIFT, false);
            }
        } else {
            // Ékezetes és speciális karakterek: beillesztés a vágólapon keresztül
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
            robot.delay(30);
            key(KeyEvent.VK_CONTROL, true);
            tap(KeyEvent.VK_V);
            key(KeyEvent.VK_CONTROL, false);
        }
    }

    @Override
    public void action(int action) {
        switch (action) {
            case RemoteDesktopService.ACTION_BACK:
                key(KeyEvent.VK_ALT, true);
                tap(KeyEvent.VK_LEFT);
                key(KeyEvent.VK_ALT, false);
                break;
            case RemoteDesktopService.ACTION_HOME:
                tap(KeyEvent.VK_WINDOWS);
                break;
            case RemoteDesktopService.ACTION_RECENTS:
                key(KeyEvent.VK_WINDOWS, true);
                tap(KeyEvent.VK_TAB);
                key(KeyEvent.VK_WINDOWS, false);
                break;
            case RemoteDesktopService.ACTION_NOTIFICATIONS:
                key(KeyEvent.VK_WINDOWS, true);
                tap(KeyEvent.VK_N);
                key(KeyEvent.VK_WINDOWS, false);
                break;
            case RemoteDesktopService.ACTION_LOCK:
                app.systemActions().lock();
                break;
            default:
                break;
        }
    }
}
