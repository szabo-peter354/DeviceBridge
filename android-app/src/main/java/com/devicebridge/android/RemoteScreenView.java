package com.devicebridge.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * A távoli képernyő kirajzolása és az érintések egérműveletekké alakítása:
 * koppintás = bal klikk, hosszú nyomás = jobb klikk, dupla koppintás = dupla klikk,
 * egy ujjas húzás = bal gombos húzás, két ujjas húzás = görgetés.
 */
final class RemoteScreenView extends View {

    interface InputListener {
        void onMove(int x, int y);
        void onButton(int button, boolean down, int x, int y);
        void onWheel(int amount, int x, int y);
    }

    private final Object lock = new Object();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect src = new Rect();
    private final Rect dst = new Rect();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int touchSlop;
    private Bitmap frame;
    private Canvas frameCanvas;
    private int frameW = 1;
    private int frameH = 1;
    private InputListener listener;

    // érintés állapot
    private float downX;
    private float downY;
    private long downTime;
    private boolean dragging;
    private boolean twoFinger;
    private boolean longPressed;
    private float lastScrollY;
    private float scrollAcc;
    private final Runnable longPress = () -> {
        if (dragging || twoFinger) return;
        longPressed = true;
        int[] p = toFrame(downX, downY);
        if (p != null && listener != null) {
            listener.onButton(com.devicebridge.common.feature.RemoteDesktopService.BUTTON_RIGHT, true, p[0], p[1]);
            listener.onButton(com.devicebridge.common.feature.RemoteDesktopService.BUTTON_RIGHT, false, p[0], p[1]);
            performHapticFeedback(HAPTIC_FEEDBACK_ENABLED);
        }
    };

    RemoteScreenView(Context ctx) {
        super(ctx);
        touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        setBackgroundColor(Color.BLACK);
    }

    void setListener(InputListener l) {
        listener = l;
    }

    void setFrameSize(int w, int h) {
        synchronized (lock) {
            if (frame != null) frame.recycle();
            frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            frameCanvas = new Canvas(frame);
            frameW = w;
            frameH = h;
        }
        postInvalidate();
    }

    void drawTile(Bitmap tile, int x, int y) {
        synchronized (lock) {
            if (frameCanvas != null) frameCanvas.drawBitmap(tile, x, y, null);
        }
        tile.recycle();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        synchronized (lock) {
            if (frame == null) {
                Paint p = new Paint();
                p.setColor(Color.GRAY);
                p.setTextSize(40);
                canvas.drawText("Várakozás a képre…", 40, 80, p);
                return;
            }
            float scale = Math.min((float) getWidth() / frameW, (float) getHeight() / frameH);
            int dw = Math.round(frameW * scale);
            int dh = Math.round(frameH * scale);
            int ox = (getWidth() - dw) / 2;
            int oy = (getHeight() - dh) / 2;
            src.set(0, 0, frameW, frameH);
            dst.set(ox, oy, ox + dw, oy + dh);
            canvas.drawBitmap(frame, src, dst, paint);
        }
    }

    private int[] toFrame(float x, float y) {
        Rect d = dst;
        if (d.width() <= 0 || d.height() <= 0) return null;
        int fx = Math.round((x - d.left) * frameW / (float) d.width());
        int fy = Math.round((y - d.top) * frameH / (float) d.height());
        fx = Math.max(0, Math.min(frameW - 1, fx));
        fy = Math.max(0, Math.min(frameH - 1, fy));
        return new int[]{fx, fy};
    }

    @Override
    @SuppressWarnings("ClickableViewAccessibility")
    public boolean onTouchEvent(MotionEvent e) {
        if (listener == null) return true;
        final int left = com.devicebridge.common.feature.RemoteDesktopService.BUTTON_LEFT;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                downTime = e.getEventTime();
                dragging = false;
                twoFinger = false;
                longPressed = false;
                handler.postDelayed(longPress, 550);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                handler.removeCallbacks(longPress);
                if (dragging) {
                    int[] p = toFrame(e.getX(0), e.getY(0));
                    if (p != null) listener.onButton(left, false, p[0], p[1]);
                    dragging = false;
                }
                twoFinger = true;
                lastScrollY = (e.getY(0) + e.getY(1)) / 2f;
                scrollAcc = 0;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (twoFinger) {
                    if (e.getPointerCount() < 2) return true;
                    float y = (e.getY(0) + e.getY(1)) / 2f;
                    scrollAcc += y - lastScrollY;
                    lastScrollY = y;
                    int step = Math.round(scrollAcc / 60f);
                    if (step != 0) {
                        scrollAcc -= step * 60f;
                        int[] p = toFrame((e.getX(0) + e.getX(1)) / 2f, y);
                        if (p != null) listener.onWheel(-step, p[0], p[1]);
                    }
                    return true;
                }
                if (longPressed) return true;
                if (!dragging && Math.hypot(e.getX() - downX, e.getY() - downY) > touchSlop) {
                    handler.removeCallbacks(longPress);
                    dragging = true;
                    int[] s = toFrame(downX, downY);
                    if (s != null) {
                        listener.onMove(s[0], s[1]);
                        listener.onButton(left, true, s[0], s[1]);
                    }
                }
                if (dragging) {
                    int[] p = toFrame(e.getX(), e.getY());
                    if (p != null) listener.onMove(p[0], p[1]);
                }
                return true;
            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPress);
                if (dragging) {
                    int[] p = toFrame(e.getX(), e.getY());
                    if (p != null) listener.onButton(left, false, p[0], p[1]);
                } else if (!twoFinger && !longPressed) {
                    int[] p = toFrame(downX, downY);
                    if (p != null) {
                        listener.onMove(p[0], p[1]);
                        listener.onButton(left, true, p[0], p[1]);
                        listener.onButton(left, false, p[0], p[1]);
                    }
                }
                dragging = false;
                twoFinger = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                if (dragging) {
                    int[] p = toFrame(e.getX(), e.getY());
                    if (p != null) listener.onButton(left, false, p[0], p[1]);
                }
                dragging = false;
                twoFinger = false;
                return true;
            default:
                return true;
        }
    }
}
