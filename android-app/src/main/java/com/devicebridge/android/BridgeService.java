package com.devicebridge.android;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.devicebridge.common.BridgeNode;
import com.devicebridge.common.PeerInfo;

/**
 * Előtérszolgáltatás: amíg fut, a DeviceBridge a háttérben is fogadja a kapcsolatokat
 * (Wi-Fi/LAN, Bluetooth, USB) és kiszolgálja a párosított számítógépet.
 */
public class BridgeService extends Service {
    static final String ACTION_START = "com.devicebridge.action.START";
    static final String ACTION_STOP = "com.devicebridge.action.STOP";
    static final String ACTION_DISCONNECT = "com.devicebridge.action.DISCONNECT";
    static final String ACTION_PROJECTION = "com.devicebridge.action.PROJECTION";
    static final String ACTION_STOP_PROJECTION = "com.devicebridge.action.STOP_PROJECTION";
    static final String ACTION_STOP_RING = "com.devicebridge.action.STOP_RING";
    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";

    private static volatile BridgeService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private AndroidBridge bridge;
    private BridgeNode.Listener listener;
    private WifiManager.WifiLock wifiLock;
    private boolean projectionActive;

    static boolean isRunning() {
        return instance != null;
    }

    static void start(Context c) {
        c.startForegroundService(new Intent(c, BridgeService.class).setAction(ACTION_START));
    }

    static void stop(Context c) {
        BridgeService s = instance;
        if (s != null) s.shutdown();
    }

    static void refreshNotification(Context c) {
        BridgeService s = instance;
        if (s != null) s.main.post(s::updateNotification);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        bridge = AndroidBridge.get(this);
        listener = new BridgeNode.Listener() {
            @Override
            public void onStateChanged(BridgeNode.State state, PeerInfo peer) {
                main.post(BridgeService.this::updateNotification);
            }
        };
        bridge.node.addListener(listener);
        goForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null || intent.getAction() == null ? ACTION_START : intent.getAction();
        goForeground();
        switch (action) {
            case ACTION_STOP:
                shutdown();
                return START_NOT_STICKY;
            case ACTION_DISCONNECT:
                bridge.post(bridge.node::disconnect);
                break;
            case ACTION_STOP_RING:
                AndroidControlHandler.stopRing();
                break;
            case ACTION_PROJECTION:
                handleProjection(intent);
                break;
            case ACTION_STOP_PROJECTION:
                bridge.screen.release();
                projectionActive = false;
                goForeground();
                bridge.post(() -> bridge.remote.stopHost("A telefon leállította a képernyőmegosztást"));
                break;
            default:
                break;
        }
        if (!bridge.isRunning()) {
            bridge.post(bridge::startServices);
            acquireWifiLock();
        }
        BridgeTileService.requestUpdate(this);
        return START_STICKY;
    }

    private void handleProjection(Intent intent) {
        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data;
        if (Build.VERSION.SDK_INT >= 33) data = intent.getParcelableExtra(EXTRA_DATA, Intent.class);
        else data = intent.getParcelableExtra(EXTRA_DATA);
        if (data == null) return;
        projectionActive = true;
        goForeground(); // Android 14+: a mediaProjection típusú előtér-szolgáltatás előbb kell
        try {
            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            MediaProjection mp = mpm.getMediaProjection(code, data);
            if (mp == null) throw new IllegalStateException("Nem sikerült a képernyőmegosztás");
            bridge.screen.setProjection(mp, () -> main.post(() -> {
                projectionActive = false;
                goForeground();
                bridge.post(() -> bridge.remote.stopHost("A képernyőmegosztás leállt a telefonon"));
                bridge.fireUi();
            }));
            bridge.node.log("Képernyőmegosztás engedélyezve");
            bridge.post(bridge.remote::hostSourceReady);
        } catch (RuntimeException e) {
            projectionActive = false;
            goForeground();
            bridge.node.log("Képernyőmegosztás hiba: " + BridgeNode.describe(e));
            bridge.post(() -> bridge.remote.hostAccessDenied("Képernyőmegosztás hiba a telefonon: " + BridgeNode.describe(e)));
        }
        bridge.fireUi();
    }

    private void goForeground() {
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        if (projectionActive) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        try {
            startForeground(Notifications.ID_SERVICE, buildNotification(), type);
        } catch (RuntimeException e) {
            if (projectionActive) {
                projectionActive = false;
                try {
                    startForeground(Notifications.ID_SERVICE, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private Notification buildNotification() {
        BridgeNode.State st = bridge == null ? BridgeNode.State.IDLE : bridge.node.state();
        PeerInfo peer = bridge == null ? null : bridge.node.peer();
        String text;
        if (st == BridgeNode.State.CONNECTED && peer != null) text = "Kapcsolódva: " + peer.name + " (" + peer.kind.label() + ")";
        else if (st == BridgeNode.State.CONNECTING) text = "Kapcsolódás…";
        else text = "Kapcsolatra vár (Wi-Fi/LAN, Bluetooth, USB)";
        if (bridge != null && bridge.remote.isHosting()) text += " · képernyő megosztva";

        Notification.Builder b = new Notification.Builder(this, Notifications.CH_SERVICE)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle("DeviceBridge aktív")
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(Notifications.activity(this, 0, new Intent(this, MainActivity.class)));
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        if (st == BridgeNode.State.CONNECTED) {
            b.addAction(new Notification.Action.Builder(null, "Bontás",
                    Notifications.service(this, 11, new Intent(this, BridgeService.class).setAction(ACTION_DISCONNECT))).build());
        }
        if (projectionActive) {
            b.addAction(new Notification.Action.Builder(null, "Megosztás leállítása",
                    Notifications.service(this, 12, new Intent(this, BridgeService.class).setAction(ACTION_STOP_PROJECTION))).build());
        }
        b.addAction(new Notification.Action.Builder(null, "Kikapcsolás",
                Notifications.service(this, 13, new Intent(this, BridgeService.class).setAction(ACTION_STOP))).build());
        return b.build();
    }

    private void updateNotification() {
        if (instance != this) return;
        try {
            getSystemService(NotificationManager.class).notify(Notifications.ID_SERVICE, buildNotification());
        } catch (SecurityException ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private void acquireWifiLock() {
        try {
            if (wifiLock == null) {
                WifiManager wm = getSystemService(WifiManager.class);
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DeviceBridge");
                wifiLock.setReferenceCounted(false);
            }
            wifiLock.acquire();
        } catch (RuntimeException ignored) {
        }
    }

    private void shutdown() {
        bridge.post(bridge::stopServices);
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        projectionActive = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        instance = null;
        bridge.node.removeListener(listener);
        if (bridge.isRunning()) bridge.post(bridge::stopServices);
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        BridgeTileService.requestUpdate(this);
        bridge.fireUi();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
