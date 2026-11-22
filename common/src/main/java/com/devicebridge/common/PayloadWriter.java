package com.devicebridge.common;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Egyszerű bináris üzenetépítő (big-endian). */
public final class PayloadWriter {
    private final ByteArrayOutputStream bos = new ByteArrayOutputStream(64);
    private final DataOutputStream out = new DataOutputStream(bos);

    public static PayloadWriter create() {
        return new PayloadWriter();
    }

    public PayloadWriter u8(int v) {
        try { out.writeByte(v); } catch (IOException e) { throw new UncheckedIOException(e); }
        return this;
    }

    public PayloadWriter u16(int v) {
        try { out.writeShort(v); } catch (IOException e) { throw new UncheckedIOException(e); }
        return this;
    }

    public PayloadWriter i32(int v) {
        try { out.writeInt(v); } catch (IOException e) { throw new UncheckedIOException(e); }
        return this;
    }

    public PayloadWriter i64(long v) {
        try { out.writeLong(v); } catch (IOException e) { throw new UncheckedIOException(e); }
        return this;
    }

    public PayloadWriter bool(boolean v) {
        return u8(v ? 1 : 0);
    }

    public PayloadWriter str(String s) {
        return bytes((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
    }

    public PayloadWriter bytes(byte[] b) {
        return bytes(b, 0, b.length);
    }

    public PayloadWriter bytes(byte[] b, int off, int len) {
        i32(len);
        try { out.write(b, off, len); } catch (IOException e) { throw new UncheckedIOException(e); }
        return this;
    }

    public byte[] build() {
        try { out.flush(); } catch (IOException e) { throw new UncheckedIOException(e); }
        return bos.toByteArray();
    }
}
