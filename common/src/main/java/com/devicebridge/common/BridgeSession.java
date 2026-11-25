package com.devicebridge.common;

import com.devicebridge.common.crypto.SecureChannel;
import com.devicebridge.common.transport.Transport;

import java.io.IOException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Egy élő, titkosított kapcsolat a párosított eszközzel. */
public final class BridgeSession {
    /** Ennyi ideig tartó teljes csend után a kapcsolatot halottnak tekintjük. */
    private static final long IDLE_TIMEOUT_MS = 45_000;
    /** Ennyi ideig blokkoló írás után (pl. megszakadt Bluetooth vonal) a kapcsolatot bontjuk. */
    private static final long WRITE_STALL_TIMEOUT_MS = 60_000;

    private final BridgeNode node;
    private final SecureChannel channel;
    private final long startedAt = System.currentTimeMillis();
    private final AtomicBoolean pingInFlight = new AtomicBoolean();
    private volatile boolean closed;
    private volatile boolean cleanClose;
    private volatile long lastRttMs = -1;
    private ScheduledFuture<?> keepAlive;

    BridgeSession(BridgeNode node, SecureChannel channel) {
        this.node = node;
        this.channel = channel;
    }

    public PeerInfo peer() {
        return channel.peer();
    }

    public Transport transport() {
        return channel.transport();
    }

    public long startedAt() {
        return startedAt;
    }

    /** Utolsó mért oda-vissza késleltetés (ms), vagy -1. */
    public long rttMs() {
        return lastRttMs;
    }

    public boolean isClosed() {
        return closed;
    }

    /** Igaz, ha a kapcsolatot valamelyik fél szándékosan bontotta (nem hiba miatt szakadt meg). */
    public boolean isCleanClose() {
        return cleanClose;
    }

    public void send(int type, byte[] payload) throws IOException {
        try {
            channel.send(type, payload);
        } catch (IOException e) {
            closeInternal();
            throw e;
        }
    }

    void runLoop() throws IOException {
        // Az őrzés sosem küld maga (az írás blokkolhat), így egy beragadt vonalat is biztosan észlel
        keepAlive = node.scheduler().scheduleWithFixedDelay(this::tick, 5, 5, TimeUnit.SECONDS);
        try {
            while (!closed) {
                Message m = channel.receive();
                switch (m.type) {
                    case MsgType.PING:
                        // Külön szálon válaszolunk, hogy az olvasás ne álljon meg, amíg egy nagy küldés fut
                        sendAsync(MsgType.PONG, m.payload);
                        break;
                    case MsgType.PONG:
                        try {
                            lastRttMs = System.currentTimeMillis() - m.reader().i64();
                        } catch (IOException ignored) {
                        }
                        break;
                    case MsgType.BYE:
                        cleanClose = true;
                        closed = true;
                        return;
                    default:
                        node.dispatch(this, m);
                }
            }
        } finally {
            closeInternal();
        }
    }

    private void sendAsync(int type, byte[] payload) {
        try {
            node.executor().execute(() -> {
                try {
                    channel.send(type, payload);
                } catch (IOException e) {
                    closeInternal();
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    private long pingSentAt;

    private void tick() {
        if (closed) return;
        long now = System.currentTimeMillis();
        if (now - channel.lastActivity() > IDLE_TIMEOUT_MS) {
            node.log("A kapcsolat nem válaszol – bontás");
            closeInternal();
            return;
        }
        if (channel.writeStalledMs() > WRITE_STALL_TIMEOUT_MS) {
            node.log("Az adatküldés elakadt (megszakadt a vonal?) – bontás");
            closeInternal();
            return;
        }
        if (now - pingSentAt < 10_000 || !pingInFlight.compareAndSet(false, true)) return;
        pingSentAt = now;
        try {
            node.executor().execute(() -> {
                try {
                    channel.send(MsgType.PING, PayloadWriter.create().i64(System.currentTimeMillis()).build());
                } catch (IOException e) {
                    closeInternal();
                } finally {
                    pingInFlight.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            pingInFlight.set(false);
        }
    }

    /** Szabályos bontás: a másik felet is értesíti. */
    public void disconnect() {
        if (closed) return;
        cleanClose = true;
        // Elakadt vonalon a BYE küldése blokkolna – legfeljebb 2 másodpercet várunk rá
        try {
            node.executor().submit(() -> {
                try {
                    channel.send(MsgType.BYE, new byte[0]);
                } catch (IOException ignored) {
                }
            }).get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        closeInternal();
    }

    void closeInternal() {
        closed = true;
        ScheduledFuture<?> k = keepAlive;
        if (k != null) k.cancel(false);
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }
}
