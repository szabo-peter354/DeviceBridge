package com.devicebridge.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Html;

import com.devicebridge.common.PeerInfo;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Párosítás jóváhagyása: ugyanazt a 6 jegyű kódot kell látni mindkét eszközön. */
public class PairingActivity extends Activity {
    static final String EXTRA_ID = "pairing_id";

    private static final class Request {
        final CompletableFuture<Boolean> future;
        final PeerInfo peer;
        final String code;

        Request(CompletableFuture<Boolean> future, PeerInfo peer, String code) {
            this.future = future;
            this.peer = peer;
            this.code = code;
        }
    }

    private static final Map<Integer, Request> REQUESTS = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT = new AtomicInteger();

    static int register(CompletableFuture<Boolean> f, PeerInfo peer, String code) {
        int id = NEXT.incrementAndGet();
        REQUESTS.put(id, new Request(f, peer, code));
        f.whenComplete((r, e) -> REQUESTS.remove(id));
        return id;
    }

    private AlertDialog dialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Request req = REQUESTS.get(getIntent().getIntExtra(EXTRA_ID, -1));
        if (req == null || req.future.isDone()) {
            finish();
            return;
        }
        String html = "<b>" + Html.escapeHtml(req.peer.name) + "</b> (" + Html.escapeHtml(req.peer.transportDescription) + ")"
                + " szeretne párosítani.<br><br>Ujjlenyomat: " + req.peer.fingerprint
                + "<br><br>Ellenőrizd, hogy a másik eszközön is ez a kód látható:<br><br>"
                + "<big><big><b>" + req.code + "</b></big></big>";
        dialog = new AlertDialog.Builder(this)
                .setTitle("Párosítás")
                .setMessage(Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY))
                .setPositiveButton("Párosítás", (d, w) -> req.future.complete(true))
                .setNegativeButton("Elutasítás", (d, w) -> req.future.complete(false))
                .setOnDismissListener(d -> {
                    req.future.complete(false);
                    finish();
                })
                .create();
        dialog.show();
        req.future.whenComplete((r, e) -> runOnUiThread(() -> {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        }));
    }

    @Override
    protected void onDestroy() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        super.onDestroy();
    }
}
