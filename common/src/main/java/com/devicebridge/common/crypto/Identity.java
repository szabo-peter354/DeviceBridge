package com.devicebridge.common.crypto;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Properties;

/**
 * Az eszköz hosszú távú azonosító kulcspárja (ECDSA P-256). Ennek ujjlenyomata azonosítja
 * az eszközt a párosítás után.
 */
public final class Identity {
    private final PrivateKey privateKey;
    private final byte[] publicEncoded;
    private final String fingerprint;

    private Identity(PrivateKey privateKey, PublicKey publicKey) {
        this.privateKey = privateKey;
        this.publicEncoded = publicKey.getEncoded();
        this.fingerprint = fingerprintOf(publicEncoded);
    }

    public static synchronized Identity loadOrCreate(File file) throws IOException {
        try {
            if (file.isFile()) {
                Properties p = new Properties();
                try (InputStream in = new FileInputStream(file)) {
                    p.load(in);
                }
                PrivateKey priv = CryptoUtil.decodeEcPrivate(Base64.getDecoder().decode(p.getProperty("private")));
                PublicKey pub = CryptoUtil.decodeEcPublic(Base64.getDecoder().decode(p.getProperty("public")));
                return new Identity(priv, pub);
            }
            KeyPair kp = CryptoUtil.generateEcKeyPair();
            Properties p = new Properties();
            p.setProperty("private", Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()));
            p.setProperty("public", Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()));
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Nem hozható létre: " + parent);
            }
            File tmp = new File(file.getPath() + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                p.store(out, "DeviceBridge identity - NE OSZD MEG");
            }
            if (!tmp.renameTo(file)) throw new IOException("Nem menthető: " + file);
            return new Identity(kp.getPrivate(), kp.getPublic());
        } catch (GeneralSecurityException | IllegalArgumentException | NullPointerException e) {
            throw new IOException("Az azonosító kulcs nem tölthető be: " + e.getMessage(), e);
        }
    }

    public byte[] publicKeyEncoded() {
        return publicEncoded.clone();
    }

    public String fingerprint() {
        return fingerprint;
    }

    public byte[] sign(byte[] data) throws GeneralSecurityException {
        return CryptoUtil.sign(privateKey, data);
    }

    /** Rövid, ember által olvasható ujjlenyomat: SHA-256 első 8 bájtja, pl. "AB12-CD34-EF56-7890". */
    public static String fingerprintOf(byte[] publicEncoded) {
        String h = CryptoUtil.hex(CryptoUtil.sha256(publicEncoded)).substring(0, 16);
        return h.substring(0, 4) + "-" + h.substring(4, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16);
    }
}
