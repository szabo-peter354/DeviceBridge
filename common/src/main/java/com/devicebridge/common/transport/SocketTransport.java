package com.devicebridge.common.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;

/** TCP alapú átvitel. */
public final class SocketTransport implements Transport {
    private final Socket socket;
    private final TransportKind kind;
    private final String description;

    public SocketTransport(Socket socket, TransportKind kind, String description) throws IOException {
        this.socket = socket;
        this.kind = kind;
        this.description = description;
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
    }

    public static SocketTransport connect(String host, int port, TransportKind kind, int timeoutMs) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignored) { }
            throw e;
        }
        return new SocketTransport(s, kind, kind.label() + ": " + host + ":" + port);
    }

    @Override
    public InputStream input() throws IOException {
        return socket.getInputStream();
    }

    @Override
    public OutputStream output() throws IOException {
        return socket.getOutputStream();
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
    public InetAddress remoteAddress() {
        return socket.getInetAddress();
    }

    @Override
    public void setReadTimeout(int millis) {
        try {
            socket.setSoTimeout(millis);
        } catch (SocketException ignored) {
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
