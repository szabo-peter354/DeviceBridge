package com.devicebridge.common.feature;

import com.devicebridge.common.BridgeNode;
import com.devicebridge.common.BridgeSession;
import com.devicebridge.common.MsgType;
import com.devicebridge.common.PayloadReader;
import com.devicebridge.common.PayloadWriter;
import com.devicebridge.common.PeerInfo;

import java.io.IOException;
import java.util.List;

/**
 * RDP-szerű távoli asztal saját protokollal: a gazda (host) képernyőjét csempékre bontva,
 * JPEG-ként küldi (csak a változásokat), a néző (viewer) egér-/érintés-/billentyűeseményeket küld vissza.
 * Ugyanez a kód fut PC-n és Androidon; a platform csak a képernyőforrást és a bemenet-injektálást adja.
 */
public final class RemoteDesktopService implements BridgeNode.Feature {

    public static final int BUTTON_LEFT = 1;
    public static final int BUTTON_MIDDLE = 2;
    public static final int BUTTON_RIGHT = 3;

    public static final int ACTION_BACK = 1;
    public static final int ACTION_HOME = 2;
    public static final int ACTION_RECENTS = 3;
    public static final int ACTION_NOTIFICATIONS = 4;
    public static final int ACTION_LOCK = 5;
    public static final int ACTION_POWER = 6;

    private static final int TILE_SIZE = 64;

    public static final class Frame {
        public final int width;
        public final int height;
        /** ARGB pixelek, sorhossz = width. */
        public final int[] pixels;

        public Frame(int width, int height, int[] pixels) {
            this.width = width;
            this.height = height;
            this.pixels = pixels;
        }
    }

    /** A gazda képernyőjének forrása (platformfüggő). */
    public interface ScreenSource {
        /** Előkészítés; a képkocka hosszabbik oldala legfeljebb maxSize pixel legyen. */
        void start(int maxSize) throws Exception;

        /** A legfrissebb képkocka, vagy null, ha nincs új. */
        Frame capture() throws Exception;

        void stop();

        int screenWidth();

        int screenHeight();

        byte[] encodeJpeg(int[] pixels, int stride, int x, int y, int w, int h, int quality) throws IOException;
    }

    /** Bemenet a gazdán – képernyő-koordinátákban. */
    public interface InputInjector {
        void mouseMove(int x, int y);
        void mouseButton(int button, boolean down, int x, int y);
        void wheel(int amount, int x, int y);
        void key(int keyCode, boolean down);
        void text(String text);
        void action(int action);
    }

    public interface HostProvider {
        /** Null, ha a képernyő most nem érhető el (pl. Androidon engedély kell). */
        ScreenSource screenSource();

        InputInjector inputInjector();

        boolean isRemoteControlAllowed();

        /** A néző megtekintést kért, de a forrás még nem elérhető. Felhasználónak szóló üzenetet ad vissza. */
        String onAccessRequired(PeerInfo viewer);

        void onHostStateChanged(boolean streaming, PeerInfo viewer);
    }

    public interface Viewer {
        void onFrameInfo(int screenWidth, int screenHeight, int frameWidth, int frameHeight);

        /** A hívó szálon dekódolható; a következő képkocka csak a visszaigazolás után jön. */
        void onTile(int x, int y, int w, int h, byte[] jpeg);

        void onFrameEnd();

        void onStatus(String message);

        void onRemoteStopped(String reason);
    }

    private final BridgeNode node;
    private volatile HostProvider hostProvider;
    private volatile HostStream hostStream;
    private volatile int[] pendingStart; // {maxSize, quality, fps}
    private volatile Viewer viewer;

    public RemoteDesktopService(BridgeNode node) {
        this.node = node;
        node.addFeature(this);

        // ---- gazda oldali üzenetek
        node.registerHandler(MsgType.RD_START, (s, m) -> {
            PayloadReader r = m.reader();
            onStartRequested(s, r.i32(), r.u8(), r.u8());
        });
        node.registerHandler(MsgType.RD_STOP, (s, m) -> {
            pendingStart = null;
            stopHost(null);
        });
        node.registerHandler(MsgType.RD_ACK, (s, m) -> {
            HostStream h = hostStream;
            if (h != null) h.ack(m.reader().i64());
        });
        node.registerHandler(MsgType.RD_MOUSE_MOVE, (s, m) -> {
            HostStream h = controllableStream();
            if (h == null) return;
            PayloadReader r = m.reader();
            int x = r.i32(), y = r.i32();
            h.injector.mouseMove(h.toScreenX(x), h.toScreenY(y));
        });
        node.registerHandler(MsgType.RD_MOUSE_BUTTON, (s, m) -> {
            HostStream h = controllableStream();
            if (h == null) return;
            PayloadReader r = m.reader();
            int button = r.u8();
            boolean down = r.bool();
            int x = r.i32(), y = r.i32();
            h.injector.mouseButton(button, down, h.toScreenX(x), h.toScreenY(y));
        });
        node.registerHandler(MsgType.RD_WHEEL, (s, m) -> {
            HostStream h = controllableStream();
            if (h == null) return;
            PayloadReader r = m.reader();
            int amount = r.i32();
            int x = r.i32(), y = r.i32();
            h.injector.wheel(amount, h.toScreenX(x), h.toScreenY(y));
        });
        node.registerHandler(MsgType.RD_KEY, (s, m) -> {
            HostStream h = controllableStream();
            if (h == null) return;
            PayloadReader r = m.reader();
            h.injector.key(r.i32(), r.bool());
        });
        node.registerHandler(MsgType.RD_TEXT, (s, m) -> {
            HostStream h = controllableStream();
            if (h != null) h.injector.text(m.reader().str());
        });
        node.registerHandler(MsgType.RD_ACTION, (s, m) -> {
            HostStream h = controllableStream();
            if (h != null) h.injector.action(m.reader().u8());
        });

        // ---- néző oldali üzenetek
        node.registerHandler(MsgType.RD_FRAME_INFO, (s, m) -> {
            Viewer v = viewer;
            PayloadReader r = m.reader();
            int sw = r.i32(), sh = r.i32(), fw = r.i32(), fh = r.i32();
            if (v != null) v.onFrameInfo(sw, sh, fw, fh);
        });
        node.registerHandler(MsgType.RD_TILE, (s, m) -> {
            Viewer v = viewer;
            if (v == null) return;
            PayloadReader r = m.reader();
            int x = r.i32(), y = r.i32(), w = r.i32(), h = r.i32();
            v.onTile(x, y, w, h, r.bytes());
        });
        node.registerHandler(MsgType.RD_FRAME_END, (s, m) -> {
            long seq = m.reader().i64();
            Viewer v = viewer;
            if (v != null) v.onFrameEnd();
            node.send(MsgType.RD_ACK, PayloadWriter.create().i64(seq));
        });
        node.registerHandler(MsgType.RD_STATUS, (s, m) -> {
            Viewer v = viewer;
            if (v != null) v.onStatus(m.reader().str());
        });
        node.registerHandler(MsgType.RD_STOPPED, (s, m) -> {
            Viewer v = viewer;
            viewer = null;
            if (v != null) v.onRemoteStopped(m.reader().str());
        });
    }

    public void setHostProvider(HostProvider p) {
        hostProvider = p;
    }

    // ================================================================ GAZDA

    private HostStream controllableStream() {
        HostStream h = hostStream;
        HostProvider p = hostProvider;
        if (h == null || p == null || !p.isRemoteControlAllowed() || h.injector == null) return null;
        return h;
    }

    private void onStartRequested(BridgeSession s, int maxSize, int quality, int fps) {
        HostProvider p = hostProvider;
        stopHost(null);
        if (p == null) {
            node.send(MsgType.RD_STOPPED, PayloadWriter.create().str("Ezen az eszközön nem érhető el a képernyőmegosztás"));
            return;
        }
        if (!p.isRemoteControlAllowed()) {
            node.send(MsgType.RD_STOPPED, PayloadWriter.create().str("A távoli asztal le van tiltva a másik eszköz beállításaiban"));
            return;
        }
        int[] params = {Math.max(320, Math.min(maxSize, 4096)), Math.max(10, Math.min(quality, 95)), Math.max(1, Math.min(fps, 30))};
        ScreenSource src = p.screenSource();
        if (src == null) {
            pendingStart = params;
            String msg = p.onAccessRequired(s.peer());
            node.send(MsgType.RD_STATUS, PayloadWriter.create().str(msg));
            return;
        }
        startHost(src, p, params, s.peer());
    }

    /** A platform jelzi, hogy a képernyőforrás elérhetővé vált (pl. Androidon megadták az engedélyt). */
    public void hostSourceReady() {
        int[] params = pendingStart;
        HostProvider p = hostProvider;
        if (params == null || p == null || !node.isConnected()) return;
        ScreenSource src = p.screenSource();
        if (src == null) return;
        pendingStart = null;
        startHost(src, p, params, node.peer());
    }

    /** A platform jelzi, hogy a felhasználó megtagadta a hozzáférést. */
    public void hostAccessDenied(String reason) {
        if (pendingStart == null) return;
        pendingStart = null;
        node.send(MsgType.RD_STOPPED, PayloadWriter.create().str(reason));
    }

    private synchronized void startHost(ScreenSource src, HostProvider p, int[] params, PeerInfo viewerPeer) {
        HostStream h = new HostStream(src, p.inputInjector(), params[0], params[1], params[2], viewerPeer);
        hostStream = h;
        Thread t = new Thread(h, "bridge-screen-host");
        t.setDaemon(true);
        h.thread = t;
        t.start();
        p.onHostStateChanged(true, viewerPeer);
        node.log("Képernyőmegosztás indult: " + (viewerPeer == null ? "?" : viewerPeer.name));
    }

    /** Leállítja a megosztást; ha reason nem null, értesíti a nézőt. */
    public synchronized void stopHost(String reason) {
        HostStream h = hostStream;
        hostStream = null;
        if (h != null) {
            h.running = false;
            synchronized (h.ackLock) { h.ackLock.notifyAll(); }
            if (reason != null) node.send(MsgType.RD_STOPPED, PayloadWriter.create().str(reason));
            Thread t = h.thread;
            if (t != null && t != Thread.currentThread()) {
                try { t.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
    }

    public boolean isHosting() {
        return hostStream != null;
    }

    private final class HostStream implements Runnable {
        final ScreenSource src;
        final InputInjector injector;
        final int maxSize;
        final int quality;
        final int fps;
        final PeerInfo viewerPeer;
        final Object ackLock = new Object();
        volatile boolean running = true;
        volatile Thread thread;
        long lastAck;
        long seq;
        volatile int frameW = 1, frameH = 1, screenW = 1, screenH = 1;

        HostStream(ScreenSource src, InputInjector injector, int maxSize, int quality, int fps, PeerInfo viewerPeer) {
            this.src = src;
            this.injector = injector;
            this.maxSize = maxSize;
            this.quality = quality;
            this.fps = fps;
            this.viewerPeer = viewerPeer;
        }

        int toScreenX(int x) {
            return (int) Math.max(0, Math.min(screenW - 1, (long) x * screenW / frameW));
        }

        int toScreenY(int y) {
            return (int) Math.max(0, Math.min(screenH - 1, (long) y * screenH / frameH));
        }

        void ack(long s) {
            synchronized (ackLock) {
                if (s > lastAck) lastAck = s;
                ackLock.notifyAll();
            }
        }

        /** Legfeljebb 2 képkocka lehet úton (pipeline), így a sávszélességhez igazodik. */
        private void waitForAck(long target) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5000;
            synchronized (ackLock) {
                while (running && lastAck < target) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) return;
                    ackLock.wait(left);
                }
            }
        }

        @Override
        public void run() {
            TileDiffer differ = new TileDiffer(TILE_SIZE);
            String error = null;
            try {
                src.start(maxSize);
                long minInterval = 1000L / fps;
                while (running && node.isConnected()) {
                    long t0 = System.currentTimeMillis();
                    Frame f = src.capture();
                    if (f == null) {
                        Thread.sleep(25);
                        continue;
                    }
                    if (f.width != frameW || f.height != frameH || src.screenWidth() != screenW || src.screenHeight() != screenH) {
                        frameW = f.width;
                        frameH = f.height;
                        screenW = src.screenWidth();
                        screenH = src.screenHeight();
                        differ.reset();
                        node.send(MsgType.RD_FRAME_INFO, PayloadWriter.create().i32(screenW).i32(screenH).i32(frameW).i32(frameH));
                    }
                    List<int[]> rects = differ.diff(f.pixels, f.width, f.height);
                    if (!rects.isEmpty()) {
                        long s = ++seq;
                        for (int[] r : rects) {
                            if (!running) break;
                            byte[] jpeg = src.encodeJpeg(f.pixels, f.width, r[0], r[1], r[2], r[3], quality);
                            if (!node.send(MsgType.RD_TILE, PayloadWriter.create().i32(r[0]).i32(r[1]).i32(r[2]).i32(r[3]).bytes(jpeg))) {
                                running = false;
                                break;
                            }
                        }
                        node.send(MsgType.RD_FRAME_END, PayloadWriter.create().i64(s));
                        waitForAck(s - 1);
                    }
                    long elapsed = System.currentTimeMillis() - t0;
                    if (elapsed < minInterval) Thread.sleep(minInterval - elapsed);
                }
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                error = "Képernyőmegosztás hiba: " + BridgeNode.describe(e);
                node.log(error);
            } finally {
                try { src.stop(); } catch (RuntimeException ignored) { }
                if (hostStream == this) hostStream = null;
                if (error != null) node.send(MsgType.RD_STOPPED, PayloadWriter.create().str(error));
                HostProvider p = hostProvider;
                if (p != null && hostStream == null) p.onHostStateChanged(false, viewerPeer);
                node.log("Képernyőmegosztás leállt");
            }
        }
    }

    // ================================================================ NÉZŐ

    /** A másik eszköz képernyőjének megtekintése. Koordináták a képkocka pixeleiben. */
    public boolean startViewing(Viewer v, int maxSize, int quality, int fps) {
        viewer = v;
        boolean ok = node.send(MsgType.RD_START, PayloadWriter.create().i32(maxSize).u8(quality).u8(fps));
        if (!ok) viewer = null;
        return ok;
    }

    public void stopViewing(Viewer v) {
        if (viewer != v) return;
        viewer = null;
        node.send(MsgType.RD_STOP, new byte[0]);
    }

    public boolean isViewing() {
        return viewer != null;
    }

    public void sendMouseMove(int x, int y) {
        node.send(MsgType.RD_MOUSE_MOVE, PayloadWriter.create().i32(x).i32(y));
    }

    public void sendMouseButton(int button, boolean down, int x, int y) {
        node.send(MsgType.RD_MOUSE_BUTTON, PayloadWriter.create().u8(button).bool(down).i32(x).i32(y));
    }

    public void sendWheel(int amount, int x, int y) {
        node.send(MsgType.RD_WHEEL, PayloadWriter.create().i32(amount).i32(x).i32(y));
    }

    public void sendKey(int keyCode, boolean down) {
        node.send(MsgType.RD_KEY, PayloadWriter.create().i32(keyCode).bool(down));
    }

    public void sendKeyPress(int keyCode) {
        sendKey(keyCode, true);
        sendKey(keyCode, false);
    }

    public void sendText(String text) {
        if (text != null && !text.isEmpty()) node.send(MsgType.RD_TEXT, PayloadWriter.create().str(text));
    }

    public void sendAction(int action) {
        node.send(MsgType.RD_ACTION, PayloadWriter.create().u8(action));
    }

    // ================================================================ munkamenet

    @Override
    public void onSessionStarted(BridgeSession session) {
    }

    @Override
    public void onSessionEnded(BridgeSession session) {
        pendingStart = null;
        stopHost(null);
        Viewer v = viewer;
        viewer = null;
        if (v != null) v.onRemoteStopped("A kapcsolat megszakadt");
    }
}
