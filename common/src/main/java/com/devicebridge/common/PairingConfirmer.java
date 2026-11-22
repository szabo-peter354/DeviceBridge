package com.devicebridge.common;

import java.util.concurrent.CompletableFuture;

/**
 * Első kapcsolódáskor mindkét eszköz ugyanazt a 6 jegyű ellenőrző kódot mutatja.
 * A felhasználónak mindkét oldalon jóvá kell hagynia, hogy a kódok egyeznek.
 */
public interface PairingConfirmer {
    CompletableFuture<Boolean> confirm(PeerInfo peer, String code);
}
