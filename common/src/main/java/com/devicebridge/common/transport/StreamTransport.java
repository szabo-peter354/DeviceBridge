package com.devicebridge.common.transport;

import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Általános átvitel kész be-/kimeneti folyamokból (Bluetooth RFCOMM, soros port).
 *
 * <p>Ezek a folyamok maguktól nem ismerik az olvasási időkorlátot, ezért egy őrszál figyeli,
 * meddig blokkol egy olvasás; ha tovább, mint a beállított korlát, lezárja az átvitelt
 * (ez felszabadítja a blokkoló olvasást), az olvasó pedig {@link SocketTimeoutException}-t kap.
 * Enélkül egy félbeszakadt Bluetooth kézfogás örökre "Kapcsolódás…" állapotban hagyná a programot.
 */
public final class StreamTransport implements Transport {
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "stream-transport-watchdog");
        t.setDaemon(true);
        return t;
    });

    private final WatchedInput in;
    private final OutputStream out;
    private final Closeable closer;
    private final String description;
    private final TransportKind kind;
    private final ScheduledFuture<?> watch;
    private volatile int readTimeoutMs;
    private volatile boolean timedOut;
    private volatile boolean closed;

    public StreamTransport(InputStream in, OutputStream out, Closeable closer, String description, TransportKind kind) {
        this.in = new WatchedInput(in);
        this.out = out;
        this.closer = closer;
        this.description = description;
        this.kind = kind;
        this.watch = WATCHDOG.scheduleWithFixedDelay(this::checkTimeout, 500, 500, TimeUnit.MILLISECONDS);
    }

    @Override
    public InputStream input() {
        return in;
    }

    @Override
    public OutputStream output() {
        return out;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public TransportKind kind() {
        return kind;
    }

    @Override
    public void setReadTimeout(int millis) {
        readTimeoutMs = Math.max(0, millis);
    }

    private void checkTimeout() {
        int t = readTimeoutMs;
        long since = in.blockedSince;
        if (t <= 0 || since == 0 || closed) return;
        if (System.currentTimeMillis() - since > t) {
            timedOut = true;
            try { close(); } catch (IOException ignored) { }
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        watch.cancel(false);
        try { in.close(); } catch (IOException ignored) { }
        try { out.close(); } catch (IOException ignored) { }
        if (closer != null) closer.close();
    }

    /** Méri, mióta blokkol az éppen futó olvasás. */
    private final class WatchedInput extends FilterInputStream {
        volatile long blockedSince;

        WatchedInput(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            blockedSince = System.currentTimeMillis();
            try {
                int c = super.read();
                if (c < 0 && timedOut) throw new SocketTimeoutException("Időtúllépés – az eszköz nem válaszol");
                return c;
            } catch (IOException e) {
                throw translate(e);
            } finally {
                blockedSince = 0;
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            blockedSince = System.currentTimeMillis();
            try {
                int n = super.read(b, off, len);
                if (n < 0 && timedOut) throw new SocketTimeoutException("Időtúllépés – az eszköz nem válaszol");
                return n;
            } catch (IOException e) {
                throw translate(e);
            } finally {
                blockedSince = 0;
            }
        }

        private IOException translate(IOException e) {
            if (!timedOut || e instanceof SocketTimeoutException) return e;
            SocketTimeoutException t = new SocketTimeoutException("Időtúllépés – az eszköz nem válaszol");
            t.initCause(e);
            return t;
        }
    }
}
