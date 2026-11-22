package com.devicebridge.android;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.KeyEvent;

import com.devicebridge.common.feature.ControlService;
import com.devicebridge.common.feature.DeviceStatus;
import com.devicebridge.common.feature.SshInfo;

/** A PC-ről érkező vezérlő parancsok végrehajtása a telefonon. */
final class AndroidControlHandler implements ControlService.Handler {
    private static Ringtone ringtone;
    private static Vibrator vibrator;
    private static Context appCtx;

    private final AndroidBridge b;

    AndroidControlHandler(AndroidBridge b) {
        this.b = b;
        appCtx = b.ctx;
    }

    @Override
    public void onClipboard(String text) {
        b.main.post(() -> {
            ClipboardManager cm = b.ctx.getSystemService(ClipboardManager.class);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("DeviceBridge", text));
        });
        Notifications.show(b.ctx, "Vágólap frissítve", text.length() > 120 ? text.substring(0, 120) + "…" : text, null);
    }

    @Override
    public void onOpenUrl(String url) {
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (BridgeApplication.isInForeground()) {
            b.main.post(() -> {
                try { b.ctx.startActivity(view); } catch (RuntimeException ignored) { }
            });
        } else {
            Notifications.show(b.ctx, "Link érkezett – koppints a megnyitáshoz", url, Notifications.activity(b.ctx, url.hashCode(), view));
        }
    }

    @Override
    public void onRing(boolean start) {
        b.main.post(() -> {
            if (start) startRing();
            else stopRing();
        });
    }

    private void startRing() {
        stopRing();
        Uri uri = RingtoneManager.getActualDefaultRingtoneUri(b.ctx, RingtoneManager.TYPE_ALARM);
        if (uri == null) uri = RingtoneManager.getActualDefaultRingtoneUri(b.ctx, RingtoneManager.TYPE_RINGTONE);
        if (uri == null) uri = Settings.System.DEFAULT_NOTIFICATION_URI;
        ringtone = RingtoneManager.getRingtone(b.ctx, uri);
        if (ringtone != null) {
            ringtone.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
            ringtone.setLooping(true);
            ringtone.play();
        }
        vibrator = b.ctx.getSystemService(Vibrator.class);
        if (vibrator != null) vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 600, 400}, 0));
        Notification n = Notifications.event(b.ctx, "A számítógép keresi a telefont!", "Koppints a leállításhoz")
                .setContentIntent(Notifications.service(b.ctx, 14, new Intent(b.ctx, BridgeService.class).setAction(BridgeService.ACTION_STOP_RING)))
                .setOngoing(true)
                .build();
        try {
            b.ctx.getSystemService(NotificationManager.class).notify(Notifications.ID_RING, n);
        } catch (SecurityException ignored) {
        }
        b.main.postDelayed(AndroidControlHandler::stopRing, 30_000);
    }

    static void stopRing() {
        if (ringtone != null) ringtone.stop();
        ringtone = null;
        if (vibrator != null) vibrator.cancel();
        vibrator = null;
        if (appCtx != null) Notifications.cancel(appCtx, Notifications.ID_RING);
    }

    @Override
    public void onLock() {
        InputAccessibilityService s = InputAccessibilityService.instance;
        if (s != null) {
            s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
        } else {
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Notifications.show(b.ctx, Notifications.ID_ACCESSIBILITY, "Zárolás nem lehetséges",
                    "A távoli zároláshoz kapcsold be: Akadálymentesítés → DeviceBridge távvezérlés.", Notifications.activity(b.ctx, 5, i));
        }
    }

    @Override
    public void onMedia(int action) {
        AudioManager am = b.ctx.getSystemService(AudioManager.class);
        if (am == null) return;
        switch (action) {
            case ControlService.MEDIA_PLAY_PAUSE: mediaKey(am, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); break;
            case ControlService.MEDIA_NEXT: mediaKey(am, KeyEvent.KEYCODE_MEDIA_NEXT); break;
            case ControlService.MEDIA_PREVIOUS: mediaKey(am, KeyEvent.KEYCODE_MEDIA_PREVIOUS); break;
            case ControlService.VOLUME_UP: am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); break;
            case ControlService.VOLUME_DOWN: am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); break;
            case ControlService.VOLUME_MUTE: am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI); break;
            default: break;
        }
    }

    private static void mediaKey(AudioManager am, int code) {
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
    }

    @Override
    public void onNotify(String title, String text) {
        Notifications.show(b.ctx, title.isEmpty() ? "Üzenet" : title, text, null);
    }

    @Override
    public void onPeerStatus(DeviceStatus status) {
        b.fireUi();
    }

    @Override
    public void onPeerSshInfo(SshInfo info) {
        b.fireUi();
    }
}
