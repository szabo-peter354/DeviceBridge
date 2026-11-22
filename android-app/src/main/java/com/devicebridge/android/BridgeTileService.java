package com.devicebridge.android;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import com.devicebridge.common.BridgeNode;
import com.devicebridge.common.PeerInfo;

/** Gyorsbeállítás csempe: a lehúzható értesítési sávból kapcsolható be/ki a DeviceBridge. */
public class BridgeTileService extends TileService {

    static void requestUpdate(Context c) {
        try {
            TileService.requestListeningState(c, new ComponentName(c, BridgeTileService.class));
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onStartListening() {
        update();
    }

    @Override
    public void onClick() {
        if (BridgeService.isRunning()) {
            BridgeService.stop(this);
            update(false);
            return;
        }
        try {
            BridgeService.start(this);
            update(true);
        } catch (RuntimeException e) {
            // Ha a rendszer nem engedi a háttérből indítást, egy átmeneti ablakon keresztül indítjuk
            Intent i = new Intent(this, ServiceStarterActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE));
            } else {
                startActivityAndCollapseLegacy(i);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void startActivityAndCollapseLegacy(Intent i) {
        startActivityAndCollapse(i);
    }

    private void update() {
        update(BridgeService.isRunning());
    }

    private void update(boolean running) {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(running ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel("DeviceBridge");
        String sub = "Ki";
        if (running) {
            AndroidBridge b = AndroidBridge.get(this);
            PeerInfo p = b.node.peer();
            sub = b.node.state() == BridgeNode.State.CONNECTED && p != null ? p.name : "Be – vár";
        }
        t.setSubtitle(sub);
        t.updateTile();
    }
}
