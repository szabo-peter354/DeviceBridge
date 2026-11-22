package com.devicebridge.android;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** "Megosztás" menü célpont: fájlok vagy szöveg/link küldése a kapcsolódott számítógépre. */
public class ShareActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AndroidBridge bridge = AndroidBridge.get(this);
        if (!bridge.node.isConnected()) {
            Toast.makeText(this, "DeviceBridge: nincs kapcsolódott számítógép", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        Intent in = getIntent();
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(in.getAction())) {
            Uri u = Build.VERSION.SDK_INT >= 33 ? in.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class) : legacyUri(in);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(in.getAction())) {
            ArrayList<Uri> list = Build.VERSION.SDK_INT >= 33 ? in.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class) : legacyUris(in);
            if (list != null) uris.addAll(list);
        }
        if (!uris.isEmpty()) {
            int n = 0;
            for (Uri u : uris) if (FileSender.send(this, bridge, u)) n++;
            Toast.makeText(this, "DeviceBridge: " + n + " fájl küldése…", Toast.LENGTH_SHORT).show();
        } else {
            CharSequence text = in.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                String s = text.toString().trim();
                boolean isUrl = (s.startsWith("http://") || s.startsWith("https://")) && !s.contains(" ");
                bridge.post(() -> {
                    if (isUrl) bridge.control.openUrl(s);
                    else bridge.control.sendClipboard(s);
                });
                Toast.makeText(this, isUrl ? "Link megnyitása a számítógépen" : "Szöveg a számítógép vágólapjára", Toast.LENGTH_SHORT).show();
            }
        }
        finish();
    }

    @SuppressWarnings("deprecation")
    private static Uri legacyUri(Intent in) {
        return in.getParcelableExtra(Intent.EXTRA_STREAM);
    }

    @SuppressWarnings("deprecation")
    private static ArrayList<Uri> legacyUris(Intent in) {
        return in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
    }
}
