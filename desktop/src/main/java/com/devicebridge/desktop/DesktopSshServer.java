package com.devicebridge.desktop;

import com.devicebridge.common.NetUtil;
import com.devicebridge.common.Protocol;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.io.IoAcceptor;
import org.apache.sshd.common.io.IoServiceEventListener;
import org.apache.sshd.common.util.OsUtils;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.shell.ProcessShellFactory;
import org.apache.sshd.server.shell.ShellFactory;
import org.apache.sshd.sftp.server.SftpEventListener;
import org.apache.sshd.sftp.server.SftpSubsystem;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

import java.io.File;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
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
        // A shell és az SFTP a beállított kezdőmappában induljon, ne a program (exe) mappájában
        sshd.setShellFactory(channel -> shellFactory(settings.sshStartDir()).createShell(channel));
        sshd.setSubsystemFactories(Collections.singletonList(new StartDirSftpFactory(settings)));
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

    /**
     * A MINA SSHD a shellt a JVM munkamappájában (az exe mappájában) indítaná, és a ProcessBuildernek
     * nem adható meg mappa, ezért a shell maga vált át a kezdőmappába.
     */
    private static ShellFactory shellFactory(File dir) {
        String path = dir.getAbsolutePath();
        if (OsUtils.isWin32()) {
            // cmd /K "cd /d "<mappa>"" – a cmd a külső idézőjeleket levágja, a belsők védik a szóközt és a &-t
            return new ProcessShellFactory("cmd.exe", "cmd.exe", "/K", "cd /d \"" + path + "\"");
        }
        String quoted = "'" + path.replace("'", "'\\''") + "'";
        return new ProcessShellFactory("/bin/sh", "/bin/sh", "-c",
                "cd -- " + quoted + " 2>/dev/null; exec \"${SHELL:-/bin/sh}\" -i");
    }

    /** SFTP, amelynek kiinduló mappája (a "." és a relatív utak alapja) a beállított kezdőmappa. */
    private static final class StartDirSftpFactory extends SftpSubsystemFactory {
        private final DesktopSettings settings;

        StartDirSftpFactory(DesktopSettings settings) {
            this.settings = settings;
        }

        @Override
        public Command createSubsystem(ChannelSession channel) throws IOException {
            Path start = settings.sshStartDir().toPath().toAbsolutePath().normalize();
            SftpSubsystem sub = new SftpSubsystem(channel, this) {
                @Override
                public void setFileSystem(FileSystem fs) {
                    super.setFileSystem(fs);
                    if (fs == FileSystems.getDefault()) defaultDir = start;
                }

                @Override
                public Path getDefaultDirectory() {
                    return fileSystem == FileSystems.getDefault() ? start : super.getDefaultDirectory();
                }
            };
            for (SftpEventListener l : getRegisteredListeners()) sub.addSftpEventListener(l);
            return sub;
        }
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
