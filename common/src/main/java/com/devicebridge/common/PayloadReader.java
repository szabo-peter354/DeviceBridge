package com.devicebridge.common;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** A {@link PayloadWriter} párja. */
public final class PayloadReader {
    private final ByteArrayInputStream bis;
    private final DataInputStream in;

    public PayloadReader(byte[] data) {
        this.bis = new ByteArrayInputStream(data);
        this.in = new DataInputStream(bis);
    }

    public int u8() throws IOException { return in.readUnsignedByte(); }
    public int u16() throws IOException { return in.readUnsignedShort(); }
    public int i32() throws IOException { return in.readInt(); }
    public long i64() throws IOException { return in.readLong(); }
    public boolean bool() throws IOException { return in.readUnsignedByte() != 0; }

    public String str() throws IOException {
        return new String(bytes(), StandardCharsets.UTF_8);
    }

    public byte[] bytes() throws IOException {
        int n = in.readInt();
        if (n < 0 || n > bis.available()) throw new IOException("Hibás üzenet (hossz: " + n + ")");
        byte[] b = new byte[n];
        in.readFully(b);
        return b;
    }

    public int remaining() {
        return bis.available();
    }
}
