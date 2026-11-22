package com.devicebridge.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.devicebridge.common.BridgeNode;
import com.devicebridge.common.Discovery;
import com.devicebridge.common.NetUtil;
import com.devicebridge.common.PeerInfo;
import com.devicebridge.common.Protocol;
import com.devicebridge.common.crypto.CryptoUtil;
import com.devicebridge.common.crypto.TrustStore;
import com.devicebridge.common.feature.ControlService;
import com.devicebridge.common.feature.DeviceStatus;
import com.devicebridge.common.feature.FileTransferService;
import com.devicebridge.common.feature.SshInfo;
import com.devicebridge.common.transport.SocketTransport;
import com.devicebridge.common.transport.TransportKind;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/** Főképernyő: állapot, kapcsolódás, vezérlés, beállítások. */
public class MainActivity extends Activity implements AndroidBridge.UiListener {
    private static final int REQ_FILES = 1;
    private static final int REQ_PERMS = 2;

    private AndroidBridge bridge;
    private TextView statusText;
    private TextView localInfoText;
    private TextView peerStatusText;
    private TextView transfersText;
    private TextView logText;
    private Switch serviceSwitch;
    private LinearLayout foundList;
    private LinearLayout controlSection;
    private EditText hostInput;
    private Button shareScreenButton;
    private Button ringButton;
    private Button accessibilityButton;
    private boolean ringing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bridge = AndroidBridge.get(this);

        statusText = findViewById(R.id.statusText);
        localInfoText = findViewById(R.id.localInfoText);
        peerStatusText = findViewById(R.id.peerStatusText);
        transfersText = findViewById(R.id.transfersText);
        logText = findViewById(R.id.logText);
        serviceSwitch = findViewById(R.id.serviceSwitch);
        foundList = findViewById(R.id.foundList);
        controlSection = findViewById(R.id.controlSection);
        hostInput = findViewById(R.id.hostInput);
        shareScreenButton = findViewById(R.id.shareScreenButton);
        ringButton = findViewById(R.id.ringButton);
        accessibilityButton = findViewById(R.id.accessibilityButton);

        serviceSwitch.setOnClickListener(v -> {
            if (serviceSwitch.isChecked()) BridgeService.start(this);
            else BridgeService.stop(this);
            bridge.main.postDelayed(this::refresh, 400);
        });

        findViewById(R.id.scanButton).setOnClickListener(v -> scan((Button) v));
        findViewById(R.id.connectButton).setOnClickListener(v -> connectManual());

        findViewById(R.id.viewScreenButton).setOnClickListener(v -> startActivity(new Intent(this, RemoteViewActivity.class)));
        shareScreenButton.setOnClickListener(v -> {
            if (bridge.screen.hasProjection()) {
                startService(new Intent(this, BridgeService.class).setAction(BridgeService.ACTION_STOP_PROJECTION));
            } else {
                startActivity(new Intent(this, ScreenCaptureActivity.class));
            }
        });
        findViewById(R.id.sendFileButton).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, REQ_FILES);
        });
        findViewById(R.id.clipboardButton).setOnClickListener(v -> sendClipboard());
        findViewById(R.id.openUrlButton).setOnClickListener(v -> prompt("Link megnyitása a számítógépen", "https://", InputType.TYPE_TEXT_VARIATION_URI,
                text -> bridge.post(() -> bridge.control.openUrl(text))));
        findViewById(R.id.messageButton).setOnClickListener(v -> prompt("Üzenet a számítógépre", "", InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                text -> bridge.post(() -> bridge.control.notify(bridge.deviceName(), text))));
        findViewById(R.id.lockButton).setOnClickListener(v -> bridge.post(bridge.control::lock));
        ringButton.setOnClickListener(v -> {
            ringing = !ringing;
            boolean r = ringing;
            bridge.post(() -> bridge.control.ring(r));
            ringButton.setText(ringing ? "Csörgés leállítása" : "PC csörgetése");
        });
        media(R.id.prevButton, ControlService.MEDIA_PREVIOUS);
        media(R.id.playButton, ControlService.MEDIA_PLAY_PAUSE);
        media(R.id.nextButton, ControlService.MEDIA_NEXT);
        media(R.id.volDownButton, ControlService.VOLUME_DOWN);
        media(R.id.volUpButton, ControlService.VOLUME_UP);
        media(R.id.muteButton, ControlService.VOLUME_MUTE);
        findViewById(R.id.sshButton).setOnClickListener(v -> startActivity(new Intent(this, TerminalActivity.class)));
        findViewById(R.id.disconnectButton).setOnClickListener(v -> bridge.post(bridge.node::disconnect));

        findViewById(R.id.settingsButton).setOnClickListener(v -> showSettings());
        findViewById(R.id.pairedButton).setOnClickListener(v -> showPaired());
        accessibilityButton.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.permissionsButton).setOnClickListener(v -> requestPerms(true));

        // Első indításkor: engedélyek + szolgáltatás bekapcsolása
        if (!bridge.prefs.getBoolean("first.run.done", false)) {
            bridge.prefs.edit().putBoolean("first.run.done", true).apply();
            requestPerms(false);
            BridgeService.start(this);
        }
    }

    private void media(int id, int action) {
        findViewById(id).setOnClickListener(v -> bridge.post(() -> bridge.control.media(action)));
    }

    @Override
    protected void onStart() {
        super.onStart();
        bridge.addUiListener(this);
        refresh();
    }

    @Override
    protected void onStop() {
        bridge.removeUiListener(this);
        super.onStop();
    }

    @Override
    public void onBridgeChanged() {
        refresh();
    }

    // ------------------------------------------------------------------ frissítés

    private void refresh() {
        boolean running = BridgeService.isRunning();
        serviceSwitch.setChecked(running);
        BridgeNode.State st = bridge.node.state();
        PeerInfo peer = bridge.node.peer();
        String status;
        int color;
        if (!running) {
            status = "● Kikapcsolva";
            color = Color.GRAY;
        } else if (st == BridgeNode.State.CONNECTED && peer != null) {
            status = "● Kapcsolódva: " + peer.name;
            color = getColor(R.color.ok);
        } else if (st == BridgeNode.State.CONNECTING) {
            status = "● Kapcsolódás…";
            color = getColor(R.color.warn);
        } else {
            status = "● Kapcsolatra vár";
            color = getColor(R.color.accent);
        }
        statusText.setText(status);
        statusText.setTextColor(color);

        List<String> ips = new ArrayList<>();
        for (InetAddress a : NetUtil.localIPv4Addresses()) ips.add(a.getHostAddress());
        localInfoText.setText("Név: " + bridge.deviceName()
                + "\nUjjlenyomat: " + bridge.identity.fingerprint()
                + "\nIP: " + (ips.isEmpty() ? "–" : String.join(", ", ips)) + "  ·  port " + Protocol.TCP_PORT
                + (bridge.ssh.isRunning() ? "\nSSH: port " + Protocol.SSH_PORT + ", felhasználó: " + bridge.sshUser() : ""));

        boolean connected = st == BridgeNode.State.CONNECTED;
        controlSection.setVisibility(connected ? View.VISIBLE : View.GONE);
        if (connected && peer != null) {
            StringBuilder sb = new StringBuilder(peer.name + " – " + peer.transportDescription);
            DeviceStatus ds = bridge.control.peerStatus();
            if (ds != null) sb.append("\n").append(ds.os);
            SshInfo si = bridge.control.peerSshInfo();
            sb.append("\nSSH a PC-n: ").append(si != null && si.enabled ? "bekapcsolva" : "kikapcsolva");
            if (bridge.remote.isHosting()) sb.append("\nA PC most látja a képernyődet.");
            peerStatusText.setText(sb.toString());
        }
        shareScreenButton.setText(bridge.screen.hasProjection() ? "Megosztás leállítása" : "Képernyőm megosztása");
        accessibilityButton.setText(InputAccessibilityService.isRunning() ? "Távvezérlés: BE ✓" : "Távvezérlés engedélyezése");

        StringBuilder tr = new StringBuilder();
        int n = 0;
        for (FileTransferService.Transfer t : bridge.files.history()) {
            if (n++ >= 6) break;
            tr.append(t.incoming ? "⬇ " : "⬆ ").append(t.name).append(" – ");
            switch (t.status()) {
                case RUNNING: tr.append(t.percent()).append('%'); break;
                case DONE: tr.append("kész"); break;
                case WAITING: tr.append("várakozik"); break;
                case CANCELLED: tr.append("megszakítva"); break;
                default: tr.append("hiba: ").append(t.message());
            }
            tr.append('\n');
        }
        transfersText.setText(tr.length() == 0 ? "Még nincs átvitel." : tr.toString().trim());

        List<String> log = bridge.node.logHistory();
        StringBuilder lb = new StringBuilder();
        for (int i = Math.max(0, log.size() - 25); i < log.size(); i++) lb.append(log.get(i)).append('\n');
        logText.setText(lb.toString().trim());
    }

    // ------------------------------------------------------------------ kapcsolódás

    private void ensureService() {
        if (!BridgeService.isRunning()) BridgeService.start(this);
    }

    private void scan(Button b) {
        b.setEnabled(false);
        b.setText("Keresés…");
        foundList.removeAllViews();
        String fp = bridge.identity.fingerprint();
        new Thread(() -> {
            List<Discovery.Device> found = Discovery.scan(2500, fp);
            runOnUiThread(() -> {
                b.setEnabled(true);
                b.setText("Számítógép keresése a hálózaton");
                if (found.isEmpty()) {
                    Toast.makeText(this, "Nem található eszköz. Fut a DeviceBridge a PC-n, azonos hálózaton vagytok?", Toast.LENGTH_LONG).show();
                }
                for (Discovery.Device d : found) {
                    Button item = new Button(this);
                    item.setAllCaps(false);
                    item.setText("Kapcsolódás: " + d.name + "  (" + d.address.getHostAddress() + ")");
                    item.setOnClickListener(v -> connect(d.address.getHostAddress(), d.port));
                    foundList.addView(item);
                }
            });
        }, "bridge-scan").start();
    }

    private void connectManual() {
        String h = hostInput.getText().toString().trim();
        if (h.isEmpty()) return;
        try {
            String[] hp = NetUtil.splitHostPort(h, Protocol.TCP_PORT);
            connect(hp[0], Integer.parseInt(hp[1]));
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Hibás port", Toast.LENGTH_SHORT).show();
        }
    }

    private void connect(String host, int port) {
        ensureService();
        if (!bridge.node.connectAsync(() -> SocketTransport.connect(host, port, TransportKind.NETWORK, 8000))) {
            Toast.makeText(this, "Már van aktív kapcsolat", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ műveletek

    private void sendClipboard() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        ClipData clip = cm == null ? null : cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            Toast.makeText(this, "A vágólap üres", Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        if (text == null || text.length() == 0) {
            Toast.makeText(this, "A vágólapon nincs szöveg", Toast.LENGTH_SHORT).show();
            return;
        }
        String s = text.toString();
        bridge.post(() -> bridge.control.sendClipboard(s));
        Toast.makeText(this, "Vágólap elküldve", Toast.LENGTH_SHORT).show();
    }

    private interface TextCallback {
        void onText(String text);
    }

    private void prompt(String title, String initial, int inputType, TextCallback cb) {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | inputType);
        et.setText(initial);
        et.setSelection(initial.length());
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(et)
                .setPositiveButton("Küldés", (d, w) -> {
                    String t = et.getText().toString().trim();
                    if (!t.isEmpty()) cb.onText(t);
                })
                .setNegativeButton("Mégse", null)
                .show();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILES || resultCode != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        for (Uri u : uris) FileSender.send(this, bridge, u);
    }

    // ------------------------------------------------------------------ beállítások

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void showSettings() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), dp(8), dp(20), 0);

        EditText name = new EditText(this);
        name.setHint("Eszköz neve");
        name.setText(bridge.prefs.getString("device.name", ""));
        name.setHint(bridge.deviceName());
        TextView nameLabel = new TextView(this);
        nameLabel.setText("Eszköz neve (üresen: rendszer neve)");
        l.addView(nameLabel);
        l.addView(name);

        CheckBox remote = new CheckBox(this);
        remote.setText("Távoli asztal engedélyezése (a PC láthatja és vezérelheti a telefont)");
        remote.setChecked(bridge.remoteControlAllowed());
        l.addView(remote);

        CheckBox ssh = new CheckBox(this);
        ssh.setText("SSH + SFTP szerver (port " + Protocol.SSH_PORT + ", csak helyi hálózat / USB)");
        ssh.setChecked(bridge.sshEnabled());
        l.addView(ssh);

        TextView creds = new TextView(this);
        creds.setTextIsSelectable(true);
        creds.setPadding(dp(32), 0, 0, dp(4));
        Runnable updateCreds = () -> creds.setText("Felhasználó: " + bridge.sshUser() + "\nJelszó: " + bridge.sshPassword());
        updateCreds.run();
        l.addView(creds);

        Button newPw = new Button(this);
        newPw.setAllCaps(false);
        newPw.setText("Új SSH jelszó");
        newPw.setOnClickListener(v -> {
            bridge.prefs.edit().putString("ssh.password", CryptoUtil.randomPassword(12)).apply();
            updateCreds.run();
            bridge.post(bridge.control::sendSshInfo);
        });
        l.addView(newPw);

        CheckBox boot = new CheckBox(this);
        boot.setText("Indítás a telefon bekapcsolásakor");
        boot.setChecked(bridge.startOnBoot());
        l.addView(boot);

        new AlertDialog.Builder(this)
                .setTitle("Beállítások")
                .setView(l)
                .setPositiveButton("Mentés", (d, w) -> {
                    boolean sshWas = bridge.sshEnabled();
                    bridge.prefs.edit()
                            .putString("device.name", name.getText().toString().trim())
                            .putBoolean("remote.allow", remote.isChecked())
                            .putBoolean("ssh.enabled", ssh.isChecked())
                            .putBoolean("boot.start", boot.isChecked())
                            .apply();
                    if (!remote.isChecked()) bridge.post(() -> bridge.remote.stopHost("A távoli asztalt letiltották a telefonon"));
                    if (bridge.isRunning() && ssh.isChecked() != sshWas) {
                        bridge.post(ssh.isChecked() ? bridge::startSsh : bridge::stopSsh);
                    }
                    refresh();
                })
                .setNegativeButton("Mégse", null)
                .show();
    }

    private void showPaired() {
        List<TrustStore.TrustedDevice> list = bridge.trust.list();
        if (list.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Párosított eszközök").setMessage("Még nincs párosított eszköz.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        String[] items = new String[list.size()];
        for (int i = 0; i < items.length; i++) items[i] = list.get(i).name + "\n" + list.get(i).fingerprint;
        new AlertDialog.Builder(this)
                .setTitle("Párosított eszközök (koppints a törléshez)")
                .setItems(items, (d, which) -> {
                    TrustStore.TrustedDevice dev = list.get(which);
                    new AlertDialog.Builder(this)
                            .setTitle("Párosítás törlése")
                            .setMessage("Törlöd a párosítást ezzel: " + dev.name + "?")
                            .setPositiveButton("Törlés", (d2, w2) -> bridge.trust.remove(dev.fingerprint))
                            .setNegativeButton("Mégse", null)
                            .show();
                })
                .setPositiveButton("Bezárás", null)
                .show();
    }

    private void requestPerms(boolean explicit) {
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (perms.isEmpty()) {
            if (explicit) {
                Toast.makeText(this, "Minden engedély megvan. Az alkalmazás beállításai nyílnak meg.", Toast.LENGTH_SHORT).show();
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            }
            return;
        }
        requestPermissions(perms.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS && bridge.isRunning()) {
            bridge.post(() -> {
                bridge.bluetooth.stop();
                bridge.bluetooth.start();
            });
        }
    }
}
