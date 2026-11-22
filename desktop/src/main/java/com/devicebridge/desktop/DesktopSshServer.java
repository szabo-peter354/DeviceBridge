package com.devicebridge.desktop;

import com.devicebridge.common.NetUtil;
import com.devicebridge.common.Protocol;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.io.IoAcceptor;
import org.apache.sshd.common.io.IoServiceEventListener;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.shell.InteractiveProcessShellFactory;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

import java.io.File;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.Collections;

/**
 * Beépített SSH + SFTP szerver. Kizárólag helyi hálózatról (LAN, Bluetooth PAN, USB) fogad
 * kapcsolatot – internet felől érkező kapcsolatot azonnal elutasít.
 */
final class DesktopSshServer {
    private final File dataDir;
    private final DesktopSettings settings;
    private SshServer server;
    private SimpleGeneratorHostKeyProvider keyProvider;

    DesktopSshServer(File dataDir, DesktopSettings settings) {
        this.dataDir = dataDir;
        this.settings = settings;
    }

    synchronized void start() throws IOException {
        if (server != null) return;
        SshServer sshd = SshServer.setUpDefaultServer();
        sshd.setPort(Protocol.SSH_PORT);
        keyProvider = new SimpleGeneratorHostKeyProvider(new File(dataDir, "ssh_host_key.ser").toPath());
        sshd.setKeyPairProvider(keyProvider);
        sshd.setPasswordAuthenticator((user, password, session) ->
                NetUtil.isLocalAddress(session.getIoSession().getRemoteAddress())
                        && user.equals(settings.sshUser())
                        && MessageDigest.isEqual(password.getBytes(StandardCharsets.UTF_8),
                        settings.sshPassword().getBytes(StandardCharsets.UTF_8)));
        sshd.setShellFactory(InteractiveProcessShellFactory.INSTANCE);
        sshd.setSubsystemFactories(Collections.singletonList(new SftpSubsystemFactory()));
        sshd.setIoServiceEventListener(new IoServiceEventListener() {
            @Override
            public void connectionAccepted(IoAcceptor acceptor, SocketAddress local, SocketAddress remote,
                                           SocketAddress service) throws IOException {
                if (!NetUtil.isLocalAddress(remote)) {
                    throw new IOException("SSH csak helyi hálózatról engedélyezett: " + remote);
                }
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
            for (KeyPair kp : keyProvider.loadKeys(null)) {
                return KeyUtils.getFingerPrint(kp.getPublic());
            }
        } catch (Exception ignored) {
        }
        return "";
    }
}
