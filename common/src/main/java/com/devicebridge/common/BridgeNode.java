package com.devicebridge.common;

import com.devicebridge.common.crypto.Identity;
import com.devicebridge.common.crypto.SecureChannel;
import com.devicebridge.common.crypto.TrustStore;
import com.devicebridge.common.transport.SocketTransport;
import com.devicebridge.common.transport.Transport;
import com.devicebridge.common.transport.TransportKind;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * A DeviceBridge központi motorja – mindkét platformon ugyanez fut.
 * Egyszerre egy párosított eszközzel tart fenn kapcsolatot; az egyes funkciók
 * ({@link Feature}) üzenetkezelőket regisztrálnak.
 */
public final class BridgeNode {

    public enum State { IDLE, CONNECTING, CONNECTED }

    public interface Listener {
        default void onStateChanged(State state, PeerInfo peer) {}
        default void onLog(String line) {}
    }

    public interface Feature {
        void onSessionStarted(BridgeSession session);
        void onSessionEnded(BridgeSession session);
    }

    public interface MessageHandler {
        void handle(BridgeSession session, Message message) throws Exception;
    }

    public interface TransportOpener {
        Transport open() throws Exception;
    }

    private final Supplier<LocalInfo> localInfo;
    private final Identity identity;
    private final TrustStore trustStore;
    private final PairingConfirmer confirmer;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<Feature> features = new CopyOnWriteArrayList<>();
    private final Map<Integer, MessageHandler> handlers = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(daemon("bridge-worker"));
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, daemon("bridge-timer"));
    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final Deque<String> logLines = new ArrayDeque<>();
    private volatile BridgeSession session;
    private volatile boolean lastCloseClean = true;
    private ServerSocket tcpServer;

    public BridgeNode(Supplier<LocalInfo> localInfo, Identity identity, TrustStore trustStore, PairingConfirmer confirmer) {
        this.localInfo = localInfo;
        this.identity = identity;
        this.trustStore = trustStore;
        this.confirmer = confirmer;
    }

    public static ThreadFactory daemon(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    // ---------------------------------------------------------------- hozzáférők

    public Identity identity() { return identity; }
    public TrustStore trustStore() { return trustStore; }
    public LocalInfo localInfo() { return localInfo.get(); }
    public ExecutorService executor() { return executor; }
    public ScheduledExecutorService scheduler() { return scheduler; }
    public State state() { return state.get(); }
    public BridgeSession session() { return session; }

    public PeerInfo peer() {
        BridgeSession s = session;
        return s == null ? null : s.peer();
    }

    /** Igaz, ha az utolsó kapcsolatot valamelyik fél szándékosan bontotta (nem szakadt meg). */
    public boolean lastDisconnectWasClean() {
        return lastCloseClean;
    }

    public boolean isConnected() {
        BridgeSession s = session;
        return s != null && !s.isClosed();
    }

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }
    public void addFeature(Feature f) { features.add(f); }

    public void registerHandler(int type, MessageHandler handler) {
        handlers.put(type, handler);
    }

    // ---------------------------------------------------------------- napló

    public void log(String msg) {
        String line = String.format("%tT  %s", new Date(), msg);
        synchronized (logLines) {
            logLines.addLast(line);
            while (logLines.size() > 300) logLines.removeFirst();
        }
        for (Listener l : listeners) {
            try { l.onLog(line); } catch (RuntimeException ignored) { }
        }
    }

    public List<String> logHistory() {
        synchronized (logLines) {
            return new ArrayList<>(logLines);
        }
    }

    private void fireState() {
        State s = state.get();
        PeerInfo p = peer();
        for (Listener l : listeners) {
            try { l.onStateChanged(s, p); } catch (RuntimeException ignored) { }
        }
    }

    // ---------------------------------------------------------------- TCP szerver

    public synchronized void startTcpServer(int port) throws IOException {
        if (tcpServer != null) return;
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(port));
        tcpServer = ss;
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                Socket s = null;
                try {
                    s = ss.accept();
                    InetAddress a = s.getInetAddress();
                    TransportKind kind = a.isLoopbackAddress() ? TransportKind.USB : TransportKind.NETWORK;
                    String desc = kind == TransportKind.USB ? "USB (ADB)" : kind.label() + ": " + a.getHostAddress();
                    acceptIncoming(new SocketTransport(s, kind, desc));
                } catch (Throwable e) {
                    // Semmilyen hiba nem állíthatja le a szervert; a hibás kapcsolatot lezárjuk
                    if (s != null) {
                        try { s.close(); } catch (IOException ignored) { }
                    }
                    if (ss.isClosed()) break;
                    log("TCP szerver hiba: " + describe(e) + " (" + e.getClass().getSimpleName() + ")");
                    try { Thread.sleep(200); } catch (InterruptedException ie) { break; }
                }
            }
        }, "bridge-tcp-server");
        t.setDaemon(true);
        t.start();
        log("Kapcsolatra vár a " + port + "-es TCP porton");
    }

    public synchronized void stopTcpServer() {
        if (tcpServer == null) return;
        try { tcpServer.close(); } catch (IOException ignored) { }
        tcpServer = null;
    }

    public synchronized boolean isTcpServerRunning() {
        return tcpServer != null && !tcpServer.isClosed();
    }

    /** A TCP szerver tényleges portja, vagy -1. */
    public synchronized int tcpPort() {
        return tcpServer == null ? -1 : tcpServer.getLocalPort();
    }

    // ---------------------------------------------------------------- kapcsolatok

    /** Kimenő kapcsolat (kezdeményező szerep). Hamis, ha már van aktív kapcsolat. */
    public boolean connectAsync(TransportOpener opener) {
        if (!state.compareAndSet(State.IDLE, State.CONNECTING)) {
            log("Már van aktív kapcsolat");
            return false;
        }
        fireState();
        executor.execute(() -> {
            Transport t;
            try {
                t = opener.open();
            } catch (Exception e) {
                log("Kapcsolódás sikertelen: " + describe(e));
                state.set(State.IDLE);
                fireState();
                return;
            }
            runSession(t, true);
        });
        return true;
    }

    /** Bejövő kapcsolat (fogadó szerep) – TCP szerver, Bluetooth szerver hívja. */
    public void acceptIncoming(Transport t) {
        if (!state.compareAndSet(State.IDLE, State.CONNECTING)) {
            log("Bejövő kapcsolat elutasítva (már van aktív kapcsolat): " + t.description());
            try { t.close(); } catch (IOException ignored) { }
            return;
        }
        try {
            fireState();
            executor.execute(() -> runSession(t, false));
        } catch (RuntimeException e) {
            state.set(State.IDLE);
            try { t.close(); } catch (IOException ignored) { }
            throw e;
        }
    }

    private void runSession(Transport t, boolean initiator) {
        BridgeSession s = null;
        try {
            log((initiator ? "Kapcsolódás: " : "Bejövő kapcsolat: ") + t.description());
            SecureChannel ch = SecureChannel.handshake(t, initiator, localInfo.get(), identity, trustStore, confirmer);
            s = new BridgeSession(this, ch);
            session = s;
            state.set(State.CONNECTED);
            log("Kapcsolódva: " + ch.peer().name + " – " + t.description());
            fireState();
            for (Feature f : features) {
                try { f.onSessionStarted(s); } catch (RuntimeException e) { log("Hiba: " + describe(e)); }
            }
            s.runLoop();
        } catch (Throwable e) {
            if (s == null || !s.isClosed()) log("Kapcsolat hiba: " + describe(e) + " (" + e.getClass().getSimpleName() + ")");
        } finally {
            try { t.close(); } catch (IOException ignored) { }
            lastCloseClean = s != null && s.isCleanClose();
            if (s != null) {
                s.closeInternal();
                for (Feature f : features) {
                    try { f.onSessionEnded(s); } catch (RuntimeException e) { log("Hiba: " + describe(e)); }
                }
                log("Kapcsolat bontva");
            }
            session = null;
            state.set(State.IDLE);
            fireState();
        }
    }

    /** Üzenet küldése az aktív kapcsolaton. Hamis, ha nincs kapcsolat vagy hiba történt. */
    public boolean send(int type, byte[] payload) {
        BridgeSession s = session;
        if (s == null || s.isClosed()) return false;
        try {
            s.send(type, payload);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean send(int type, PayloadWriter w) {
        return send(type, w.build());
    }

    void dispatch(BridgeSession s, Message m) {
        MessageHandler h = handlers.get(m.type);
        if (h == null) {
            log("Ismeretlen üzenettípus: " + m.type);
            return;
        }
        try {
            h.handle(s, m);
        } catch (Exception e) {
            log("Hiba az üzenet feldolgozásakor (" + m.type + "): " + describe(e));
        }
    }

    public void disconnect() {
        BridgeSession s = session;
        if (s != null) s.disconnect();
    }

    public void shutdown() {
        stopTcpServer();
        disconnect();
        scheduler.shutdownNow();
        executor.shutdownNow();
    }

    public static String describe(Throwable e) {
        if (e instanceof java.io.EOFException) return "A másik fél bontotta a kapcsolatot (fut rajta a DeviceBridge?)";
        if (e instanceof java.net.ConnectException) return "A kapcsolat elutasítva (fut a DeviceBridge a másik eszközön?)";
        if (e instanceof java.net.SocketTimeoutException) return "Időtúllépés – az eszköz nem válaszol";
        String m = e.getMessage();
        if (m == null || m.isEmpty()) return e.getClass().getSimpleName();
        return m;
    }
}
