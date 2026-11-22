package com.devicebridge.android;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.devicebridge.common.Keys;
import com.devicebridge.common.feature.RemoteDesktopService;

/** A számítógép képernyőjének megtekintése és vezérlése a telefonról. */
public class RemoteViewActivity extends Activity implements RemoteDesktopService.Viewer {
    private static final int[] QUALITY = {40, 65, 85};
    private static final String[] QUALITY_LABEL = {"Gyors", "Normál", "Éles"};

    private AndroidBridge bridge;
    private RemoteScreenView screen;
    private TextView status;
    private EditText input;
    private Button qualityButton;
    private int qualityIndex = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bridge = AndroidBridge.get(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);

        screen = new RemoteScreenView(this);
        column.addView(screen, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Szövegbevitel
        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setBackgroundColor(0xFF1F2937);
        input = new EditText(this);
        input.setHint("Gépelés a számítógépre…");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFF9CA3AF);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, actionId, event) -> {
            sendInput(true);
            return true;
        });
        inputRow.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        inputRow.addView(key("Küld", () -> sendInput(false)));
        column.addView(inputRow);

        // Speciális billentyűk
        LinearLayout keys = new LinearLayout(this);
        keys.setOrientation(LinearLayout.HORIZONTAL);
        keys.addView(key("Enter", () -> keyPress(Keys.ENTER)));
        keys.addView(key("⌫", () -> keyPress(Keys.BACK_SPACE)));
        keys.addView(key("Esc", () -> keyPress(Keys.ESCAPE)));
        keys.addView(key("Tab", () -> keyPress(Keys.TAB)));
        keys.addView(key("←", () -> keyPress(Keys.LEFT)));
        keys.addView(key("↑", () -> keyPress(Keys.UP)));
        keys.addView(key("↓", () -> keyPress(Keys.DOWN)));
        keys.addView(key("→", () -> keyPress(Keys.RIGHT)));
        keys.addView(key("Del", () -> keyPress(Keys.DELETE)));
        keys.addView(key("Win", () -> bridge.post(() -> bridge.remote.sendAction(RemoteDesktopService.ACTION_HOME))));
        keys.addView(key("Ablakok", () -> bridge.post(() -> bridge.remote.sendAction(RemoteDesktopService.ACTION_RECENTS))));
        keys.addView(key("Zárolás", () -> bridge.post(() -> bridge.remote.sendAction(RemoteDesktopService.ACTION_LOCK))));
        qualityButton = key("Minőség: " + QUALITY_LABEL[qualityIndex], () -> {
            qualityIndex = (qualityIndex + 1) % QUALITY.length;
            qualityButton.setText("Minőség: " + QUALITY_LABEL[qualityIndex]);
            startViewing();
        });
        keys.addView(qualityButton);
        keys.addView(key("Bezárás", this::finish));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setBackgroundColor(0xFF111827);
        hs.addView(keys);
        column.addView(hs);

        root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(0x99000000);
        status.setPadding(16, 8, 16, 8);
        status.setText("Kapcsolódás a képernyőhöz…");
        root.addView(status, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));

        setContentView(root);
        hideSystemBars();

        screen.setListener(new RemoteScreenView.InputListener() {
            @Override
            public void onMove(int x, int y) {
                bridge.post(() -> bridge.remote.sendMouseMove(x, y));
            }

            @Override
            public void onButton(int button, boolean down, int x, int y) {
                bridge.post(() -> bridge.remote.sendMouseButton(button, down, x, y));
            }

            @Override
            public void onWheel(int amount, int x, int y) {
                bridge.post(() -> bridge.remote.sendWheel(amount, x, y));
            }
        });

        if (!bridge.node.isConnected()) {
            status.setText("Nincs kapcsolódott számítógép");
            return;
        }
        startViewing();
    }

    private Button key(String label, Runnable r) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setOnClickListener(v -> r.run());
        return b;
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private void startViewing() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int maxSize = Math.min(1920, Math.max(dm.widthPixels, dm.heightPixels));
        int q = QUALITY[qualityIndex];
        if (q <= 40) maxSize = Math.min(maxSize, 1024);
        int fps = q <= 40 ? 8 : 20;
        int ms = maxSize;
        bridge.post(() -> {
            if (!bridge.remote.startViewing(this, ms, q, fps)) {
                runOnUiThread(() -> status.setText("Nincs kapcsolat"));
            }
        });
    }

    private void keyPress(int code) {
        bridge.post(() -> bridge.remote.sendKeyPress(code));
    }

    private void sendInput(boolean withEnter) {
        String t = input.getText().toString();
        input.setText("");
        bridge.post(() -> {
            if (!t.isEmpty()) bridge.remote.sendText(t);
            if (withEnter) bridge.remote.sendKeyPress(Keys.ENTER);
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Hardver billentyűzet (pl. Bluetooth) támogatása, ha nem a beviteli mező van fókuszban
        if (input.hasFocus()) return super.onKeyDown(keyCode, event);
        int mapped;
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER: mapped = Keys.ENTER; break;
            case KeyEvent.KEYCODE_DEL: mapped = Keys.BACK_SPACE; break;
            case KeyEvent.KEYCODE_ESCAPE: mapped = Keys.ESCAPE; break;
            case KeyEvent.KEYCODE_TAB: mapped = Keys.TAB; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: mapped = Keys.LEFT; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: mapped = Keys.RIGHT; break;
            case KeyEvent.KEYCODE_DPAD_UP: mapped = Keys.UP; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: mapped = Keys.DOWN; break;
            case KeyEvent.KEYCODE_FORWARD_DEL: mapped = Keys.DELETE; break;
            default: mapped = -1;
        }
        if (mapped >= 0) {
            keyPress(mapped);
            return true;
        }
        int ch = event.getUnicodeChar();
        if (ch > 0 && keyCode != KeyEvent.KEYCODE_BACK) {
            String s = String.valueOf((char) ch);
            bridge.post(() -> bridge.remote.sendText(s));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        bridge.post(() -> bridge.remote.stopViewing(this));
        super.onDestroy();
    }

    // ------------------------------------------------------------------ Viewer (háttérszálról hívódik)

    @Override
    public void onFrameInfo(int screenWidth, int screenHeight, int frameWidth, int frameHeight) {
        screen.setFrameSize(frameWidth, frameHeight);
        runOnUiThread(() -> status.setText(screenWidth + "×" + screenHeight));
        screen.postDelayed(() -> status.setVisibility(View.GONE), 2500);
    }

    @Override
    public void onTile(int x, int y, int w, int h, byte[] jpeg) {
        Bitmap b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (b != null) screen.drawTile(b, x, y);
    }

    @Override
    public void onFrameEnd() {
        screen.postInvalidate();
    }

    @Override
    public void onStatus(String message) {
        runOnUiThread(() -> {
            status.setVisibility(View.VISIBLE);
            status.setText(message);
        });
    }

    @Override
    public void onRemoteStopped(String reason) {
        runOnUiThread(() -> {
            status.setVisibility(View.VISIBLE);
            status.setTextColor(0xFFFCA5A5);
            status.setText("Leállítva: " + reason);
        });
    }
}
