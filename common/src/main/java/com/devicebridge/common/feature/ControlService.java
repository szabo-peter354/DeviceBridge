package com.devicebridge.common.feature;

import com.devicebridge.common.BridgeNode;
import com.devicebridge.common.BridgeSession;
import com.devicebridge.common.MsgType;
import com.devicebridge.common.PayloadReader;
import com.devicebridge.common.PayloadWriter;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Vezérlő parancsok: vágólap, link megnyitás, csörgetés, zárolás, média, üzenet, állapot, SSH adatok. */
public final class ControlService implements BridgeNode.Feature {

    public static final int MEDIA_PLAY_PAUSE = 1;
    public static final int MEDIA_NEXT = 2;
    public static final int MEDIA_PREVIOUS = 3;
    public static final int VOLUME_UP = 4;
    public static final int VOLUME_DOWN = 5;
    public static final int VOLUME_MUTE = 6;

    public interface Handler {
        void onClipboard(String text);
        void onOpenUrl(String url);
        void onRing(boolean start);
        void onLock();
        void onMedia(int action);
        void onNotify(String title, String text);
        void onPeerStatus(DeviceStatus status);
        void onPeerSshInfo(SshInfo info);
    }

    private final BridgeNode node;
    private final Handler handler;
    private volatile Supplier<DeviceStatus> statusSupplier;
    private volatile Supplier<SshInfo> sshInfoSupplier;
    private volatile DeviceStatus peerStatus;
    private volatile SshInfo peerSshInfo;
    private ScheduledFuture<?> statusTask;

    public ControlService(BridgeNode node, Handler handler) {
        this.node = node;
        this.handler = handler;
        node.addFeature(this);
        node.registerHandler(MsgType.CTRL_CLIPBOARD, (s, m) -> handler.onClipboard(m.reader().str()));
        node.registerHandler(MsgType.CTRL_OPEN_URL, (s, m) -> {
            String url = m.reader().str();
            String lower = url.toLowerCase();
            if (lower.startsWith("http://") || lower.startsWith("https://")) handler.onOpenUrl(url);
            else node.log("Nem engedélyezett link elutasítva: " + url);
        });
        node.registerHandler(MsgType.CTRL_RING, (s, m) -> handler.onRing(m.reader().bool()));
        node.registerHandler(MsgType.CTRL_LOCK, (s, m) -> handler.onLock());
        node.registerHandler(MsgType.CTRL_MEDIA, (s, m) -> handler.onMedia(m.reader().u8()));
        node.registerHandler(MsgType.CTRL_NOTIFY, (s, m) -> {
            PayloadReader r = m.reader();
            handler.onNotify(r.str(), r.str());
        });
        node.registerHandler(MsgType.CTRL_STATUS, (s, m) -> {
            peerStatus = DeviceStatus.read(m.reader());
            handler.onPeerStatus(peerStatus);
        });
        node.registerHandler(MsgType.CTRL_SSH_INFO, (s, m) -> {
            peerSshInfo = SshInfo.read(m.reader());
            handler.onPeerSshInfo(peerSshInfo);
        });
    }

    public void setStatusSupplier(Supplier<DeviceStatus> s) { statusSupplier = s; }
    public void setSshInfoSupplier(Supplier<SshInfo> s) { sshInfoSupplier = s; }
    public DeviceStatus peerStatus() { return peerStatus; }
    public SshInfo peerSshInfo() { return peerSshInfo; }

    @Override
    public synchronized void onSessionStarted(BridgeSession session) {
        peerStatus = null;
        peerSshInfo = null;
        sendStatus();
        sendSshInfo();
        statusTask = node.scheduler().scheduleWithFixedDelay(this::sendStatus, 60, 60, TimeUnit.SECONDS);
    }

    @Override
    public synchronized void onSessionEnded(BridgeSession session) {
        if (statusTask != null) statusTask.cancel(false);
        statusTask = null;
        peerStatus = null;
        peerSshInfo = null;
    }

    public boolean sendStatus() {
        Supplier<DeviceStatus> s = statusSupplier;
        if (s == null) return false;
        PayloadWriter w = PayloadWriter.create();
        s.get().write(w);
        return node.send(MsgType.CTRL_STATUS, w);
    }

    /** Újraküldi az SSH adatokat (pl. ha a szerver be-/kikapcsolt). */
    public boolean sendSshInfo() {
        Supplier<SshInfo> s = sshInfoSupplier;
        SshInfo info = s == null ? SshInfo.disabled() : s.get();
        PayloadWriter w = PayloadWriter.create();
        info.write(w);
        return node.send(MsgType.CTRL_SSH_INFO, w);
    }

    public boolean sendClipboard(String text) {
        return node.send(MsgType.CTRL_CLIPBOARD, PayloadWriter.create().str(text));
    }

    public boolean openUrl(String url) {
        return node.send(MsgType.CTRL_OPEN_URL, PayloadWriter.create().str(url));
    }

    public boolean ring(boolean start) {
        return node.send(MsgType.CTRL_RING, PayloadWriter.create().bool(start));
    }

    public boolean lock() {
        return node.send(MsgType.CTRL_LOCK, new byte[0]);
    }

    public boolean media(int action) {
        return node.send(MsgType.CTRL_MEDIA, PayloadWriter.create().u8(action));
    }

    public boolean notify(String title, String text) {
        return node.send(MsgType.CTRL_NOTIFY, PayloadWriter.create().str(title).str(text));
    }
}
