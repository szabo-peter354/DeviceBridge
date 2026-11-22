package com.devicebridge.android;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

/** Képernyőmegosztási engedély kérése a rendszertől (minden megosztásnál kötelező). */
public class ScreenCaptureActivity extends Activity {
    private static final int REQ = 42;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Notifications.cancel(this, Notifications.ID_SCREEN_REQUEST);
        if (savedInstanceState != null) return;
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ);
        } catch (RuntimeException e) {
            finish();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ) return;
        AndroidBridge bridge = AndroidBridge.get(this);
        if (resultCode == RESULT_OK && data != null) {
            Intent i = new Intent(this, BridgeService.class)
                    .setAction(BridgeService.ACTION_PROJECTION)
                    .putExtra(BridgeService.EXTRA_CODE, resultCode)
                    .putExtra(BridgeService.EXTRA_DATA, data);
            startForegroundService(i);
        } else {
            bridge.post(() -> bridge.remote.hostAccessDenied("A képernyőmegosztást elutasították a telefonon"));
        }
        finish();
    }
}
