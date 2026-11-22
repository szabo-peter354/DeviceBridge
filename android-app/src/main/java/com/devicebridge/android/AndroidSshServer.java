package com.devicebridge.android;

import android.content.Context;

import com.devicebridge.common.NetUtil;
import com.devicebridge.common.Protocol;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.io.IoAcceptor;
import org.apache.sshd.common.io.IoServiceEventListener;
import org.apache.sshd.common.util.OsUtils;
import org.apache.sshd.common.util.io.PathUtils;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.shell.ProcessShellFactory;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

import java.io.File;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.Collections;

/**
 * Beépített SSH + SFTP szerver a telefonon (Apache MINA SSHD). Csak helyi hálózatról
 * (Wi-Fi/LAN, Bluetooth PAN, USB) fogad kapcsolatot. A shell a /system/bin/sh, az SFTP gyökere
 * az alkalmazás külső mappája (Android/data/com.devicebridge.android/files).
 */
final class AndroidSshServer {
    private static boolean minaInitialized;

    private final Context ctx;
    private final AndroidBridge bridge;
    private SshServer server;
    private SimpleGeneratorHostKeyProvider keyProvider;

    AndroidSshServer(Context ctx, AndroidBridge bridge) {
        this.ctx = ctx;
        this.bridge = bridge;
    }

    /** A MINA SSHD Androidon szükséges beállításai (felhasználó, home mappa). */
    static synchronized void initMina(Context ctx) {
        if (minaInitialized) return;
        Path home = ctx.getFilesDir().toPath();
        System.setProperty("user.home", home.toString());
        OsUtils.setAndroid(Boolean.TRUE);
        OsUtils.setCurrentUser("bridge");
        OsUtils.setCurrentWorkingDirectoryResolver(() -> home);
        PathUtils.setUserHomeFolderResolver(() -> home);
        minaInitialized = true;
    }

    synchronized void start() throws IOException {
        if (server != null) return;
        initMina(ctx);
        SshServer sshd = SshServer.setUpDefaultServer();
        sshd.setPort(Protocol.SSH_PORT);
        keyProvider = new SimpleGeneratorHostKeyProvider(new File(ctx.getFilesDir(), "ssh_host_key.ser").toPath());
        sshd.setKeyPairProvider(keyProvider);
        sshd.setPasswordAuthenticator((user, password, session) ->
                NetUtil.isLocalAddress(session.getIoSession().getRemoteAddress())
                        && user.equals(bridge.sshUser())
                        && MessageDigest.isEqual(password.getBytes(StandardCharsets.UTF_8),
                        bridge.sshPassword().getBytes(StandardCharsets.UTF_8)));
        sshd.setShellFactory(new ProcessShellFactory("/system/bin/sh", "/system/bin/sh", "-i"));
        File root = ctx.getExternalFilesDir(null);
        if (root == null) root = ctx.getFilesDir();
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
        sshd.setFileSystemFactory(new VirtualFileSystemFactory(root.toPath()));
        sshd.setSubsystemFactories(Collections.singletonList(new SftpSubsystemFactory()));
        sshd.setIoServiceEventListener(new IoServiceEventListener() {
            @Override
            public void connectionAccepted(IoAcceptor acceptor, SocketAddress local, SocketAddress remote,
                                           SocketAddress service) throws IOException {
                if (!NetUtil.isLocalAddress(remote)) throw new IOException("SSH csak helyi hálózatról: " + remote);
            }
        });
        sshd.start();
        server = sshd;
    }

    synchronized void stop() {
        if (server == null) return;
        try {
            server.stop(true);
        } catch (IOException ignored) {
        }
        server = null;
    }

    synchronized boolean isRunning() {
        return server != null && server.isStarted();
    }

    synchronized String hostKeyFingerprint() {
        if (keyProvider == null) return "";
        try {
            for (KeyPair kp : keyProvider.loadKeys(null)) return KeyUtils.getFingerPrint(kp.getPublic());
        } catch (Exception ignored) {
        }
        return "";
    }
}
