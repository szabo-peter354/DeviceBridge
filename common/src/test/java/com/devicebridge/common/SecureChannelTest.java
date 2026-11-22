package com.devicebridge.common;

import com.devicebridge.common.crypto.Identity;
import com.devicebridge.common.crypto.SecureChannel;
import com.devicebridge.common.crypto.TrustStore;
import com.devicebridge.common.feature.TileDiffer;
import com.devicebridge.common.transport.SocketTransport;
import com.devicebridge.common.transport.TransportKind;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SecureChannelTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final class Pair {
        SecureChannel a;
        SecureChannel b;
    }

    private Pair connect(Identity idA, TrustStore trustA, PairingConfirmer confA,
                         Identity idB, TrustStore trustB, PairingConfirmer confB) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try (ServerSocket ss = new ServerSocket(0)) {
            Future<SecureChannel> server = ex.submit(() -> {
                Socket s = ss.accept();
                return SecureChannel.handshake(new SocketTransport(s, TransportKind.NETWORK, "srv"), false,
                        new LocalInfo("B", Protocol.DEVICE_TYPE_ANDROID), idB, trustB, confB);
            });
            Future<SecureChannel> client = ex.submit(() -> {
                SocketTransport t = SocketTransport.connect("127.0.0.1", ss.getLocalPort(), TransportKind.NETWORK, 3000);
                return SecureChannel.handshake(t, true, new LocalInfo("A", Protocol.DEVICE_TYPE_DESKTOP), idA, trustA, confA);
            });
            Pair p = new Pair();
            p.a = client.get();
            p.b = server.get();
            return p;
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    public void pairsOnceThenReconnectsSilently() throws Exception {
        File dir = tmp.newFolder();
        Identity idA = Identity.loadOrCreate(new File(dir, "a.id"));
        Identity idB = Identity.loadOrCreate(new File(dir, "b.id"));
        TrustStore trustA = new TrustStore(new File(dir, "a.trust"));
        TrustStore trustB = new TrustStore(new File(dir, "b.trust"));

        AtomicReference<String> codeA = new AtomicReference<>();
        AtomicReference<String> codeB = new AtomicReference<>();
        AtomicInteger prompts = new AtomicInteger();
        PairingConfirmer confA = (peer, code) -> { prompts.incrementAndGet(); codeA.set(code); return CompletableFuture.completedFuture(true); };
        PairingConfirmer confB = (peer, code) -> { prompts.incrementAndGet(); codeB.set(code); return CompletableFuture.completedFuture(true); };

        Pair p = connect(idA, trustA, confA, idB, trustB, confB);
        assertNotNull(codeA.get());
        assertEquals("Mindkét oldal ugyanazt a kódot mutatja", codeA.get(), codeB.get());
        assertEquals(2, prompts.get());
        assertEquals("B", p.a.peer().name);
        assertEquals(idB.fingerprint(), p.a.peer().fingerprint);

        byte[] data = "Szia, telefon! ÁÉŐŰ".getBytes("UTF-8");
        p.a.send(MsgType.CTRL_NOTIFY, data);
        Message m = p.b.receive();
        assertEquals(MsgType.CTRL_NOTIFY, m.type);
        assertArrayEquals(data, m.payload);
        p.b.send(MsgType.PING, new byte[0]);
        assertEquals(MsgType.PING, p.a.receive().type);
        p.a.close();
        p.b.close();

        // Második kapcsolódás: már párosítva, nincs újabb kérdés
        Pair p2 = connect(idA, trustA, confA, idB, trustB, confB);
        assertEquals(2, prompts.get());
        p2.a.close();
        p2.b.close();
    }

    @Test
    public void rejectedPairingFails() throws Exception {
        File dir = tmp.newFolder();
        Identity idA = Identity.loadOrCreate(new File(dir, "a.id"));
        Identity idB = Identity.loadOrCreate(new File(dir, "b.id"));
        TrustStore trustA = new TrustStore(new File(dir, "a.trust"));
        TrustStore trustB = new TrustStore(new File(dir, "b.trust"));
        PairingConfirmer yes = (peer, code) -> CompletableFuture.completedFuture(true);
        PairingConfirmer no = (peer, code) -> CompletableFuture.completedFuture(false);
        try {
            connect(idA, trustA, yes, idB, trustB, no);
            fail("Elutasított párosításnak hibát kell dobnia");
        } catch (Exception expected) {
            // ok
        }
        assertTrue(trustA.list().isEmpty());
        assertTrue(trustB.list().isEmpty());
    }

    @Test
    public void tileDifferFindsChanges() {
        TileDiffer d = new TileDiffer(64);
        int w = 200, h = 100;
        int[] px = new int[w * h];
        List<int[]> first = d.diff(px, w, h);
        assertEquals("Első képkocka: soronként egy téglalap", 2, first.size());
        assertTrue(d.diff(px, w, h).isEmpty());
        px[70 * w + 150] = 0xFFFFFF;
        List<int[]> rects = d.diff(px, w, h);
        assertEquals(1, rects.size());
        assertArrayEquals(new int[]{128, 64, 64, 36}, rects.get(0));
    }
}
