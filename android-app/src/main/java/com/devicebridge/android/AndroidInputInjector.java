package com.devicebridge.android;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Path;
import android.os.SystemClock;
import android.provider.Settings;

import com.devicebridge.common.Keys;
import com.devicebridge.common.feature.RemoteDesktopService;

/**
 * A PC-ről érkező egéreseményeket érintésekké alakítja:
 * bal gomb = érintés / hosszú érintés / csúsztatás, jobb gomb = Vissza, középső = Kezdőlap,
 * görgő = görgetés (csúsztatás).
 */
final class AndroidInputInjector implements RemoteDesktopService.InputInjector {
    private final AndroidBridge bridge;
    private Path path;
    private boolean pressed;
    private boolean moved;
    private long downTime;
    private float startX;
    private float startY;
    private long lastWarning;

    AndroidInputInjector(AndroidBridge bridge) {
        this.bridge = bridge;
    }

    private InputAccessibilityService service() {
        InputAccessibilityService s = InputAccessibilityService.instance;
        if (s == null) {
            long now = SystemClock.uptimeMillis();
            if (now - lastWarning > 30_000) {
                lastWarning = now;
                Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                Notifications.show(bridge.ctx, Notifications.ID_ACCESSIBILITY, "Távvezérlés kikapcsolva",
                        "A PC-ről érkező érintésekhez kapcsold be: Akadálymentesítés → DeviceBridge távvezérlés.",
                        Notifications.activity(bridge.ctx, 5, i));
                bridge.node.log("Távvezérlés: az akadálymentesítési szolgáltatás nincs bekapcsolva");
            }
        }
        return s;
    }

    @Override
    public synchronized void mouseMove(int x, int y) {
        if (!pressed || path == null) return;
        path.lineTo(Math.max(0, x), Math.max(0, y));
        if (Math.hypot(x - startX, y - startY) > 12) moved = true;
    }

    @Override
    public synchronized void mouseButton(int button, boolean down, int x, int y) {
        InputAccessibilityService s = service();
        if (s == null) return;
        x = Math.max(0, x);
        y = Math.max(0, y);
        if (button == RemoteDesktopService.BUTTON_RIGHT) {
            if (down) s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            return;
        }
        if (button == RemoteDesktopService.BUTTON_MIDDLE) {
            if (down) s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
            return;
        }
        if (down) {
            pressed = true;
            moved = false;
            downTime = SystemClock.uptimeMillis();
            startX = x;
            startY = y;
            path = new Path();
            path.moveTo(x, y);
            return;
        }
        if (!pressed) return;
        pressed = false;
        long dur = SystemClock.uptimeMillis() - downTime;
        if (!moved) {
            Path tap = new Path();
            tap.moveTo(startX, startY);
            s.stroke(tap, dur >= 500 ? 800 : 60);
        } else {
            path.lineTo(x, y);
            s.stroke(path, Math.max(120, Math.min(dur, 3000)));
        }
        path = null;
    }

    @Override
    public void wheel(int amount, int x, int y) {
        InputAccessibilityService s = service();
        if (s == null || amount == 0) return;
        int dy = Math.max(-1200, Math.min(1200, amount * 300));
        Path p = new Path();
        p.moveTo(Math.max(0, x), Math.max(0, y));
        p.lineTo(Math.max(0, x), Math.max(0, y - dy));
        s.stroke(p, 220);
    }

    @Override
    public void key(int keyCode, boolean down) {
        if (!down) return;
        InputAccessibilityService s = service();
        if (s == null) return;
        switch (keyCode) {
            case Keys.ESCAPE: s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); break;
            case Keys.HOME: s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); break;
            case Keys.BACK_SPACE: s.backspace(); break;
            case Keys.ENTER: s.enter(); break;
            default: break;
        }
    }

    @Override
    public void text(String text) {
        InputAccessibilityService s = service();
        if (s != null) s.typeText(text);
    }

    @Override
    public void action(int action) {
        InputAccessibilityService s = service();
        if (s == null) return;
        switch (action) {
            case RemoteDesktopService.ACTION_BACK: s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); break;
            case RemoteDesktopService.ACTION_HOME: s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); break;
            case RemoteDesktopService.ACTION_RECENTS: s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS); break;
            case RemoteDesktopService.ACTION_NOTIFICATIONS: s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS); break;
            case RemoteDesktopService.ACTION_LOCK: s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN); break;
            case RemoteDesktopService.ACTION_POWER: s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG); break;
            default: break;
        }
    }
}
