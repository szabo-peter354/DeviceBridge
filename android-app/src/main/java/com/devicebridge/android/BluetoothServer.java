package com.devicebridge.android;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import com.devicebridge.common.BridgeNode;
import com.devicebridge.common.Protocol;
import com.devicebridge.common.transport.StreamTransport;
import com.devicebridge.common.transport.TransportKind;

import java.io.IOException;
import java.util.UUID;

/**
 * Bluetooth RFCOMM szerver a szabványos SPP UUID-val. A PC (Windows) a párosítás után
 * létrehoz hozzá egy virtuális COM portot, ezen keresztül kapcsolódik.
 */
final class BluetoothServer {
    private final Context ctx;
    private final BridgeNode node;
    private volatile boolean running;
    private volatile BluetoothServerSocket server;
    private Thread thread;

    BluetoothServer(Context ctx, BridgeNode node) {
        this.ctx = ctx;
        this.node = node;
    }

    static boolean hasPermission(Context ctx) {
        return Build.VERSION.SDK_INT < 31
                || ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    synchronized void start() {
        if (running) return;
        if (!hasPermission(ctx)) {
            node.log("Bluetooth: nincs engedély (Engedélyek gomb) – Bluetooth kapcsolat nem fogadható");
            return;
        }
        BluetoothManager bm = ctx.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null) {
            node.log("Bluetooth: az eszköz nem támogatja");
            return;
        }
        running = true;
        thread = new Thread(() -> loop(adapter), "bridge-bluetooth");
        thread.setDaemon(true);
        thread.start();
    }

    @SuppressWarnings("MissingPermission")
    private void loop(BluetoothAdapter adapter) {
        boolean announced = false;
        boolean wasDisabled = false;
        String lastError = null;
        int failures = 0;
        while (running) {
            BluetoothServerSocket ss = null;
            try {
                if (!adapter.isEnabled()) {
                    if (!wasDisabled) {
                        node.log("Bluetooth: ki van kapcsolva – bekapcsolásra vár");
                        wasDisabled = true;
                        announced = false;
                    }
                    Thread.sleep(3000);
                    continue;
                }
                wasDisabled = false;
                ss = adapter.listenUsingRfcommWithServiceRecord("DeviceBridge", UUID.fromString(Protocol.SPP_UUID));
                server = ss;
                if (!announced) {
                    node.log("Bluetooth: kapcsolatra vár (SPP)");
                    announced = true;
                }
                BluetoothSocket s = ss.accept();
                failures = 0;
                lastError = null;
                String name;
                try {
                    name = s.getRemoteDevice().getName();
                } catch (SecurityException e) {
                    name = s.getRemoteDevice().getAddress();
                }
                node.acceptIncoming(new StreamTransport(s.getInputStream(), s.getOutputStream(), s,
                        "Bluetooth: " + (name == null ? "?" : name), TransportKind.BLUETOOTH));
            } catch (InterruptedException e) {
                break;
            } catch (IOException | RuntimeException e) {
                if (!running) break;
                // Ugyanazt a hibát csak egyszer naplózzuk, hogy a napló ne teljen meg
                String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                if (!msg.equals(lastError)) {
                    node.log("Bluetooth hiba (újrapróbálás): " + e.getMessage());
                    lastError = msg;
                    announced = false;
                }
                failures++;
                long delay = Math.min(15_000, 1000L * failures);
                try { Thread.sleep(delay); } catch (InterruptedException ie) { break; }
            } finally {
                // Kapcsolat elfogadása vagy hiba után a régi szerver socketet mindig lezárjuk és újat nyitunk
                if (ss != null) {
                    try { ss.close(); } catch (IOException ignored) { }
                }
                server = null;
            }
        }
    }

    synchronized void stop() {
        running = false;
        BluetoothServerSocket ss = server;
        if (ss != null) {
            try { ss.close(); } catch (IOException ignored) { }
        }
        server = null;
        if (thread != null) thread.interrupt();
        thread = null;
    }
}
