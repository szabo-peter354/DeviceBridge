package com.devicebridge.android;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import com.devicebridge.common.BridgeNode;
import com.devicebridge.common.Discovery;
import com.devicebridge.common.LocalInfo;
import com.devicebridge.common.NetUtil;
import com.devicebridge.common.PeerInfo;
import com.devicebridge.common.Protocol;
import com.devicebridge.common.crypto.CryptoUtil;
import com.devicebridge.common.crypto.Identity;
import com.devicebridge.common.crypto.TrustStore;
import com.devicebridge.common.feature.ControlService;
import com.devicebridge.common.feature.DeviceStatus;
import com.devicebridge.common.feature.FileTransferService;
import com.devicebridge.common.feature.RemoteDesktopService;
import com.devicebridge.common.feature.SshInfo;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Az Android oldali mag (egyke): összeköti a közös motort az Android-specifikus részekkel.
 * Hálózati műveletet Androidon nem szabad a főszálon végezni – a felületről érkező
 * parancsokat a {@link #post(Runnable)} sorosítja egy háttérszálra.
 */
public final class AndroidBridge {

    public interface UiListener {
        void onBridgeChanged();
    }

    private static AndroidBridge instance;

    public static synchronized AndroidBridge get(Context c) {
        if (instance == null) instance = new AndroidBridge(c.getApplicationContext());
        return instance;
    }

    final Context ctx;
    final SharedPreferences prefs;
    final Handler main = new Handler(Looper.getMainLooper());
    final Identity identity;
    final TrustStore trust;
    final BridgeNode node;
    final ControlService control;
    final FileTransferService files;
    final RemoteDesktopService remote;
    final Discovery discovery;
    final AndroidScreenSource screen;
    final AndroidInputInjector injector;
    final AndroidSshServer ssh;
    final BluetoothServer bluetooth;
    private final ExecutorService sender = Executors.newSingleThreadExecutor(BridgeNode.daemon("bridge-ui-send"));
    private final List<UiListener> uiListeners = new CopyOnWriteArrayList<>();
    private volatile boolean servicesRunning;
    private WifiManager.MulticastLock multicastLock;
    private boolean uiUpdatePending;

    private AndroidBridge(Context ctx) {
        this.ctx = ctx;
        prefs = ctx.getSharedPreferences("devicebridge", Context.MODE_PRIVATE);
        if (!prefs.contains("ssh.password")) prefs.edit().putString("ssh.password", CryptoUtil.randomPassword(12)).apply();
        Notifications.createChannels(ctx);
        try {
            identity = Identity.loadOrCreate(new File(ctx.getFilesDir(), "identity.properties"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        trust = new TrustStore(new File(ctx.getFilesDir(), "trusted.properties"));
        node = new BridgeNode(this::localInfo, identity, trust, this::confirmPairing);
        control = new ControlService(node, new AndroidControlHandler(this));
        control.setStatusSupplier(this::status);
        control.setSshInfoSupplier(this::sshInfo);
        files = new FileTransferService(node, new AndroidFileSink(ctx));
        files.addListener(t -> {
            if (t.incoming && t.status() == FileTransferService.Status.DONE) {
                Notifications.show(ctx, "Fájl fogadva", t.name + "\n" + t.message(), null);
            }
            fireUi();
        });
        remote = new RemoteDesktopService(node);
        screen = new AndroidScreenSource(ctx);
        injector = new AndroidInputInjector(this);
        remote.setHostProvider(new HostProvider());
        discovery = new Discovery(this::localInfo, identity.fingerprint(), Protocol.TCP_PORT);
        ssh = new AndroidSshServer(ctx, this);
        bluetooth = new BluetoothServer(ctx, node);
        node.addListener(new BridgeNode.Listener() {
            @Override
            public void onStateChanged(BridgeNode.State state, PeerInfo peer) {
                fireUi();
                BridgeTileService.requestUpdate(ctx);
            }

            @Override
            public void onLog(String line) {
                fireUi();
            }
        });
    }

    // ------------------------------------------------------------------ általános

    /** Parancs futtatása háttérszálon, sorrendtartóan (pl. gombnyomásra küldés). */
    public void post(Runnable r) {
        sender.execute(() -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                node.log("Hiba: " + BridgeNode.describe(e));
            }
        });
    }

    public void addUiListener(UiListener l) { uiListeners.add(l); }
    public void removeUiListener(UiListener l) { uiListeners.remove(l); }

    /** A felület frissítése a főszálon (összevonva, max. ~5/mp). */
    void fireUi() {
        synchronized (this) {
            if (uiUpdatePending) return;
            uiUpdatePending = true;
        }
        main.postDelayed(() -> {
            synchronized (AndroidBridge.this) { uiUpdatePending = false; }
            for (UiListener l : uiListeners) l.onBridgeChanged();
        }, 200);
    }

    boolean isRunning() {
        return servicesRunning;
    }

    String deviceName() {
        String n = prefs.getString("device.name", "");
        if (n != null && !n.trim().isEmpty()) return n.trim();
        String sys = Settings.Global.getString(ctx.getContentResolver(), Settings.Global.DEVICE_NAME);
        if (sys != null && !sys.isEmpty()) return sys;
        return Build.MANUFACTURER + " " + Build.MODEL;
    }

    LocalInfo localInfo() {
        return new LocalInfo(deviceName(), Protocol.DEVICE_TYPE_ANDROID);
    }

    boolean sshEnabled() { return prefs.getBoolean("ssh.enabled", false); }
    String sshUser() { return "bridge"; }
    String sshPassword() { return prefs.getString("ssh.password", ""); }
    boolean remoteControlAllowed() { return prefs.getBoolean("remote.allow", true); }
    boolean startOnBoot() { return prefs.getBoolean("boot.start", false); }

    private DeviceStatus status() {
        BatteryManager bm = ctx.getSystemService(BatteryManager.class);
        int level = bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        boolean charging = bm != null && bm.isCharging();
        return new DeviceStatus(Build.MANUFACTURER + " " + Build.MODEL, "Android " + Build.VERSION.RELEASE, level, charging);
    }

    private SshInfo sshInfo() {
        if (!ssh.isRunning()) return SshInfo.disabled();
        List<String> eps = new ArrayList<>();
        for (InetAddress a : NetUtil.localIPv4Addresses()) eps.add(a.getHostAddress() + ":" + Protocol.SSH_PORT);
        return new SshInfo(true, sshUser(), sshPassword(), ssh.hostKeyFingerprint(), eps);
    }

    // ------------------------------------------------------------------ szolgáltatások

    /** Háttérszálon hívandó. */
    synchronized void startServices() {
        if (servicesRunning) return;
        servicesRunning = true;
        try {
            node.startTcpServer(Protocol.TCP_PORT);
        } catch (IOException e) {
            node.log("A TCP szerver nem indult: " + e.getMessage());
        }
        try {
            WifiManager wm = ctx.getSystemService(WifiManager.class);
            if (wm != null) {
                multicastLock = wm.createMulticastLock("DeviceBridge");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (RuntimeException ignored) {
        }
        try {
            discovery.startResponder();
        } catch (IOException e) {
            node.log("Az eszközkeresés nem indult: " + e.getMessage());
        }
        bluetooth.start();
        if (sshEnabled()) startSsh();
        node.log("A DeviceBridge fut – " + deviceName());
        fireUi();
    }

    /** Háttérszálon hívandó. */
    synchronized void stopServices() {
        if (!servicesRunning) return;
        servicesRunning = false;
        node.disconnect();
        node.stopTcpServer();
        discovery.stopResponder();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        bluetooth.stop();
        ssh.stop();
        screen.release();
        node.log("A DeviceBridge leállt");
        fireUi();
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
        control.sendSshInfo();
    }

    // ------------------------------------------------------------------ párosítás

    private CompletableFuture<Boolean> confirmPairing(PeerInfo peer, String code) {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        int id = PairingActivity.register(f, peer, code);
        Intent i = new Intent(ctx, PairingActivity.class)
                .putExtra(PairingActivity.EXTRA_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = Notifications.activity(ctx, 1000 + id, i);
        Notifications.show(ctx, Notifications.ID_PAIRING, "Párosítási kérés: " + peer.name,
                "Ellenőrző kód: " + code + " – koppints a jóváhagyáshoz", pi);
        if (BridgeApplication.isInForeground()) main.post(() -> ctx.startActivity(i));
        f.whenComplete((r, e) -> Notifications.cancel(ctx, Notifications.ID_PAIRING));
        return f;
    }

    // ------------------------------------------------------------------ képernyőmegosztás (gazda)

    private final class HostProvider implements RemoteDesktopService.HostProvider {
        @Override
        public RemoteDesktopService.ScreenSource screenSource() {
            return screen.hasProjection() ? screen : null;
        }

        @Override
        public RemoteDesktopService.InputInjector inputInjector() {
            return injector;
        }

        @Override
        public boolean isRemoteControlAllowed() {
            return remoteControlAllowed();
        }

        @Override
        public String onAccessRequired(PeerInfo viewer) {
            Intent i = new Intent(ctx, ScreenCaptureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            String who = viewer == null ? "A párosított eszköz" : viewer.name;
            Notifications.show(ctx, Notifications.ID_SCREEN_REQUEST, who + " szeretné látni a képernyődet",
                    "Koppints ide a képernyőmegosztás engedélyezéséhez", Notifications.activity(ctx, 3, i));
            if (BridgeApplication.isInForeground()) main.post(() -> ctx.startActivity(i));
            return "Várakozás a telefon engedélyére – koppints a telefonon megjelent értesítésre…";
        }

        @Override
        public void onHostStateChanged(boolean streaming, PeerInfo viewer) {
            Notifications.cancel(ctx, Notifications.ID_SCREEN_REQUEST);
            if (streaming && !InputAccessibilityService.isRunning()) {
                Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                Notifications.show(ctx, Notifications.ID_ACCESSIBILITY, "A képernyőd látható, de nem vezérelhető",
                        "A PC-ről történő vezérléshez kapcsold be: Akadálymentesítés → DeviceBridge távvezérlés.",
                        Notifications.activity(ctx, 5, i));
            }
            BridgeService.refreshNotification(ctx);
            fireUi();
        }
    }
}
