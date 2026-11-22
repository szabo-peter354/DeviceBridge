package com.devicebridge.android;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.devicebridge.common.NetUtil;
import com.devicebridge.common.PeerInfo;
import com.devicebridge.common.Protocol;
import com.devicebridge.common.feature.DeviceStatus;
import com.devicebridge.common.feature.SshInfo;
import com.devicebridge.common.transport.TransportKind;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Egyszerű SSH terminál a párosított számítógéphez (a belépési adatok a titkosított csatornán érkeznek). */
public class TerminalActivity extends Activity {
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007|\u001B[()][0-9A-Za-z]|\r");
    private static final int MAX_CHARS = 60_000;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final StringBuilder buffer = new StringBuilder();
    private AndroidBridge bridge;
    private TextView output;
    private ScrollView scroll;
    private EditText input;
    private SshClient client;
    private ClientSession session;
    private ChannelShell channel;
    private volatile OutputStream toServer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bridge = AndroidBridge.get(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        output = new TextView(this);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextSize(12);
        output.setTextIsSelectable(true);
        output.setPadding(16, 16, 16, 16);
        scroll = new ScrollView(this);
        scroll.addView(output);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout row = new LinearLayout(this);
        input = new EditText(this);
        input.setHint("Parancs");
        input.setTypeface(Typeface.MONOSPACE);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, a, e) -> {
            sendLine();
            return true;
        });
        row.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(button("Küld", this::sendLine));
        root.addView(row);

        LinearLayout keys = new LinearLayout(this);
        keys.addView(button("Ctrl+C", () -> sendRaw(new byte[]{3})));
        keys.addView(button("Tab", () -> sendRaw(new byte[]{9})));
        keys.addView(button("Törlés", () -> {
            buffer.setLength(0);
            output.setText("");
        }));
        keys.addView(button("Bezárás", this::finish));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.addView(keys);
        root.addView(hs);
        setContentView(root);

        io.execute(this::connect);
    }

    private Button button(String label, Runnable r) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setOnClickListener(v -> r.run());
        return b;
    }

    private void append(String s) {
        String clean = ANSI.matcher(s).replaceAll("");
        runOnUiThread(() -> {
            buffer.append(clean);
            if (buffer.length() > MAX_CHARS) buffer.delete(0, buffer.length() - MAX_CHARS);
            output.setText(buffer);
            scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void connect() {
        SshInfo info = bridge.control.peerSshInfo();
        if (!bridge.node.isConnected()) {
            append("Nincs kapcsolódott számítógép.\n");
            return;
        }
        if (info == null || !info.enabled) {
            append("A számítógépen nincs bekapcsolva az SSH szerver.\n(PC: DeviceBridge → Beállítások → SSH + SFTP szerver)\n");
            return;
        }
        List<String> endpoints = new ArrayList<>(info.endpoints);
        PeerInfo peer = bridge.node.peer();
        if (peer != null && peer.kind == TransportKind.NETWORK && peer.address != null) {
            endpoints.add(0, peer.address.getHostAddress() + ":" + Protocol.SSH_PORT);
        }
        try {
            AndroidSshServer.initMina(this);
            client = SshClient.setUpDefaultClient();
            String expected = info.hostKeyFingerprint;
            client.setServerKeyVerifier((s, addr, key) -> expected.isEmpty() || expected.equals(KeyUtils.getFingerPrint(key)));
            client.start();
            IOException last = null;
            for (String ep : endpoints) {
                String[] hp = NetUtil.splitHostPort(ep, Protocol.SSH_PORT);
                append("Kapcsolódás: " + hp[0] + ":" + hp[1] + " …\n");
                try {
                    session = client.connect(info.user, hp[0], Integer.parseInt(hp[1])).verify(4, TimeUnit.SECONDS).getSession();
                    break;
                } catch (IOException | RuntimeException e) {
                    last = e instanceof IOException ? (IOException) e : new IOException(e.getMessage());
                }
            }
            if (session == null) {
                throw new IOException("Az SSH szerver nem érhető el. SSH-hoz azonos helyi hálózat (Wi-Fi / PAN) vagy USB kapcsolat kell."
                        + (last != null ? "\n(" + last.getMessage() + ")" : ""));
            }
            session.addPasswordIdentity(info.password);
            session.auth().verify(10, TimeUnit.SECONDS);
            channel = session.createShellChannel();
            channel.setPtyType("dumb");
            OutputStream sink = new OutputStream() {
                @Override
                public void write(int b) {
                    write(new byte[]{(byte) b}, 0, 1);
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    append(new String(b, off, len, StandardCharsets.UTF_8));
                }
            };
            channel.setOut(sink);
            channel.setErr(sink);
            channel.open().verify(10, TimeUnit.SECONDS);
            toServer = channel.getInvertedIn();
            append("Kapcsolódva.\n");
            DeviceStatus ds = bridge.control.peerStatus();
            if (ds != null && ds.os.toLowerCase().contains("windows")) sendRaw("chcp 65001 >nul\r\n".getBytes(StandardCharsets.UTF_8));
            channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 0L);
            append("\n[A kapcsolat lezárult]\n");
        } catch (Exception e) {
            append("Hiba: " + e.getMessage() + "\n");
        }
    }

    private void sendLine() {
        String line = input.getText().toString();
        input.setText("");
        sendRaw((line + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private void sendRaw(byte[] data) {
        OutputStream out = toServer;
        if (out == null) return;
        writer.execute(() -> {
            try {
                out.write(data);
                out.flush();
            } catch (IOException e) {
                append("\n[Írási hiba: " + e.getMessage() + "]\n");
            }
        });
    }

    @Override
    protected void onDestroy() {
        // A waitFor blokkolja az io szálat, ezért a lezárás külön szálon fut
        Thread t = new Thread(() -> {
            try { if (channel != null) channel.close(true); } catch (Exception ignored) { }
            try { if (session != null) session.close(true); } catch (Exception ignored) { }
            try { if (client != null) client.stop(); } catch (Exception ignored) { }
            io.shutdownNow();
            writer.shutdownNow();
        }, "bridge-ssh-close");
        t.start();
        super.onDestroy();
    }
}
