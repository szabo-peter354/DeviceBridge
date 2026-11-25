package com.devicebridge.common;

import com.devicebridge.common.transport.StreamTransport;
import com.devicebridge.common.transport.TransportKind;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** A Bluetooth/soros átvitel olvasási időkorlátja (a kézfogás ne ragadhasson be). */
public class StreamTransportTest {

    /** Úgy viselkedik, mint egy Bluetooth socket / COM port: a lezárás felszabadítja a blokkoló olvasást. */
    private static final class SocketLikeInput extends InputStream {
        private int pending = -1;
        private boolean closed;

        synchronized void push(int b) {
            pending = b;
            notifyAll();
        }

        @Override
        public synchronized int read() throws IOException {
            while (pending < 0) {
                if (closed) throw new IOException("socket closed");
                try {
                    wait();
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }
            int b = pending;
            pending = -1;
            return b;
        }

        @Override
        public synchronized void close() {
            closed = true;
            notifyAll();
        }
    }

    @Test(timeout = 10_000)
    public void silentPeerTimesOut() throws Exception {
        SocketLikeInput in = new SocketLikeInput();
        StreamTransport t = new StreamTransport(in, new ByteArrayOutputStream(), null, "teszt", TransportKind.BLUETOOTH);
        t.setReadTimeout(800);
        long start = System.currentTimeMillis();
        try {
            t.input().read();
            fail("Időtúllépést vártunk");
        } catch (SocketTimeoutException expected) {
            long took = System.currentTimeMillis() - start;
            assertTrue("túl korán: " + took, took >= 800);
            assertTrue("túl későn: " + took, took < 3000);
        }
    }

    @Test(timeout = 10_000)
    public void noTimeoutWaitsForData() throws Exception {
        SocketLikeInput in = new SocketLikeInput();
        StreamTransport t = new StreamTransport(in, new ByteArrayOutputStream(), null, "teszt", TransportKind.BLUETOOTH);
        t.setReadTimeout(0);
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
            }
            in.push(42);
        });
        writer.start();
        assertEquals(42, t.input().read());
        t.close();
    }
}
