package com.devicebridge.common;

import com.devicebridge.common.crypto.SecureChannel;
import com.devicebridge.common.transport.Transport;

import java.io.IOException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Egy élő, titkosított kapcsolat a párosított eszközzel. */
public final class BridgeSession {
    private static final long IDLE_TIMEOUT_MS = 45_000;

    private final BridgeNode node;
    private final SecureChannel channel;
    private final long startedAt = System.currentTimeMillis();
    private volatile boolean closed;
    private volatile long lastReceived = System.currentTimeMillis();
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

    public void send(int type, byte[] payload) throws IOException {
        try {
            channel.send(type, payload);
        } catch (IOException e) {
            closeInternal();
            throw e;
        }
    }

    void runLoop() throws IOException {
        keepAlive = node.scheduler().scheduleWithFixedDelay(this::tick, 10, 10, TimeUnit.SECONDS);
        try {
            while (!closed) {
                Message m = channel.receive();
                lastReceived = System.currentTimeMillis();
                switch (m.type) {
                    case MsgType.PING:
                        channel.send(MsgType.PONG, m.payload);
                        break;
                    case MsgType.PONG:
                        try {
                            lastRttMs = System.currentTimeMillis() - m.reader().i64();
                        } catch (IOException ignored) {
                        }
                        break;
                    case MsgType.BYE:
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

    private void tick() {
        if (closed) return;
        if (System.currentTimeMillis() - lastReceived > IDLE_TIMEOUT_MS) {
            node.log("A kapcsolat nem válaszol – bontás");
            closeInternal();
            return;
        }
        try {
            channel.send(MsgType.PING, PayloadWriter.create().i64(System.currentTimeMillis()).build());
        } catch (IOException e) {
            closeInternal();
        }
    }

    /** Szabályos bontás: a másik felet is értesíti. */
    public void disconnect() {
        if (closed) return;
        try {
            channel.send(MsgType.BYE, new byte[0]);
        } catch (IOException ignored) {
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
