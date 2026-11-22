package com.devicebridge.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Indítás a telefon bekapcsolásakor (ha a beállításokban engedélyezve van). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!AndroidBridge.get(context).startOnBoot()) return;
        try {
            BridgeService.start(context);
        } catch (RuntimeException ignored) {
        }
    }
}
