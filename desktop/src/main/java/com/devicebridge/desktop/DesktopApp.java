package com.devicebridge.desktop;

import com.devicebridge.common.BridgeNode;
import com.devicebridge.common.Discovery;
import com.devicebridge.common.LocalInfo;
import com.devicebridge.common.NetUtil;
import com.devicebridge.common.PeerInfo;
import com.devicebridge.common.Protocol;
import com.devicebridge.common.crypto.Identity;
import com.devicebridge.common.crypto.TrustStore;
import com.devicebridge.common.feature.ControlService;
import com.devicebridge.common.feature.DeviceStatus;
import com.devicebridge.common.feature.FileTransferService;
import com.devicebridge.common.feature.RemoteDesktopService;
import com.devicebridge.common.feature.SshInfo;
import com.devicebridge.common.transport.SocketTransport;
import com.devicebridge.common.transport.TransportKind;

import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** A PC alkalmazás magja: összeköti a közös motort a Windows/Linux specifikus részekkel. */
public final class DesktopApp {

    public interface Ui {
        void notifyUser(String title, String message);

        CompletableFuture<Boolean> confirmPairing(PeerInfo peer, String code);

        void peerInfoChanged();
    }

    final File dataDir;
    final DesktopSettings settings;
    final Identity identity;
    final TrustStore trust;
    final BridgeNode node;
    final ControlService control;
    final FileTransferService files;
    final RemoteDesktopService remote;
    final Discovery discovery;
    final DesktopSshServer ssh;
    final AdbHelper adb = new AdbHelper();
    private final SystemActions systemActions = new SystemActions();
    private volatile Ui ui;
    private DesktopInputInjector injector;

    DesktopApp(File dataDir) throws IOException {
        this.dataDir = dataDir;
        if (!dataDir.isDirectory() && !dataDir.mkdirs()) throw new IOException("Nem hozható létre: " + dataDir);
        settings = new DesktopSettings(dataDir);
        identity = Identity.loadOrCreate(new File(dataDir, "identity.properties"));
        trust = new TrustStore(new File(dataDir, "trusted.properties"));
        node = new BridgeNode(this::localInfo, identity, trust, (peer, code) -> {
            Ui u = ui;
            return u == null ? CompletableFuture.completedFuture(false) : u.confirmPairing(peer, code);
        });
        node.addListener(new BridgeNode.Listener() {
            @Override
            public void onLog(String line) {
                FileLog.write(line);
            }

            @Override
            public void onStateChanged(BridgeNode.State state, PeerInfo peer) {
                onStateChangedForBluetooth(state, peer);
            }
        });
        control = new ControlService(node, new ControlHandler());
        control.setStatusSupplier(this::status);
        control.setSshInfoSupplier(this::sshInfo);
        files = new FileTransferService(node, new DesktopFileSink(settings::downloadDir));
        files.addListener(t -> {
            if (t.incoming && t.status() == FileTransferService.Status.DONE) notifyUser("Fájl fogadva", t.name);
        });
        remote = new RemoteDesktopService(node);
        remote.setHostProvider(new HostProvider());
        discovery = new Discovery(this::localInfo, identity.fingerprint(), Protocol.TCP_PORT);
        ssh = new DesktopSshServer(dataDir, settings);
    }

    void setUi(Ui ui) {
        this.ui = ui;
    }

    SystemActions systemActions() {
        return systemActions;
    }

    void start() {
        try {
            node.startTcpServer(Protocol.TCP_PORT);
        } catch (IOException e) {
            node.log("A TCP szerver nem indult (" + Protocol.TCP_PORT + "): " + e.getMessage());
        }
        try {
            discovery.startResponder();
        } catch (IOException e) {
            node.log("Az eszközkeresés nem indult: " + e.getMessage());
        }
        if (settings.sshEnabled()) startSsh();
    }

    void shutdown() {
        ssh.stop();
        discovery.stopResponder();
        node.shutdown();
    }

    void startSsh() {
        try {
            ssh.start();
            node.log("SSH szerver fut a " + Protocol.SSH_PORT + "-es porton (csak helyi hálózat)");
        } catch (Exception e) {
            node.log("Az SSH szerver nem indult: " + BridgeNode.describe(e));
        }
        control.sendSshInfo();
    }

    void stopSsh() {
        ssh.stop();
        node.log("SSH szerver leállítva");
        control.sendSshInfo();
    }

    LocalInfo localInfo() {
        return new LocalInfo(settings.deviceName(), Protocol.DEVICE_TYPE_DESKTOP);
    }

    private DeviceStatus status() {
        String os = System.getProperty("os.name") + " " + System.getProperty("os.version");
        return new DeviceStatus(settings.deviceName(), os, -1, false);
    }

    private SshInfo sshInfo() {
        if (!ssh.isRunning()) return SshInfo.disabled();
        List<String> eps = new ArrayList<>();
        PeerInfo p = node.peer();
        if (p != null && p.kind == TransportKind.USB) eps.add("127.0.0.1:" + Protocol.USB_SSH_REVERSE_PORT);
        for (InetAddress a : NetUtil.localIPv4Addresses()) eps.add(a.getHostAddress() + ":" + Protocol.SSH_PORT);
        return new SshInfo(true, settings.sshUser(), settings.sshPassword(), ssh.hostKeyFingerprint(), eps);
    }

    void notifyUser(String title, String message) {
        Ui u = ui;
        if (u != null) u.notifyUser(title, message);
    }

    // ------------------------------------------------------------------ kapcsolódás

    void connectNetwork(String host, int port) {
        node.connectAsync(() -> SocketTransport.connect(host, port, TransportKind.NETWORK, 8000));
    }

    void connectUsb(AdbHelper.Device device) {
        node.connectAsync(() -> {
            adb.forward(device.serial, Protocol.USB_BRIDGE_FORWARD_PORT, Protocol.TCP_PORT);
            try {
                adb.forward(device.serial, Protocol.USB_SSH_FORWARD_PORT, Protocol.SSH_PORT);
                adb.reverse(device.serial, Protocol.USB_SSH_REVERSE_PORT, Protocol.SSH_PORT);
            } catch (IOException e) {
                node.log("SSH port továbbítás USB-n nem sikerült: " + e.getMessage());
            }
            Socket s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", Protocol.USB_BRIDGE_FORWARD_PORT), 5000);
            return new SocketTransport(s, TransportKind.USB, "USB: " + device.description);
        });
    }

    // Bluetooth automatikus újrakapcsolódás: ha egy már létrejött Bluetooth kapcsolat váratlanul
    // megszakad (hatótáv, alvás, zavar), a PC magától újra próbálkozik ugyanazon a COM porton.
    private static final int BT_RECONNECT_ATTEMPTS = 8;
    private volatile BridgeNode.TransportOpener btPort;
    private volatile boolean btEverConnected;
    private volatile int btAttempts;

    void connectSerial(SerialLink.PortInfo port) {
        connectBluetooth(() -> SerialLink.open(port));
    }

    /** Közvetlen Bluetooth (RFCOMM) kapcsolat a párosított telefonhoz – COM port nem kell. */
    void connectRfcomm(BluetoothRfcomm.Device device) {
        connectBluetooth(() -> BluetoothRfcomm.connect(device));
    }

    private void connectBluetooth(BridgeNode.TransportOpener opener) {
        btPort = opener;
        btEverConnected = false;
        btAttempts = 0;
        node.connectAsync(opener);
    }

    /** Felhasználói bontás: kikapcsolja az automatikus újrakapcsolódást is. */
    void disconnect() {
        btPort = null;
        node.disconnect();
    }

    private void onStateChangedForBluetooth(BridgeNode.State state, PeerInfo peer) {
        if (state == BridgeNode.State.CONNECTED) {
            if (peer != null && peer.kind == TransportKind.BLUETOOTH) {
                btEverConnected = true;
                btAttempts = 0;
            } else {
                btPort = null; // más úton kapcsolódtunk – nincs mit újraépíteni
            }
            return;
        }
        if (state != BridgeNode.State.IDLE) return;
        BridgeNode.TransportOpener port = btPort;
        if (port == null || !btEverConnected) return;
        if (node.lastDisconnectWasClean()) {
            btPort = null;
            return;
        }
        int attempt = ++btAttempts;
        if (attempt > BT_RECONNECT_ATTEMPTS) {
            node.log("Bluetooth: az újrakapcsolódás nem sikerült – kapcsolódj újra kézzel");
            notifyUser("Bluetooth", "A kapcsolat megszakadt, az újrakapcsolódás nem sikerült.");
            btPort = null;
            return;
        }
        long delay = Math.min(30, 3L * attempt);
        node.log("Bluetooth: újrakapcsolódás " + delay + " mp múlva ("
                + attempt + "/" + BT_RECONNECT_ATTEMPTS + ")");
        try {
            node.scheduler().schedule(() -> {
                if (btPort != port || node.state() != BridgeNode.State.IDLE) return;
                node.connectAsync(port);
            }, delay, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
        }
    }

    // ------------------------------------------------------------------ SSH a telefonra

    static final class SshTarget {
        final String host;
        final int port;
        final SshInfo info;

        SshTarget(String host, int port, SshInfo info) {
            this.host = host;
            this.port = port;
            this.info = info;
        }

        String command() {
            return "ssh -p " + port + " " + info.user + "@" + host;
        }
    }

    /** Megkeresi a telefon elérhető SSH végpontját (USB továbbítás vagy LAN/PAN cím). */
    SshTarget findPeerSsh() throws IOException {
        SshInfo info = control.peerSshInfo();
        if (info == null || !info.enabled) {
            throw new IOException("A telefonon nincs bekapcsolva az SSH szerver (DeviceBridge → Beállítások).");
        }
        List<String> eps = new ArrayList<>();
        PeerInfo p = node.peer();
        if (p != null && p.kind == TransportKind.USB) eps.add("127.0.0.1:" + Protocol.USB_SSH_FORWARD_PORT);
        eps.addAll(info.endpoints);
        for (String ep : eps) {
            String[] hp = NetUtil.splitHostPort(ep, Protocol.SSH_PORT);
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(hp[0], Integer.parseInt(hp[1])), 1500);
                return new SshTarget(hp[0], Integer.parseInt(hp[1]), info);
            } catch (IOException | NumberFormatException ignored) {
            }
        }
        throw new IOException("A telefon SSH szervere nem érhető el. SSH-hoz azonos helyi hálózat "
                + "(Wi-Fi / LAN / Bluetooth PAN) vagy USB kapcsolat szükséges.");
    }

    /** Rendszer terminált nyit egy ssh paranccsal; a jelszót a vágólapra másolja. */
    void launchSshTerminal(SshTarget t) throws IOException {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(t.info.password), null);
        String userHost = t.info.user + "@" + t.host;
        if (SystemActions.isWindows()) {
            // A terminálablak a felhasználó mappájában nyíljon, ne a program (exe) mappájában
            new ProcessBuilder("cmd.exe", "/c", "start", "DeviceBridge SSH", "ssh", "-p", String.valueOf(t.port),
                    "-o", "StrictHostKeyChecking=accept-new", userHost)
                    .directory(new File(System.getProperty("user.home"))).start();
        } else if (SystemActions.isMac()) {
            new ProcessBuilder("open", "-a", "Terminal").start();
        } else {
            new ProcessBuilder("x-terminal-emulator", "-e", "ssh", "-p", String.valueOf(t.port), userHost).start();
        }
    }

    // ------------------------------------------------------------------ automatikus indítás (Windows)

    private static File startupFile() {
        String appData = System.getenv("APPDATA");
        if (appData == null) return null;
        return new File(appData, "Microsoft\\Windows\\Start Menu\\Programs\\Startup\\DeviceBridge.bat");
    }

    boolean isAutostartEnabled() {
        File f = startupFile();
        return f != null && f.isFile();
    }

    void setAutostart(boolean enabled) throws IOException {
        File f = startupFile();
        if (f == null || !SystemActions.isWindows()) throw new IOException("Az automatikus indítás csak Windowson támogatott");
        if (!enabled) {
            if (f.exists() && !f.delete()) throw new IOException("Nem törölhető: " + f);
            return;
        }
        File jar;
        try {
            jar = new File(DesktopApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            throw new IOException("A program helye nem állapítható meg");
        }
        String line;
        File bat = new File(jar.getParentFile().getParentFile(), "bin\\DeviceBridge.bat");
        String exe = System.getProperty("jpackage.app-path"); // a hordozható DeviceBridge.exe állítja be
        if (exe != null && new File(exe).isFile()) {
            line = "start \"\" \"" + exe + "\" --minimized";
        } else if (jar.getName().equals("DeviceBridge-desktop.jar")) {
            String javaw = System.getProperty("java.home") + "\\bin\\javaw.exe";
            line = "start \"\" \"" + javaw + "\" -jar \"" + jar.getAbsolutePath() + "\" --minimized";
        } else if (bat.isFile()) {
            line = "start \"\" /min \"" + bat.getAbsolutePath() + "\" --minimized";
        } else {
            throw new IOException("Az automatikus indításhoz a DeviceBridge-desktop.jar-ból indítsd a programot");
        }
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(("@echo off\r\n" + line + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    // ------------------------------------------------------------------ funkció kezelők

    private final class ControlHandler implements ControlService.Handler {
        @Override
        public void onClipboard(String text) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
            notifyUser("Vágólap frissítve", text.length() > 80 ? text.substring(0, 80) + "…" : text);
        }

        @Override
        public void onOpenUrl(String url) {
            systemActions.openUrl(url);
            notifyUser("Link megnyitva", url);
        }

        @Override
        public void onRing(boolean start) {
            systemActions.ring(start);
            if (start) notifyUser("DeviceBridge", "A telefonról keresik ezt a számítógépet!");
        }

        @Override
        public void onLock() {
            systemActions.lock();
        }

        @Override
        public void onMedia(int action) {
            systemActions.media(action);
        }

        @Override
        public void onNotify(String title, String text) {
            notifyUser(title.isEmpty() ? "Üzenet" : title, text);
        }

        @Override
        public void onPeerStatus(DeviceStatus status) {
            Ui u = ui;
            if (u != null) u.peerInfoChanged();
        }

        @Override
        public void onPeerSshInfo(SshInfo info) {
            Ui u = ui;
            if (u != null) u.peerInfoChanged();
        }
    }

    private final class HostProvider implements RemoteDesktopService.HostProvider {
        @Override
        public RemoteDesktopService.ScreenSource screenSource() {
            return GraphicsEnvironment.isHeadless() ? null : new DesktopScreenSource();
        }

        @Override
        public synchronized RemoteDesktopService.InputInjector inputInjector() {
            if (injector == null) {
                try {
                    injector = new DesktopInputInjector(DesktopApp.this);
                } catch (AWTException e) {
                    node.log("Bemenet szimuláció nem érhető el: " + e.getMessage());
                }
            }
            return injector;
        }

        @Override
        public boolean isRemoteControlAllowed() {
            return settings.allowRemoteControl();
        }

        @Override
        public String onAccessRequired(PeerInfo viewer) {
            return "A képernyő nem érhető el";
        }

        @Override
        public void onHostStateChanged(boolean streaming, PeerInfo viewer) {
            if (streaming) notifyUser("Távoli asztal", (viewer == null ? "A párosított eszköz" : viewer.name) + " látja és vezérelheti a képernyődet");
            Ui u = ui;
            if (u != null) u.peerInfoChanged();
        }
    }
}
