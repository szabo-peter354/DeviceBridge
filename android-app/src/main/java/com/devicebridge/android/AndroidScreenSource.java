package com.devicebridge.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import com.devicebridge.common.feature.RemoteDesktopService;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * A telefon képernyőjének rögzítése MediaProjection-nel. A virtuális kijelzőt engedélyenként
 * csak egyszer szabad létrehozni (Android 14+), ezért átméretezéskor csak a felületet cseréljük.
 */
final class AndroidScreenSource implements RemoteDesktopService.ScreenSource {
    private final Context ctx;
    private final Handler handler;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private Runnable onStopped;
    private int screenW = 1;
    private int screenH = 1;
    private int frameW = 1;
    private int frameH = 1;
    private int dpi = 320;
    private int maxSize = 1280;
    private int[] lastPixels;
    private int lastW;
    private int lastH;
    private boolean deliverCached;
    private byte[] rowBuf;
    private int counter;

    AndroidScreenSource(Context ctx) {
        this.ctx = ctx;
        HandlerThread t = new HandlerThread("bridge-capture");
        t.start();
        handler = new Handler(t.getLooper());
    }

    synchronized void setProjection(MediaProjection mp, Runnable onStopped) {
        releaseInternal(true);
        projection = mp;
        this.onStopped = onStopped;
        mp.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Runnable cb;
                synchronized (AndroidScreenSource.this) {
                    if (projection != mp) return;
                    cb = AndroidScreenSource.this.onStopped;
                    releaseInternal(false);
                }
                if (cb != null) cb.run();
            }
        }, handler);
        updateScreenSize();
        computeFrameSize();
        reader = ImageReader.newInstance(frameW, frameH, PixelFormat.RGBA_8888, 2);
        display = mp.createVirtualDisplay("DeviceBridge", frameW, frameH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
    }

    synchronized boolean hasProjection() {
        return projection != null && display != null;
    }

    synchronized void release() {
        releaseInternal(true);
    }

    private void releaseInternal(boolean stopProjection) {
        if (display != null) display.release();
        display = null;
        if (reader != null) reader.close();
        reader = null;
        MediaProjection p = projection;
        projection = null;
        onStopped = null;
        lastPixels = null;
        if (stopProjection && p != null) {
            try { p.stop(); } catch (RuntimeException ignored) { }
        }
    }

    @SuppressWarnings("deprecation")
    private void updateScreenSize() {
        WindowManager wm = ctx.getSystemService(WindowManager.class);
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getMaximumWindowMetrics().getBounds();
            screenW = b.width();
            screenH = b.height();
        } else {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;
        }
        dpi = ctx.getResources().getDisplayMetrics().densityDpi;
    }

    private void computeFrameSize() {
        double scale = Math.min(1.0, (double) maxSize / Math.max(screenW, screenH));
        frameW = Math.max(2, ((int) Math.round(screenW * scale)) & ~1);
        frameH = Math.max(2, ((int) Math.round(screenH * scale)) & ~1);
    }

    /** Elforgatás vagy új méretkérés esetén a felület cseréje. */
    private void resizeIfNeeded() {
        if (display == null) return;
        updateScreenSize();
        int ow = frameW, oh = frameH;
        computeFrameSize();
        if (ow == frameW && oh == frameH) return;
        ImageReader old = reader;
        reader = ImageReader.newInstance(frameW, frameH, PixelFormat.RGBA_8888, 2);
        display.resize(frameW, frameH, dpi);
        display.setSurface(reader.getSurface());
        if (old != null) old.close();
        lastPixels = null;
    }

    @Override
    public synchronized void start(int maxSize) {
        if (display == null) throw new IllegalStateException("Nincs képernyőmegosztási engedély");
        this.maxSize = maxSize;
        resizeIfNeeded();
        deliverCached = true;
    }

    @Override
    public synchronized RemoteDesktopService.Frame capture() {
        if (display == null || reader == null) throw new IllegalStateException("A képernyőmegosztás leállt");
        if (++counter % 20 == 0) resizeIfNeeded();
        Image img = reader.acquireLatestImage();
        if (img == null) {
            if (deliverCached && lastPixels != null) {
                deliverCached = false;
                return new RemoteDesktopService.Frame(lastW, lastH, lastPixels.clone());
            }
            return null;
        }
        try {
            Image.Plane plane = img.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int ps = plane.getPixelStride();
            int rs = plane.getRowStride();
            int w = img.getWidth();
            int h = img.getHeight();
            int rowBytes = w * ps;
            if (rowBuf == null || rowBuf.length < rowBytes) rowBuf = new byte[rowBytes];
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                int pos = y * rs;
                if (pos + rowBytes > buf.limit()) break;
                buf.position(pos);
                buf.get(rowBuf, 0, rowBytes);
                int o = y * w;
                for (int x = 0, i = 0; x < w; x++, i += ps) {
                    px[o + x] = 0xFF000000 | ((rowBuf[i] & 0xff) << 16) | ((rowBuf[i + 1] & 0xff) << 8) | (rowBuf[i + 2] & 0xff);
                }
            }
            lastPixels = px;
            lastW = w;
            lastH = h;
            deliverCached = false;
            return new RemoteDesktopService.Frame(w, h, px);
        } finally {
            img.close();
        }
    }

    @Override
    public synchronized void stop() {
        deliverCached = false;
    }

    @Override
    public synchronized int screenWidth() {
        return screenW;
    }

    @Override
    public synchronized int screenHeight() {
        return screenH;
    }

    @Override
    public byte[] encodeJpeg(int[] pixels, int stride, int x, int y, int w, int h, int quality) {
        Bitmap bmp = Bitmap.createBitmap(pixels, y * stride + x, stride, w, h, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos);
        bmp.recycle();
        return bos.toByteArray();
    }
}
