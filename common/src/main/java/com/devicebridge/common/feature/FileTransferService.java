package com.devicebridge.common.feature;

import com.devicebridge.common.BridgeNode;
import com.devicebridge.common.BridgeSession;
import com.devicebridge.common.MsgType;
import com.devicebridge.common.PayloadReader;
import com.devicebridge.common.PayloadWriter;
import com.devicebridge.common.crypto.CryptoUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Fájlátvitel mindkét irányban, darabolva és SHA-256 ellenőrzéssel. A párosított eszközről
 * érkező fájlokat automatikusan elfogadja és a platform által megadott helyre menti.
 */
public final class FileTransferService implements BridgeNode.Feature {

    public enum Status { WAITING, RUNNING, DONE, FAILED, CANCELLED }

    public interface FileSink {
        IncomingFile begin(String name, long size) throws IOException;
    }

    public interface IncomingFile {
        OutputStream stream() throws IOException;

        /** Sikeres fogadás után hívódik; a mentett fájl helyét adja vissza. */
        String complete() throws IOException;

        void abort();
    }

    public interface InputOpener {
        InputStream open() throws IOException;
    }

    public interface Listener {
        void onTransferUpdate(Transfer t);
    }

    public static final class Transfer {
        public final long id;
        public final String name;
        public final long size;
        public final boolean incoming;
        volatile long transferred;
        volatile Status status = Status.WAITING;
        volatile String message = "";
        volatile long lastFire;

        Transfer(long id, String name, long size, boolean incoming) {
            this.id = id;
            this.name = name;
            this.size = size;
            this.incoming = incoming;
        }

        public long transferred() { return transferred; }
        public Status status() { return status; }
        public String message() { return message; }

        public int percent() {
            if (status == Status.DONE) return 100;
            if (size <= 0) return 0;
            return (int) Math.min(100, transferred * 100 / size);
        }

        public boolean isFinished() {
            return status == Status.DONE || status == Status.FAILED || status == Status.CANCELLED;
        }
    }

    private static final int CHUNK = 64 * 1024;

    private static final class Outgoing {
        final Transfer t;
        final CompletableFuture<String> accepted = new CompletableFuture<>();
        final CompletableFuture<String> done = new CompletableFuture<>();
        volatile boolean cancelled;

        Outgoing(Transfer t) { this.t = t; }
    }

    private static final class Incoming {
        final Transfer t;
        final IncomingFile file;
        final OutputStream out;
        final MessageDigest md;

        Incoming(Transfer t, IncomingFile file, OutputStream out, MessageDigest md) {
            this.t = t;
            this.file = file;
            this.out = out;
            this.md = md;
        }
    }

    private final BridgeNode node;
    private final FileSink sink;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<Transfer> history = new CopyOnWriteArrayList<>();
    private final Map<Long, Outgoing> outgoing = new ConcurrentHashMap<>();
    private final Map<Long, Incoming> incoming = new ConcurrentHashMap<>();

    public FileTransferService(BridgeNode node, FileSink sink) {
        this.node = node;
        this.sink = sink;
        node.addFeature(this);
        node.registerHandler(MsgType.FILE_OFFER, (s, m) -> onOffer(m.reader()));
        node.registerHandler(MsgType.FILE_CHUNK, (s, m) -> onChunk(m.reader()));
        node.registerHandler(MsgType.FILE_END, (s, m) -> onEnd(m.reader()));
        node.registerHandler(MsgType.FILE_ACCEPT, (s, m) -> {
            Outgoing o = outgoing.get(m.reader().i64());
            if (o != null) o.accepted.complete(null);
        });
        node.registerHandler(MsgType.FILE_REJECT, (s, m) -> {
            PayloadReader r = m.reader();
            Outgoing o = outgoing.get(r.i64());
            if (o != null) o.accepted.complete(r.str());
        });
        node.registerHandler(MsgType.FILE_DONE, (s, m) -> {
            PayloadReader r = m.reader();
            Outgoing o = outgoing.get(r.i64());
            boolean ok = r.bool();
            String msg = r.str();
            if (o != null) o.done.complete(ok ? null : msg);
        });
        node.registerHandler(MsgType.FILE_CANCEL, (s, m) -> {
            PayloadReader r = m.reader();
            long id = r.i64();
            String reason = r.str();
            Outgoing o = outgoing.get(id);
            if (o != null) {
                o.cancelled = true;
                o.accepted.complete("Megszakítva: " + reason);
                o.done.complete("Megszakítva: " + reason);
            }
            Incoming in = incoming.remove(id);
            if (in != null) finishIncoming(in, Status.CANCELLED, "A küldő megszakította: " + reason);
        });
    }

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    public List<Transfer> history() {
        return new ArrayList<>(history);
    }

    private void fire(Transfer t, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - t.lastFire < 250) return;
        t.lastFire = now;
        for (Listener l : listeners) {
            try { l.onTransferUpdate(t); } catch (RuntimeException ignored) { }
        }
    }

    private void track(Transfer t) {
        history.add(0, t);
        while (history.size() > 50) history.remove(history.size() - 1);
        fire(t, true);
    }

    // ---------------------------------------------------------------- küldés

    public Transfer sendFile(String name, long size, InputOpener opener) {
        Transfer t = new Transfer(CryptoUtil.RANDOM.nextLong() & Long.MAX_VALUE, name, size, false);
        track(t);
        node.executor().execute(() -> doSend(t, opener));
        return t;
    }

    private void doSend(Transfer t, InputOpener opener) {
        Outgoing o = new Outgoing(t);
        outgoing.put(t.id, o);
        boolean announced = false;
        try (InputStream in = opener.open()) {
            if (!node.send(MsgType.FILE_OFFER, PayloadWriter.create().i64(t.id).str(t.name).i64(t.size))) {
                throw new IOException("Nincs kapcsolat");
            }
            announced = true;
            String rejection = o.accepted.get(60, TimeUnit.SECONDS);
            if (rejection != null) throw new IOException("Elutasítva: " + rejection);
            t.status = Status.RUNNING;
            fire(t, true);
            MessageDigest md = sha256();
            byte[] buf = new byte[CHUNK];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (o.cancelled) throw new IOException("Megszakítva");
                md.update(buf, 0, n);
                if (!node.send(MsgType.FILE_CHUNK, PayloadWriter.create().i64(t.id).bytes(buf, 0, n))) {
                    throw new IOException("A kapcsolat megszakadt");
                }
                t.transferred += n;
                fire(t, false);
            }
            node.send(MsgType.FILE_END, PayloadWriter.create().i64(t.id).i64(t.transferred).bytes(md.digest()));
            String result = o.done.get(120, TimeUnit.SECONDS);
            if (result != null) throw new IOException(result);
            t.status = Status.DONE;
            t.message = "Elküldve";
            node.log("Fájl elküldve: " + t.name);
        } catch (Exception e) {
            t.status = o.cancelled ? Status.CANCELLED : Status.FAILED;
            t.message = BridgeNode.describe(e);
            if (announced && !o.cancelled) {
                node.send(MsgType.FILE_CANCEL, PayloadWriter.create().i64(t.id).str(t.message));
            }
            node.log("Fájlküldés sikertelen (" + t.name + "): " + t.message);
        } finally {
            outgoing.remove(t.id);
            fire(t, true);
        }
    }

    public void cancel(Transfer t) {
        Outgoing o = outgoing.get(t.id);
        if (o != null) {
            o.cancelled = true;
            o.accepted.complete("Megszakítva");
            o.done.complete("Megszakítva");
            node.send(MsgType.FILE_CANCEL, PayloadWriter.create().i64(t.id).str("a küldő megszakította"));
        }
        Incoming in = incoming.remove(t.id);
        if (in != null) {
            node.send(MsgType.FILE_CANCEL, PayloadWriter.create().i64(t.id).str("a fogadó megszakította"));
            finishIncoming(in, Status.CANCELLED, "Megszakítva");
        }
    }

    // ---------------------------------------------------------------- fogadás

    private static String sanitize(String name) {
        String n = name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        n = n.replaceAll("[\\x00-\\x1F:*?\"<>|]", "_").trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) n = "fajl";
        if (n.length() > 180) n = n.substring(n.length() - 180);
        return n;
    }

    private void onOffer(PayloadReader r) throws IOException {
        long id = r.i64();
        String name = sanitize(r.str());
        long size = r.i64();
        Transfer t = new Transfer(id, name, size, true);
        track(t);
        try {
            IncomingFile f = sink.begin(name, size);
            incoming.put(id, new Incoming(t, f, f.stream(), sha256()));
            t.status = Status.RUNNING;
            fire(t, true);
            node.send(MsgType.FILE_ACCEPT, PayloadWriter.create().i64(id));
        } catch (Exception e) {
            t.status = Status.FAILED;
            t.message = BridgeNode.describe(e);
            fire(t, true);
            node.send(MsgType.FILE_REJECT, PayloadWriter.create().i64(id).str(t.message));
        }
    }

    private void onChunk(PayloadReader r) throws IOException {
        long id = r.i64();
        byte[] data = r.bytes();
        Incoming in = incoming.get(id);
        if (in == null) return;
        try {
            in.out.write(data);
            in.md.update(data);
            in.t.transferred += data.length;
            fire(in.t, false);
        } catch (IOException e) {
            incoming.remove(id);
            node.send(MsgType.FILE_CANCEL, PayloadWriter.create().i64(id).str("Írási hiba: " + e.getMessage()));
            finishIncoming(in, Status.FAILED, "Írási hiba: " + e.getMessage());
        }
    }

    private void onEnd(PayloadReader r) throws IOException {
        long id = r.i64();
        long total = r.i64();
        byte[] hash = r.bytes();
        Incoming in = incoming.remove(id);
        if (in == null) return;
        try {
            in.out.close();
            if (total != in.t.transferred || !MessageDigest.isEqual(hash, in.md.digest())) {
                throw new IOException("Ellenőrzőösszeg hiba – a fájl sérült");
            }
            String location = in.file.complete();
            in.t.status = Status.DONE;
            in.t.message = location;
            node.send(MsgType.FILE_DONE, PayloadWriter.create().i64(id).bool(true).str(location));
            node.log("Fájl fogadva: " + in.t.name + " → " + location);
            fire(in.t, true);
        } catch (IOException e) {
            node.send(MsgType.FILE_DONE, PayloadWriter.create().i64(id).bool(false).str(BridgeNode.describe(e)));
            finishIncoming(in, Status.FAILED, BridgeNode.describe(e));
        }
    }

    private void finishIncoming(Incoming in, Status status, String msg) {
        try { in.out.close(); } catch (IOException ignored) { }
        in.file.abort();
        in.t.status = status;
        in.t.message = msg;
        fire(in.t, true);
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    @Override
    public void onSessionStarted(BridgeSession session) {
    }

    @Override
    public void onSessionEnded(BridgeSession session) {
        for (Outgoing o : outgoing.values()) {
            o.cancelled = true;
            o.accepted.complete("A kapcsolat megszakadt");
            o.done.complete("A kapcsolat megszakadt");
        }
        for (Long id : new ArrayList<>(incoming.keySet())) {
            Incoming in = incoming.remove(id);
            if (in != null) finishIncoming(in, Status.FAILED, "A kapcsolat megszakadt");
        }
    }
}
