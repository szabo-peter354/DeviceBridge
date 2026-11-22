package com.devicebridge.common;

import com.devicebridge.common.crypto.Identity;
import com.devicebridge.common.crypto.TrustStore;
import com.devicebridge.common.feature.ControlService;
import com.devicebridge.common.feature.DeviceStatus;
import com.devicebridge.common.feature.FileTransferService;
import com.devicebridge.common.feature.RemoteDesktopService;
import com.devicebridge.common.feature.SshInfo;
import com.devicebridge.common.transport.SocketTransport;
import com.devicebridge.common.transport.TransportKind;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Két teljes csomópont (PC és telefon szerepben) valódi TCP kapcsolaton keresztül. */
public class NodeIntegrationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** Vezérlő kezelő, ami rögzíti a beérkező parancsokat. */
    static final class RecordingControl implements ControlService.Handler {
        final AtomicReference<String> clipboard = new AtomicReference<>();
        final CountDownLatch clipLatch = new CountDownLatch(1);
        final CountDownLatch statusLatch = new CountDownLatch(1);
        volatile DeviceStatus status;

        @Override public void onClipboard(String text) { clipboard.set(text); clipLatch.countDown(); }
        @Override public void onOpenUrl(String url) { }
        @Override public void onRing(boolean start) { }
        @Override public void onLock() { }
        @Override public void onMedia(int action) { }
        @Override public void onNotify(String title, String text) { }
        @Override public void onPeerStatus(DeviceStatus s) { status = s; statusLatch.countDown(); }
        @Override public void onPeerSshInfo(SshInfo info) { }
    }

    /** Memóriába író fájlfogadó. */
    static final class MemorySink implements FileTransferService.FileSink {
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        volatile String name;

        @Override
        public FileTransferService.IncomingFile begin(String n, long size) {
            name = n;
            return new FileTransferService.IncomingFile() {
                @Override public OutputStream stream() { return data; }
                @Override public String complete() { return "memória"; }
                @Override public void abort() { }
            };
        }
    }

    /** Ál-képernyő: 2000x1000-es "kijelző", minden rögzítéskor egy csík változik. */
    static final class FakeScreen implements RemoteDesktopService.ScreenSource {
        int w;
        int h;
        int tick;

        @Override public void start(int maxSize) { w = Math.min(2000, maxSize); h = w / 2; }

        @Override
        public RemoteDesktopService.Frame capture() throws InterruptedException {
            Thread.sleep(10);
            int[] px = new int[w * h];
            int stripe = (tick++ % 4) * 10;
            for (int y = stripe; y < stripe + 10; y++) Arrays.fill(px, y * w, y * w + w, 0xFF00FF00);
            return new RemoteDesktopService.Frame(w, h, px);
        }

        @Override public void stop() { }
        @Override public int screenWidth() { return 2000; }
        @Override public int screenHeight() { return 1000; }

        @Override
        public byte[] encodeJpeg(int[] pixels, int stride, int x, int y, int w, int h, int quality) {
            return new byte[]{(byte) x, (byte) y, (byte) w, (byte) h};
        }
    }

    static final class Node {
        BridgeNode node;
        RecordingControl control = new RecordingControl();
        ControlService controlService;
        MemorySink sink = new MemorySink();
        FileTransferService files;
        RemoteDesktopService remote;
    }

    private Node create(String name, String type) throws Exception {
        File dir = tmp.newFolder(name);
        Node n = new Node();
        Identity id = Identity.loadOrCreate(new File(dir, "id"));
        n.node = new BridgeNode(() -> new LocalInfo(name, type), id, new TrustStore(new File(dir, "trust")),
                (peer, code) -> CompletableFuture.completedFuture(true));
        n.controlService = new ControlService(n.node, n.control);
        n.controlService.setStatusSupplier(() -> new DeviceStatus(name, type, 77, true));
        n.files = new FileTransferService(n.node, n.sink);
        n.remote = new RemoteDesktopService(n.node);
        return n;
    }

    private static void awaitState(BridgeNode n, BridgeNode.State s) throws InterruptedException {
        long end = System.currentTimeMillis() + 10_000;
        while (n.state() != s && System.currentTimeMillis() < end) Thread.sleep(20);
        assertEquals(s, n.state());
    }

    @Test
    public void fullSession() throws Exception {
        Node pc = create("PC", Protocol.DEVICE_TYPE_DESKTOP);
        Node phone = create("Telefon", Protocol.DEVICE_TYPE_ANDROID);

        // A PC gazdaként osztja meg a képernyőt, a bemeneteket rögzítjük
        List<String> injected = new CopyOnWriteArrayList<>();
        CountDownLatch clickLatch = new CountDownLatch(2);
        pc.remote.setHostProvider(new RemoteDesktopService.HostProvider() {
            @Override public RemoteDesktopService.ScreenSource screenSource() { return new FakeScreen(); }

            @Override
            public RemoteDesktopService.InputInjector inputInjector() {
                return new RemoteDesktopService.InputInjector() {
                    @Override public void mouseMove(int x, int y) { injected.add("move " + x + "," + y); }
                    @Override public void mouseButton(int b, boolean down, int x, int y) { injected.add("btn" + b + (down ? " down " : " up ") + x + "," + y); clickLatch.countDown(); }
                    @Override public void wheel(int a, int x, int y) { }
                    @Override public void key(int k, boolean d) { }
                    @Override public void text(String t) { injected.add("text " + t); }
                    @Override public void action(int a) { }
                };
            }

            @Override public boolean isRemoteControlAllowed() { return true; }
            @Override public String onAccessRequired(PeerInfo v) { return ""; }
            @Override public void onHostStateChanged(boolean s, PeerInfo v) { }
        });

        pc.node.startTcpServer(0);
        int port = pc.node.tcpPort();
        assertTrue(phone.node.connectAsync(() -> SocketTransport.connect("127.0.0.1", port, TransportKind.NETWORK, 3000)));
        awaitState(phone.node, BridgeNode.State.CONNECTED);
        awaitState(pc.node, BridgeNode.State.CONNECTED);
        assertEquals("PC", phone.node.peer().name);
        assertEquals("Telefon", pc.node.peer().name);

        // Állapot + vágólap
        assertTrue(pc.control.statusLatch.await(5, TimeUnit.SECONDS));
        assertEquals(77, pc.control.status.battery);
        assertTrue(phone.controlService.sendClipboard("Helló PC – árvíztűrő tükörfúrógép"));
        assertTrue(pc.control.clipLatch.await(5, TimeUnit.SECONDS));
        assertEquals("Helló PC – árvíztűrő tükörfúrógép", pc.control.clipboard.get());

        // Fájlátvitel (több darabban)
        byte[] file = new byte[300_000];
        new Random(1).nextBytes(file);
        FileTransferService.Transfer t = phone.files.sendFile("kép.jpg", file.length, () -> new ByteArrayInputStream(file));
        long end = System.currentTimeMillis() + 10_000;
        while (!t.isFinished() && System.currentTimeMillis() < end) Thread.sleep(20);
        assertEquals(t.message(), FileTransferService.Status.DONE, t.status());
        assertEquals("kép.jpg", pc.sink.name);
        assertArrayEquals(file, pc.sink.data.toByteArray());

        // Távoli asztal: a telefon nézi a PC-t
        AtomicInteger frames = new AtomicInteger();
        AtomicReference<int[]> info = new AtomicReference<>();
        CountDownLatch framesLatch = new CountDownLatch(3);
        RemoteDesktopService.Viewer viewer = new RemoteDesktopService.Viewer() {
            @Override public void onFrameInfo(int sw, int sh, int fw, int fh) { info.set(new int[]{sw, sh, fw, fh}); }
            @Override public void onTile(int x, int y, int w, int h, byte[] jpeg) { }
            @Override public void onFrameEnd() { frames.incrementAndGet(); framesLatch.countDown(); }
            @Override public void onStatus(String m) { }
            @Override public void onRemoteStopped(String r) { }
        };
        assertTrue(phone.remote.startViewing(viewer, 1000, 60, 30));
        assertTrue("Képkockák érkeznek", framesLatch.await(5, TimeUnit.SECONDS));
        assertArrayEquals(new int[]{2000, 1000, 1000, 500}, info.get());

        // Kattintás a képkocka (100,50) pontján → a képernyő (200,100) pontja
        phone.remote.sendMouseButton(RemoteDesktopService.BUTTON_LEFT, true, 100, 50);
        phone.remote.sendMouseButton(RemoteDesktopService.BUTTON_LEFT, false, 100, 50);
        assertTrue(clickLatch.await(5, TimeUnit.SECONDS));
        assertTrue(injected.toString(), injected.contains("btn1 down 200,100"));
        assertTrue(pc.remote.isHosting());

        phone.remote.stopViewing(viewer);
        end = System.currentTimeMillis() + 5_000;
        while (pc.remote.isHosting() && System.currentTimeMillis() < end) Thread.sleep(20);
        assertTrue("A megosztás leáll", !pc.remote.isHosting());

        // Bontás
        phone.node.disconnect();
        awaitState(pc.node, BridgeNode.State.IDLE);
        awaitState(phone.node, BridgeNode.State.IDLE);

        // Újrakapcsolódás – már párosítva
        assertTrue(phone.node.connectAsync(() -> SocketTransport.connect("127.0.0.1", port, TransportKind.NETWORK, 3000)));
        awaitState(pc.node, BridgeNode.State.CONNECTED);

        pc.node.shutdown();
        phone.node.shutdown();
    }
}
