package com.devicebridge.android;

import android.app.Activity;
import android.os.Bundle;

/** Átmeneti, láthatatlan ablak, amely előtérből indítja a szolgáltatást (csempe tartalék út). */
public class ServiceStarterActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            BridgeService.start(this);
        } catch (RuntimeException ignored) {
        }
        finish();
    }
}
