package com.devicebridge.android;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Akadálymentesítési szolgáltatás a távvezérléshez: érintéseket/csúsztatásokat hajt végre és
 * szöveget ír a fókuszban lévő mezőbe. Képernyőtartalmat nem olvas és nem tárol.
 */
public class InputAccessibilityService extends AccessibilityService {
    static volatile InputAccessibilityService instance;

    static boolean isRunning() {
        return instance != null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    void stroke(Path path, long durationMs) {
        long d = Math.max(1, Math.min(durationMs, GestureDescription.getMaxGestureDuration()));
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, d))
                .build();
        dispatchGesture(g, null, null);
    }

    private AccessibilityNodeInfo focusedEditable() {
        AccessibilityNodeInfo n = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        return n != null && n.isEditable() ? n : null;
    }

    private static CharSequence currentText(AccessibilityNodeInfo n) {
        if (n.isShowingHintText()) return "";
        CharSequence t = n.getText();
        return t == null ? "" : t;
    }

    private static void setText(AccessibilityNodeInfo n, CharSequence text) {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    void typeText(String s) {
        AccessibilityNodeInfo n = focusedEditable();
        if (n == null) return;
        setText(n, currentText(n).toString() + s);
    }

    void backspace() {
        AccessibilityNodeInfo n = focusedEditable();
        if (n == null) return;
        CharSequence cur = currentText(n);
        if (cur.length() > 0) setText(n, cur.subSequence(0, cur.length() - 1));
    }

    void enter() {
        AccessibilityNodeInfo n = focusedEditable();
        if (n == null) return;
        if (Build.VERSION.SDK_INT >= 30) {
            n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
        } else {
            setText(n, currentText(n).toString() + "\n");
        }
    }
}
