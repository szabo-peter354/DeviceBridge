package com.devicebridge.common.crypto;

import com.devicebridge.common.LocalInfo;
import com.devicebridge.common.Message;
import com.devicebridge.common.MsgType;
import com.devicebridge.common.PairingConfirmer;
import com.devicebridge.common.PairingException;
import com.devicebridge.common.PayloadReader;
import com.devicebridge.common.PayloadWriter;
import com.devicebridge.common.PeerInfo;
import com.devicebridge.common.Protocol;
import com.devicebridge.common.transport.Transport;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Titkosított, hitelesített csatorna bármely {@link Transport} fölött.
 *
 * <p>Kézfogás:
 * <ol>
 *   <li>Mindkét fél elküldi: név, típus, hosszú távú azonosító kulcs, ideiglenes ECDH kulcs, nonce.</li>
 *   <li>ECDH (P-256) + HKDF-SHA256 → irányonként külön AES-256-GCM kulcs + 6 jegyű ellenőrző kód.</li>
 *   <li>Mindkét fél ECDSA aláírással bizonyítja az azonosító kulcs birtoklását.</li>
 *   <li>Ha bármelyik fél még nem ismeri a másikat, mindkét eszközön jóvá kell hagyni az
 *       ellenőrző kódot (MITM ellen védett párosítás, mint a Bluetooth "numeric comparison").</li>
 * </ol>
 */
public final class SecureChannel implements Closeable {
    private static final byte[] AUTH_LABEL = "DBRG-AUTH-v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KDF_INFO = "DeviceBridge v1 session".getBytes(StandardCharsets.UTF_8);
    private static final int TAG_BITS = 128;

    private final Transport transport;
    private final ActivityInput activity;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final SecretKeySpec sendKey;
    private final SecretKeySpec recvKey;
    private final Cipher sendCipher;
    private final Cipher recvCipher;
    private final Object writeLock = new Object();
    private long sendCounter;
    private long recvCounter;
    private volatile boolean closed;
    /** Mióta tart az éppen futó írás (0 = nincs írás folyamatban). */
    private volatile long writeStartedAt;
    private PeerInfo peer;

    private SecureChannel(Transport transport, ActivityInput activity, DataInputStream in, DataOutputStream out,
                          byte[] sendKey, byte[] recvKey) throws GeneralSecurityException {
        this.transport = transport;
        this.activity = activity;
        this.in = in;
        this.out = out;
        this.sendKey = new SecretKeySpec(sendKey, "AES");
        this.recvKey = new SecretKeySpec(recvKey, "AES");
        this.sendCipher = Cipher.getInstance("AES/GCM/NoPadding");
        this.recvCipher = Cipher.getInstance("AES/GCM/NoPadding");
    }

    public static SecureChannel handshake(Transport transport, boolean initiator, LocalInfo local, Identity identity,
                                          TrustStore trust, PairingConfirmer confirmer) throws IOException {
        transport.setReadTimeout(20_000);
        ActivityInput activity = new ActivityInput(transport.input());
        DataInputStream in = new DataInputStream(new BufferedInputStream(activity, 64 * 1024));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(transport.output(), 64 * 1024));
        try {
            KeyPair eph = CryptoUtil.generateEcKeyPair();
            byte[] myHello = PayloadWriter.create()
                    .i32(Protocol.MAGIC)
                    .u16(Protocol.VERSION)
                    .bool(initiator)
                    .str(local.name)
                    .str(local.deviceType)
                    .bytes(identity.publicKeyEncoded())
                    .bytes(eph.getPublic().getEncoded())
                    .bytes(CryptoUtil.random(32))
                    .build();
            out.writeInt(myHello.length);
            out.write(myHello);
            out.flush();

            int len = in.readInt();
            if (len <= 0 || len > 16 * 1024) throw new IOException("A másik fél nem DeviceBridge eszköz");
            byte[] peerHello = new byte[len];
            in.readFully(peerHello);

            PayloadReader r = new PayloadReader(peerHello);
            if (r.i32() != Protocol.MAGIC) throw new IOException("A másik fél nem DeviceBridge eszköz");
            int version = r.u16();
            if (version != Protocol.VERSION) throw new IOException("Nem kompatibilis protokollverzió: " + version);
            boolean peerInitiator = r.bool();
            if (peerInitiator == initiator) throw new IOException("Mindkét fél ugyanabban a szerepben van");
            String peerName = r.str();
            String peerType = r.str();
            byte[] peerIdentity = r.bytes();
            byte[] peerEphemeral = r.bytes();
            r.bytes(); // nonce – a transcript része

            PublicKey peerIdKey = CryptoUtil.decodeEcPublic(peerIdentity);
            PublicKey peerEphKey = CryptoUtil.decodeEcPublic(peerEphemeral);

            byte[] transcript = initiator ? CryptoUtil.sha256(myHello, peerHello) : CryptoUtil.sha256(peerHello, myHello);
            byte[] shared = CryptoUtil.ecdh(eph.getPrivate(), peerEphKey);
            byte[] okm = CryptoUtil.hkdf(transcript, shared, KDF_INFO, 68);
            byte[] i2r = Arrays.copyOfRange(okm, 0, 32);
            byte[] r2i = Arrays.copyOfRange(okm, 32, 64);
            byte[] sasBytes = Arrays.copyOfRange(okm, 64, 68);

            SecureChannel ch = new SecureChannel(transport, activity, in, out, initiator ? i2r : r2i, initiator ? r2i : i2r);

            // Az azonosító kulcs birtoklásának bizonyítása
            byte myRole = (byte) (initiator ? 1 : 0);
            byte peerRole = (byte) (initiator ? 0 : 1);
            ch.send(MsgType.HS_AUTH, identity.sign(CryptoUtil.concat(AUTH_LABEL, new byte[]{myRole}, transcript)));
            Message auth = ch.expect(MsgType.HS_AUTH);
            if (!CryptoUtil.verify(peerIdKey, CryptoUtil.concat(AUTH_LABEL, new byte[]{peerRole}, transcript), auth.payload)) {
                throw new IOException("A másik eszköz hitelesítése sikertelen");
            }

            String fingerprint = Identity.fingerprintOf(peerIdentity);
            PeerInfo peer = new PeerInfo(peerName, peerType, fingerprint, transport.description(),
                    transport.kind(), transport.remoteAddress());

            boolean iTrust = trust.isTrusted(fingerprint, peerIdentity);
            ch.send(MsgType.HS_TRUST, new byte[]{(byte) (iTrust ? 1 : 0)});
            Message tm = ch.expect(MsgType.HS_TRUST);
            boolean peerTrusts = tm.payload.length > 0 && tm.payload[0] == 1;

            if (!(iTrust && peerTrusts)) {
                transport.setReadTimeout(200_000);
                int n = ((sasBytes[0] & 0x7f) << 24) | ((sasBytes[1] & 0xff) << 16) | ((sasBytes[2] & 0xff) << 8) | (sasBytes[3] & 0xff);
                n %= 1_000_000;
                String code = String.format("%03d %03d", n / 1000, n % 1000);
                boolean accepted;
                CompletableFuture<Boolean> decision = confirmer.confirm(peer, code);
                try {
                    accepted = Boolean.TRUE.equals(decision.get(180, TimeUnit.SECONDS));
                } catch (Exception e) {
                    decision.cancel(true);
                    accepted = false;
                }
                ch.send(MsgType.HS_PAIR_DECISION, new byte[]{(byte) (accepted ? 1 : 0)});
                Message dm = ch.expect(MsgType.HS_PAIR_DECISION);
                boolean peerAccepted = dm.payload.length > 0 && dm.payload[0] == 1;
                if (!accepted) throw new PairingException("A párosítást elutasítottad");
                if (!peerAccepted) throw new PairingException("A másik eszköz elutasította a párosítást");
                trust.add(fingerprint, peerName, peerIdentity);
            }

            transport.setReadTimeout(0);
            ch.peer = peer;
            return ch;
        } catch (GeneralSecurityException e) {
            throw new IOException("Kriptográfiai hiba: " + e.getMessage(), e);
        }
    }

    private Message expect(int type) throws IOException {
        Message m = receive();
        if (m.type != type) throw new IOException("Váratlan üzenet a kézfogás során: " + m.type);
        return m;
    }

    public PeerInfo peer() {
        return peer;
    }

    public Transport transport() {
        return transport;
    }

    private static byte[] nonce(long counter) {
        byte[] n = new byte[12];
        for (int i = 0; i < 8; i++) n[4 + i] = (byte) (counter >>> (56 - 8 * i));
        return n;
    }

    public void send(int type, byte[] payload) throws IOException {
        if (payload.length > Protocol.MAX_RECORD_SIZE) throw new IOException("Túl nagy üzenet");
        synchronized (writeLock) {
            if (closed) throw new IOException("A kapcsolat le van zárva");
            byte[] plain = new byte[payload.length + 2];
            plain[0] = (byte) (type >>> 8);
            plain[1] = (byte) type;
            System.arraycopy(payload, 0, plain, 2, payload.length);
            byte[] ct;
            try {
                sendCipher.init(Cipher.ENCRYPT_MODE, sendKey, new GCMParameterSpec(TAG_BITS, nonce(sendCounter++)));
                ct = sendCipher.doFinal(plain);
            } catch (GeneralSecurityException e) {
                throw new IOException("Titkosítási hiba", e);
            }
            writeStartedAt = System.currentTimeMillis();
            try {
                out.writeInt(ct.length);
                out.write(ct);
                out.flush();
            } finally {
                writeStartedAt = 0;
            }
        }
    }

    /** Az utolsó beérkezett bájt ideje (nagy üzenet lassú vonalon is "élő"-nek számít, amíg jön az adat). */
    public long lastActivity() {
        return activity.last;
    }

    /** Mennyi ideje blokkol az éppen futó írás (ms), vagy 0. */
    public long writeStalledMs() {
        long s = writeStartedAt;
        return s == 0 ? 0 : System.currentTimeMillis() - s;
    }

    /** Számolja, mikor érkezett utoljára adat az átvitelen. */
    private static final class ActivityInput extends FilterInputStream {
        volatile long last = System.currentTimeMillis();

        ActivityInput(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int c = super.read();
            if (c >= 0) last = System.currentTimeMillis();
            return c;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) last = System.currentTimeMillis();
            return n;
        }
    }

    /** Csak egyetlen olvasó szálról hívható. */
    public Message receive() throws IOException {
        int len = in.readInt();
        if (len < 18 || len > Protocol.MAX_RECORD_SIZE + 64) throw new IOException("Hibás rekordméret: " + len);
        byte[] ct = new byte[len];
        in.readFully(ct);
        byte[] plain;
        try {
            recvCipher.init(Cipher.DECRYPT_MODE, recvKey, new GCMParameterSpec(TAG_BITS, nonce(recvCounter++)));
            plain = recvCipher.doFinal(ct);
        } catch (GeneralSecurityException e) {
            throw new IOException("Sérült vagy meghamisított adat érkezett", e);
        }
        int type = ((plain[0] & 0xff) << 8) | (plain[1] & 0xff);
        return new Message(type, Arrays.copyOfRange(plain, 2, plain.length));
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() throws IOException {
        closed = true;
        transport.close();
    }
}
