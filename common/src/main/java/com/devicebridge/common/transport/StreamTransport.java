package com.devicebridge.common.transport;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Általános átvitel kész be-/kimeneti folyamokból (Bluetooth RFCOMM, soros port). */
public final class StreamTransport implements Transport {
    private final InputStream in;
    private final OutputStream out;
    private final Closeable closer;
    private final String description;
    private final TransportKind kind;

    public StreamTransport(InputStream in, OutputStream out, Closeable closer, String description, TransportKind kind) {
        this.in = in;
        this.out = out;
        this.closer = closer;
        this.description = description;
        this.kind = kind;
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
    public void close() throws IOException {
        try { in.close(); } catch (IOException ignored) { }
        try { out.close(); } catch (IOException ignored) { }
        if (closer != null) closer.close();
    }
}
