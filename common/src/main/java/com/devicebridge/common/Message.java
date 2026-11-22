package com.devicebridge.common;

/** Egy visszafejtett üzenet: típus + bináris tartalom. */
public final class Message {
    public final int type;
    public final byte[] payload;

    public Message(int type, byte[] payload) {
        this.type = type;
        this.payload = payload == null ? new byte[0] : payload;
    }

    public PayloadReader reader() {
        return new PayloadReader(payload);
    }
}
