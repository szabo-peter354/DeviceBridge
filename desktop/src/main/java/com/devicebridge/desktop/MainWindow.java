package com.devicebridge.desktop;

import com.devicebridge.common.BridgeNode;
import com.devicebridge.common.Discovery;
import com.devicebridge.common.NetUtil;
import com.devicebridge.common.PeerInfo;
import com.devicebridge.common.Protocol;
import com.devicebridge.common.crypto.TrustStore;
import com.devicebridge.common.feature.ControlService;
import com.devicebridge.common.feature.DeviceStatus;
import com.devicebridge.common.feature.FileTransferService;
import com.devicebridge.desktop.ui.Icons;
import com.devicebridge.desktop.ui.RemoteViewerWindow;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.WindowConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;

/** A PC alkalmazás főablaka és tálcaikonja. */
final class MainWindow extends JFrame implements DesktopApp.Ui {
    private final DesktopApp app;

    private final JLabel statusLabel = new JLabel();
    private final JButton disconnectButton = new JButton("Bontás");
    private final JLabel peerLabel = new JLabel(" ");
    private final List<JComponent> connectedOnly = new ArrayList<>();

    private final DefaultListModel<Discovery.Device> lanModel = new DefaultListModel<>();
    private final JList<Discovery.Device> lanList = new JList<>(lanModel);
    private final JTextField hostField = new JTextField(18);
    private final JComboBox<AdbHelper.Device> usbCombo = new JComboBox<>();
    /** Párosított Bluetooth eszközök (közvetlen RFCOMM) és – tartalékként – Bluetooth COM portok. */
    private final JComboBox<Object> btCombo = new JComboBox<>();

    private final TransferModel transferModel = new TransferModel();
    private final DefaultListModel<TrustStore.TrustedDevice> pairedModel = new DefaultListModel<>();
    private final JTextArea logArea = new JTextArea();
    private final JTextArea ownInfo = new JTextArea();

    private TrayIcon trayIcon;
    private boolean trayHintShown;
    private RemoteViewerWindow viewer;

    MainWindow(DesktopApp app) {
        super("DeviceBridge");
        this.app = app;
        setIconImages(Icons.appIcons());
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (trayIcon != null) {
                    setVisible(false);
                    if (!trayHintShown) {
                        trayHintShown = true;
                        trayIcon.displayMessage("DeviceBridge", "A DeviceBridge a háttérben tovább fut. Kilépés: tálcaikon → Kilépés.", TrayIcon.MessageType.INFO);
                    }
                } else {
                    exit();
                }
            }
        });

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 14f));
        top.add(statusLabel, BorderLayout.CENTER);
        disconnectButton.addActionListener(e -> app.disconnect());
        top.add(disconnectButton, BorderLayout.EAST);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Kapcsolódás", wrap(buildConnectTab()));
        tabs.addTab("Vezérlés", wrap(buildControlTab()));
        tabs.addTab("Fájlok", buildFilesTab());
        tabs.addTab("Párosított eszközök", buildPairedTab());
        tabs.addTab("Beállítások", wrap(buildSettingsTab()));
        tabs.addTab("Napló", buildLogTab());

        getContentPane().add(top, BorderLayout.NORTH);
        getContentPane().add(tabs, BorderLayout.CENTER);
        setSize(860, 640);
        setMinimumSize(new Dimension(700, 520));
        setLocationRelativeTo(null);

        app.node.addListener(new BridgeNode.Listener() {
            @Override
            public void onStateChanged(BridgeNode.State state, PeerInfo peer) {
                SwingUtilities.invokeLater(MainWindow.this::refreshState);
            }

            @Override
            public void onLog(String line) {
                SwingUtilities.invokeLater(() -> {
                    logArea.append(line + "\n");
                    logArea.setCaretPosition(logArea.getDocument().getLength());
                });
            }
        });
        app.files.addListener(t -> SwingUtilities.invokeLater(transferModel::refresh));
        for (String l : app.node.logHistory()) logArea.append(l + "\n");

        setupTray();
        refreshState();
        refreshOwnInfo();
        refreshPaired();
        refreshUsb();
        refreshSerial();
    }

    private static JScrollPane wrap(JComponent c) {
        JPanel holder = new JPanel(new BorderLayout());
        holder.add(c, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(holder);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    private static JPanel section(String title, JComponent content) {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(6, 10, 6, 10),
                BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(title),
                        BorderFactory.createEmptyBorder(6, 8, 8, 8))));
        p.add(content, BorderLayout.CENTER);
        return p;
    }

    private static JLabel hint(String html) {
        JLabel l = new JLabel("<html><div style='width:560px'>" + html + "</div></html>");
        l.setForeground(new Color(0x6B7280));
        return l;
    }

    private static JPanel row(Component... cs) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        for (Component c : cs) p.add(c);
        return p;
    }

    private static JButton button(String text, Runnable r) {
        JButton b = new JButton(text);
        b.addActionListener(e -> r.run());
        return b;
    }

    // =================================================================== Kapcsolódás

    private JComponent buildConnectTab() {
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));

        ownInfo.setEditable(false);
        ownInfo.setOpaque(false);
        ownInfo.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        root.add(section("Ez a számítógép", ownInfo));

        // LAN / internet
        JPanel lan = new JPanel(new BorderLayout(6, 6));
        lanList.setVisibleRowCount(4);
        lan.add(new JScrollPane(lanList), BorderLayout.CENTER);
        JPanel lanButtons = new JPanel(new GridLayout(0, 1, 4, 4));
        JButton scan = new JButton("Keresés");
        scan.addActionListener(e -> scanLan(scan));
        lanButtons.add(scan);
        lanButtons.add(button("Kapcsolódás", () -> {
            Discovery.Device d = lanList.getSelectedValue();
            if (d == null) { info("Válassz egy eszközt a listából (előbb: Keresés)."); return; }
            app.connectNetwork(d.address.getHostAddress(), d.port);
        }));
        JPanel lanEast = new JPanel(new BorderLayout());
        lanEast.add(lanButtons, BorderLayout.NORTH);
        lan.add(lanEast, BorderLayout.EAST);
        JPanel manual = row(new JLabel("Cím (IP vagy host[:port]):"), hostField, button("Kapcsolódás", () -> {
            String h = hostField.getText().trim();
            if (h.isEmpty()) return;
            try {
                String[] hp = NetUtil.splitHostPort(h, Protocol.TCP_PORT);
                app.connectNetwork(hp[0], Integer.parseInt(hp[1]));
            } catch (NumberFormatException ex) {
                info("Hibás port: " + h);
            }
        }));
        JPanel lanSouth = new JPanel(new BorderLayout());
        lanSouth.add(manual, BorderLayout.NORTH);
        lanSouth.add(hint("Wi-Fi / LAN, Bluetooth PAN (Bluetooth-hálózat) és USB-tethering esetén a keresés megtalálja a telefont. "
                + "Internetes kapcsolathoz (pl. VPN-en át) írd be a címet; a kapcsolat végponttól végpontig titkosított."), BorderLayout.SOUTH);
        lan.add(lanSouth, BorderLayout.SOUTH);
        root.add(section("Hálózat (LAN / PAN / internet)", lan));

        // USB
        JPanel usb = new JPanel(new BorderLayout());
        usbCombo.setPrototypeDisplayValue(new AdbHelper.Device("xxxxxxxxxxxxxxxx", "Telefon modell neve hosszú"));
        usb.add(row(usbCombo, button("Frissítés", this::refreshUsb), button("Kapcsolódás", () -> {
            AdbHelper.Device d = (AdbHelper.Device) usbCombo.getSelectedItem();
            if (d == null) { info("Nincs USB-n csatlakoztatott telefon."); return; }
            app.connectUsb(d);
        })), BorderLayout.NORTH);
        usb.add(hint("A telefonon kapcsold be a Fejlesztői beállítások → USB-hibakeresés opciót, és engedélyezd a számítógépet. "
                + "A kapcsolat az ADB port-továbbításon keresztül megy, az SSH is elérhető rajta."), BorderLayout.SOUTH);
        root.add(section("USB kábel", usb));

        // Bluetooth
        JPanel bt = new JPanel(new BorderLayout());
        btCombo.setPrototypeDisplayValue("Telefon neve hosszabb – 00:00:00:00:00:00");
        bt.add(row(btCombo, button("Frissítés", this::refreshSerial), button("Kapcsolódás", () -> {
            Object sel = btCombo.getSelectedItem();
            if (sel instanceof BluetoothRfcomm.Device) {
                app.connectRfcomm((BluetoothRfcomm.Device) sel);
            } else if (sel instanceof SerialLink.PortInfo) {
                app.connectSerial((SerialLink.PortInfo) sel);
            } else {
                info("Nincs párosított Bluetooth eszköz. Párosítsd a telefont a Windows Bluetooth beállításaiban, majd nyomd meg a Frissítés gombot.");
            }
        })), BorderLayout.NORTH);
        bt.add(hint("Párosítsd a telefont a Windows Bluetooth beállításaiban, a telefonon fusson a DeviceBridge (Bluetooth engedéllyel), "
                + "majd válaszd ki a listából. COM port nem kell – internet és Wi-Fi nélkül is működik. "
                + "Gyorsabb kapcsolathoz használhatsz Bluetooth PAN-t is (akkor a Hálózat részt használd)."), BorderLayout.SOUTH);
        root.add(section("Bluetooth", bt));
        return root;
    }

    private void scanLan(JButton scan) {
        scan.setEnabled(false);
        scan.setText("Keresés…");
        new SwingWorker<List<Discovery.Device>, Void>() {
            @Override
            protected List<Discovery.Device> doInBackground() {
                return Discovery.scan(2000, app.identity.fingerprint());
            }

            @Override
            protected void done() {
                scan.setEnabled(true);
                scan.setText("Keresés");
                lanModel.clear();
                try {
                    for (Discovery.Device d : get()) lanModel.addElement(d);
                } catch (Exception ignored) {
                }
                if (lanModel.isEmpty()) app.node.log("A keresés nem talált eszközt (fut a DeviceBridge a telefonon, azonos hálózaton vagytok?)");
                else lanList.setSelectedIndex(0);
            }
        }.execute();
    }

    private void refreshUsb() {
        new SwingWorker<List<AdbHelper.Device>, Void>() {
            String error;

            @Override
            protected List<AdbHelper.Device> doInBackground() {
                try {
                    return app.adb.devices();
                } catch (IOException e) {
                    error = e.getMessage();
                    return new ArrayList<>();
                }
            }

            @Override
            protected void done() {
                usbCombo.removeAllItems();
                try {
                    for (AdbHelper.Device d : get()) usbCombo.addItem(d);
                } catch (Exception ignored) {
                }
                if (error != null) app.node.log("USB: " + error);
            }
        }.execute();
    }

    private void refreshSerial() {
        new SwingWorker<List<Object>, Void>() {
            @Override
            protected List<Object> doInBackground() {
                List<Object> items = new ArrayList<>();
                try {
                    items.addAll(BluetoothRfcomm.pairedDevices());
                } catch (Throwable t) {
                    app.node.log("Párosított Bluetooth eszközök nem listázhatók: " + t.getMessage());
                }
                try {
                    // Tartalék: csak a ténylegesen létező Bluetooth COM portok
                    for (SerialLink.PortInfo p : SerialLink.listPorts()) if (p.bluetooth) items.add(p);
                } catch (Throwable t) {
                    app.node.log("Soros portok nem listázhatók: " + t.getMessage());
                }
                return items;
            }

            @Override
            protected void done() {
                btCombo.removeAllItems();
                try {
                    for (Object o : get()) btCombo.addItem(o);
                } catch (Exception ignored) {
                }
            }
        }.execute();
    }

    private void refreshOwnInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("Név:          ").append(app.settings.deviceName()).append('\n');
        sb.append("Ujjlenyomat:  ").append(app.identity.fingerprint()).append('\n');
        sb.append("Port:         ").append(Protocol.TCP_PORT).append(" (TCP)\n");
        sb.append("IP-címek:     ");
        List<String> ips = new ArrayList<>();
        for (InetAddress a : NetUtil.localIPv4Addresses()) ips.add(a.getHostAddress());
        sb.append(ips.isEmpty() ? "–" : String.join(", ", ips));
        ownInfo.setText(sb.toString());
    }

    // =================================================================== Vezérlés

    private JComponent buildControlTab() {
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));

        JPanel peer = new JPanel(new BorderLayout());
        peer.add(peerLabel, BorderLayout.CENTER);
        root.add(section("Kapcsolódott eszköz", peer));

        JPanel main = new JPanel(new GridLayout(0, 3, 8, 8));
        main.add(ctl("Telefon képernyője (távoli asztal)", this::openViewer));
        main.add(ctl("Fájl küldése…", this::chooseAndSendFiles));
        main.add(ctl("Vágólap küldése", this::sendClipboard));
        main.add(ctl("Link megnyitása a telefonon…", () -> {
            String url = JOptionPane.showInputDialog(this, "Megnyitandó link (http/https):", "https://");
            if (url != null && !url.trim().isEmpty()) app.control.openUrl(url.trim());
        }));
        main.add(ctl("Üzenet küldése…", () -> {
            String text = JOptionPane.showInputDialog(this, "Üzenet a telefonra:");
            if (text != null && !text.trim().isEmpty()) app.control.notify(app.settings.deviceName(), text.trim());
        }));
        main.add(ctl("SSH terminál a telefonra", this::openSsh));
        main.add(ctl("Telefon csörgetése", () -> app.control.ring(true)));
        main.add(ctl("Csörgés leállítása", () -> app.control.ring(false)));
        main.add(ctl("Telefon zárolása", app.control::lock));
        root.add(section("Műveletek", main));

        JPanel media = new JPanel(new GridLayout(1, 0, 8, 8));
        media.add(ctl("Előző", () -> app.control.media(ControlService.MEDIA_PREVIOUS)));
        media.add(ctl("Lejátszás / Szünet", () -> app.control.media(ControlService.MEDIA_PLAY_PAUSE)));
        media.add(ctl("Következő", () -> app.control.media(ControlService.MEDIA_NEXT)));
        media.add(ctl("Halkítás", () -> app.control.media(ControlService.VOLUME_DOWN)));
        media.add(ctl("Hangosítás", () -> app.control.media(ControlService.VOLUME_UP)));
        media.add(ctl("Némítás", () -> app.control.media(ControlService.VOLUME_MUTE)));
        root.add(section("Média a telefonon", media));
        root.add(section("Tipp", hint("A telefon vezérléséhez (érintés, gépelés, zárolás) a telefonon engedélyezni kell a "
                + "DeviceBridge akadálymentesítési szolgáltatását; a képernyő megosztását a telefon minden alkalommal megerősítteti.")));
        return root;
    }

    private JButton ctl(String text, Runnable r) {
        JButton b = button(text, r);
        b.setMargin(new Insets(10, 8, 10, 8));
        connectedOnly.add(b);
        return b;
    }

    private void openViewer() {
        PeerInfo p = app.node.peer();
        if (p == null) return;
        if (viewer != null && viewer.isDisplayable()) {
            viewer.toFront();
            return;
        }
        viewer = new RemoteViewerWindow(app.remote, p.name);
        viewer.start();
    }

    private void sendClipboard() {
        try {
            Object data = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            if (data instanceof String && !((String) data).isEmpty()) {
                app.control.sendClipboard((String) data);
                app.node.log("Vágólap elküldve (" + ((String) data).length() + " karakter)");
            } else {
                info("A vágólapon nincs szöveg.");
            }
        } catch (Exception e) {
            info("A vágólapon nincs szöveg.");
        }
    }

    private void openSsh() {
        new SwingWorker<DesktopApp.SshTarget, Void>() {
            String error;

            @Override
            protected DesktopApp.SshTarget doInBackground() {
                try {
                    return app.findPeerSsh();
                } catch (IOException e) {
                    error = e.getMessage();
                    return null;
                }
            }

            @Override
            protected void done() {
                DesktopApp.SshTarget t;
                try { t = get(); } catch (Exception e) { t = null; }
                if (t == null) {
                    info(error);
                    return;
                }
                try {
                    app.launchSshTerminal(t);
                } catch (IOException e) {
                    app.node.log("Terminál nem indítható: " + e.getMessage());
                }
                JTextArea ta = new JTextArea("Parancs:     " + t.command()
                        + "\nFelhasználó: " + t.info.user
                        + "\nJelszó:      " + t.info.password + "   (vágólapra másolva)"
                        + "\nKulcs:       " + t.info.hostKeyFingerprint
                        + "\n\nSFTP is elérhető ugyanezekkel az adatokkal (pl. WinSCP, FileZilla).");
                ta.setEditable(false);
                ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                JOptionPane.showMessageDialog(MainWindow.this, ta, "SSH a telefonra", JOptionPane.INFORMATION_MESSAGE);
            }
        }.execute();
    }

    // =================================================================== Fájlok

    private JComponent buildFilesTab() {
        JPanel root = new JPanel(new BorderLayout(6, 6));
        root.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        JTable table = new JTable(transferModel);
        table.setRowHeight(24);
        table.getColumnModel().getColumn(0).setMaxWidth(70);
        table.getColumnModel().getColumn(2).setMaxWidth(100);
        table.getColumnModel().getColumn(3).setCellRenderer(new ProgressRenderer());
        table.getColumnModel().getColumn(3).setMaxWidth(140);
        JScrollPane sp = new JScrollPane(table);

        TransferHandler dnd = new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport s) {
                return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport s) {
                try {
                    List<File> files = (List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    sendFiles(files.toArray(new File[0]));
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        };
        table.setTransferHandler(dnd);
        sp.setTransferHandler(dnd);
        table.setFillsViewportHeight(true);

        JButton send = button("Fájl küldése…", this::chooseAndSendFiles);
        connectedOnly.add(send);
        JPanel top = row(send,
                button("Letöltési mappa megnyitása", () -> {
                    File d = app.settings.downloadDir();
                    //noinspection ResultOfMethodCallIgnored
                    d.mkdirs();
                    try { Desktop.getDesktop().open(d); } catch (Exception e) { info(d.getAbsolutePath()); }
                }),
                button("Kijelölt megszakítása", () -> {
                    int r = table.getSelectedRow();
                    if (r >= 0) app.files.cancel(transferModel.get(r));
                }));
        root.add(top, BorderLayout.NORTH);
        root.add(sp, BorderLayout.CENTER);
        root.add(hint("Fájlokat ide is húzhatsz. A fogadott fájlok helye: " + app.settings.downloadDir().getAbsolutePath()), BorderLayout.SOUTH);
        return root;
    }

    private void chooseAndSendFiles() {
        if (!app.node.isConnected()) { info("Nincs kapcsolódott eszköz."); return; }
        JFileChooser fc = new JFileChooser();
        fc.setMultiSelectionEnabled(true);
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) sendFiles(fc.getSelectedFiles());
    }

    private void sendFiles(File[] files) {
        if (!app.node.isConnected()) { info("Nincs kapcsolódott eszköz."); return; }
        for (File f : files) {
            if (!f.isFile()) continue;
            app.files.sendFile(f.getName(), f.length(), () -> new FileInputStream(f));
        }
    }

    private static String size(long b) {
        if (b < 0) return "?";
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.1f MB", b / 1048576.0);
        return String.format("%.2f GB", b / 1073741824.0);
    }

    private final class TransferModel extends AbstractTableModel {
        private List<FileTransferService.Transfer> rows = new ArrayList<>();
        private final String[] cols = {"Irány", "Fájl", "Méret", "Haladás", "Állapot"};

        void refresh() {
            rows = app.files.history();
            fireTableDataChanged();
        }

        FileTransferService.Transfer get(int r) {
            return rows.get(r);
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }

        @Override
        public Object getValueAt(int r, int c) {
            FileTransferService.Transfer t = rows.get(r);
            switch (c) {
                case 0: return t.incoming ? "Fogadás" : "Küldés";
                case 1: return t.name;
                case 2: return size(t.size);
                case 3: return t.percent();
                default:
                    switch (t.status()) {
                        case WAITING: return "Várakozik";
                        case RUNNING: return "Folyamatban";
                        case DONE: return t.incoming ? "Kész – " + t.message() : "Elküldve";
                        case CANCELLED: return "Megszakítva";
                        default: return "Hiba: " + t.message();
                    }
            }
        }
    }

    private static final class ProgressRenderer extends JProgressBar implements TableCellRenderer {
        ProgressRenderer() {
            super(0, 100);
            setStringPainted(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            setValue(value instanceof Integer ? (Integer) value : 0);
            return this;
        }
    }

    // =================================================================== Párosított eszközök

    private JComponent buildPairedTab() {
        JPanel root = new JPanel(new BorderLayout(6, 6));
        root.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        JList<TrustStore.TrustedDevice> list = new JList<>(pairedModel);
        root.add(new JScrollPane(list), BorderLayout.CENTER);
        root.add(row(button("Frissítés", this::refreshPaired), button("Párosítás törlése", () -> {
            TrustStore.TrustedDevice d = list.getSelectedValue();
            if (d == null) return;
            if (JOptionPane.showConfirmDialog(this, "Törlöd a párosítást ezzel: " + d.name + "?\nKövetkező kapcsolódáskor újra meg kell erősíteni a kódot.",
                    "Párosítás törlése", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                app.trust.remove(d.fingerprint);
                refreshPaired();
            }
        })), BorderLayout.NORTH);
        root.add(hint("Csak párosított eszköz kapcsolódhat. Az első kapcsolódáskor mindkét eszközön ugyanaz a 6 jegyű kód jelenik meg – ezt kell jóváhagyni."), BorderLayout.SOUTH);
        return root;
    }

    private void refreshPaired() {
        pairedModel.clear();
        for (TrustStore.TrustedDevice d : app.trust.list()) pairedModel.addElement(d);
    }

    // =================================================================== Beállítások

    private JComponent buildSettingsTab() {
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        JTextField name = new JTextField(app.settings.deviceName(), 24);
        JCheckBox remoteAllow = new JCheckBox("Távoli asztal engedélyezése (a párosított telefon láthatja és vezérelheti a képernyőt)", app.settings.allowRemoteControl());
        JCheckBox sshEnabled = new JCheckBox("SSH + SFTP szerver (port " + Protocol.SSH_PORT + ", csak helyi hálózatról / USB-ről)", app.settings.sshEnabled());
        JLabel sshCreds = new JLabel();
        Supplier<String> creds = () -> "Felhasználó: " + app.settings.sshUser() + "    Jelszó: " + app.settings.sshPassword();
        sshCreds.setText(creds.get());
        sshCreds.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JTextField downloadDir = new JTextField(app.settings.downloadDir().getAbsolutePath(), 30);
        JTextField sshStartDir = new JTextField(app.settings.sshStartDir().getAbsolutePath(), 30);
        JCheckBox autostart = new JCheckBox("Indítás a Windows-zal (a tálcán)", app.isAutostartEnabled());
        autostart.setEnabled(SystemActions.isWindows());
        JCheckBox startMin = new JCheckBox("Indításkor csak a tálcaikon jelenjen meg", app.settings.startMinimized());

        int y = 0;
        c.gridx = 0; c.gridy = y; form.add(new JLabel("Eszköz neve:"), c);
        c.gridx = 1; form.add(name, c);
        c.gridx = 0; c.gridy = ++y; c.gridwidth = 2; form.add(remoteAllow, c);
        c.gridy = ++y; form.add(sshEnabled, c);
        c.gridy = ++y; form.add(row(sshCreds, button("Új jelszó", () -> {
            app.settings.regenerateSshPassword();
            app.settings.save();
            sshCreds.setText(creds.get());
            app.control.sendSshInfo();
        })), c);
        c.gridwidth = 1;
        c.gridx = 0; c.gridy = ++y; form.add(new JLabel("SSH kezdőmappa:"), c);
        c.gridx = 1; form.add(row(sshStartDir, button("Tallózás…", () -> {
            JFileChooser fc = new JFileChooser(sshStartDir.getText());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) sshStartDir.setText(fc.getSelectedFile().getAbsolutePath());
        })), c);
        c.gridx = 0; c.gridy = ++y; form.add(new JLabel("Letöltési mappa:"), c);
        c.gridx = 1; form.add(row(downloadDir, button("Tallózás…", () -> {
            JFileChooser fc = new JFileChooser(downloadDir.getText());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) downloadDir.setText(fc.getSelectedFile().getAbsolutePath());
        })), c);
        c.gridx = 0; c.gridy = ++y; c.gridwidth = 2; form.add(autostart, c);
        c.gridy = ++y; form.add(startMin, c);
        c.gridy = ++y; form.add(Box.createVerticalStrut(8), c);
        c.gridy = ++y; c.fill = GridBagConstraints.NONE;
        form.add(button("Mentés", () -> {
            String n = name.getText().trim();
            if (!n.isEmpty()) app.settings.setDeviceName(n);
            app.settings.setAllowRemoteControl(remoteAllow.isSelected());
            if (!remoteAllow.isSelected()) app.remote.stopHost("A távoli asztalt letiltották");
            app.settings.setDownloadDir(new File(downloadDir.getText().trim()));
            File sshDir = new File(sshStartDir.getText().trim());
            if (sshDir.isDirectory()) {
                app.settings.setSshStartDir(sshDir);
            } else {
                info("Az SSH kezdőmappa nem létezik, a felhasználói mappa marad: " + app.settings.sshStartDir());
                sshStartDir.setText(app.settings.sshStartDir().getAbsolutePath());
            }
            app.settings.setStartMinimized(startMin.isSelected());
            boolean sshWas = app.settings.sshEnabled();
            app.settings.setSshEnabled(sshEnabled.isSelected());
            app.settings.save();
            if (sshEnabled.isSelected() && !sshWas) app.startSsh();
            if (!sshEnabled.isSelected() && sshWas) app.stopSsh();
            if (autostart.isEnabled() && autostart.isSelected() != app.isAutostartEnabled()) {
                try {
                    app.setAutostart(autostart.isSelected());
                } catch (IOException e) {
                    info(e.getMessage());
                    autostart.setSelected(app.isAutostartEnabled());
                }
            }
            refreshOwnInfo();
            app.node.log("Beállítások mentve");
        }), c);
        return section("Beállítások", form);
    }

    // =================================================================== Napló

    private JComponent buildLogTab() {
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane sp = new JScrollPane(logArea);
        sp.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        return sp;
    }

    // =================================================================== állapot

    private void refreshState() {
        BridgeNode.State st = app.node.state();
        PeerInfo p = app.node.peer();
        String text;
        Color color;
        switch (st) {
            case CONNECTED:
                text = "Kapcsolódva: " + (p == null ? "?" : p.name + "  ·  " + p.transportDescription);
                color = new Color(0x16A34A);
                break;
            case CONNECTING:
                text = "Kapcsolódás folyamatban…";
                color = new Color(0xF59E0B);
                break;
            default:
                text = "Nincs kapcsolat – várakozás (port " + Protocol.TCP_PORT + ")";
                color = new Color(0x9CA3AF);
        }
        statusLabel.setText(text);
        statusLabel.setIcon(new ImageIcon(Icons.dot(color)));
        boolean connected = st == BridgeNode.State.CONNECTED;
        disconnectButton.setEnabled(connected);
        for (JComponent c : connectedOnly) c.setEnabled(connected);
        if (trayIcon != null) trayIcon.setToolTip("DeviceBridge – " + text);
        peerInfoChangedNow();
        if (connected) refreshPaired();
    }

    private void peerInfoChangedNow() {
        PeerInfo p = app.node.peer();
        if (p == null) {
            peerLabel.setText("Nincs kapcsolódott eszköz. Kapcsolódj a \"Kapcsolódás\" fülön, vagy indítsd a kapcsolatot a telefonról.");
            return;
        }
        DeviceStatus s = app.control.peerStatus();
        StringBuilder sb = new StringBuilder("<html><b>").append(p.name).append("</b> – ").append(p.transportDescription);
        sb.append("<br>Ujjlenyomat: ").append(p.fingerprint);
        if (s != null) sb.append("<br>").append(s);
        sb.append("<br>SSH a telefonon: ").append(app.control.peerSshInfo() != null && app.control.peerSshInfo().enabled ? "bekapcsolva" : "kikapcsolva");
        if (app.remote.isHosting()) sb.append("<br><font color='#DC2626'>A telefon most látja ennek a gépnek a képernyőjét.</font>");
        sb.append("</html>");
        peerLabel.setText(sb.toString());
    }

    @Override
    public void peerInfoChanged() {
        SwingUtilities.invokeLater(this::peerInfoChangedNow);
    }

    @Override
    public void notifyUser(String title, String message) {
        app.node.log(title + ": " + message);
        if (trayIcon != null) {
            trayIcon.displayMessage(title, message, TrayIcon.MessageType.INFO);
        }
    }

    @Override
    public CompletableFuture<Boolean> confirmPairing(PeerInfo peer, String code) {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            showWindow();
            String msg = "<html>Új eszköz szeretne párosítani:<br><b>" + esc(peer.name) + "</b> (" + esc(peer.transportDescription) + ")"
                    + "<br>Ujjlenyomat: " + peer.fingerprint
                    + "<br><br>Ellenőrizd, hogy a másik eszközön is ez a kód látható:<br><br>"
                    + "<span style='font-size:28pt'><b>" + code + "</b></span><br><br>Párosítod az eszközt?</html>";
            JOptionPane pane = new JOptionPane(msg, JOptionPane.QUESTION_MESSAGE, JOptionPane.YES_NO_OPTION);
            JDialog d = pane.createDialog(this, "DeviceBridge – Párosítás");
            d.setModal(false);
            d.setAlwaysOnTop(true);
            pane.addPropertyChangeListener(JOptionPane.VALUE_PROPERTY, e -> {
                Object v = pane.getValue();
                if (v == JOptionPane.UNINITIALIZED_VALUE) return;
                f.complete(Integer.valueOf(JOptionPane.YES_OPTION).equals(v));
                d.dispose();
            });
            d.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    f.complete(false);
                }
            });
            f.whenComplete((r, ex) -> SwingUtilities.invokeLater(d::dispose));
            d.setVisible(true);
            if (trayIcon != null) trayIcon.displayMessage("Párosítás", peer.name + " – kód: " + code, TrayIcon.MessageType.INFO);
        });
        return f;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void info(String msg) {
        JOptionPane.showMessageDialog(this, msg, "DeviceBridge", JOptionPane.INFORMATION_MESSAGE);
    }

    // =================================================================== tálca

    private void setupTray() {
        if (!SystemTray.isSupported()) return;
        PopupMenu menu = new PopupMenu();
        MenuItem open = new MenuItem("Megnyitás");
        open.addActionListener(e -> showWindow());
        MenuItem disc = new MenuItem("Kapcsolat bontása");
        disc.addActionListener(e -> app.disconnect());
        MenuItem quit = new MenuItem("Kilépés");
        quit.addActionListener(e -> exit());
        menu.add(open);
        menu.add(disc);
        menu.addSeparator();
        menu.add(quit);
        TrayIcon icon = new TrayIcon(Icons.app(64), "DeviceBridge", menu);
        icon.setImageAutoSize(true);
        icon.addActionListener(e -> showWindow());
        try {
            SystemTray.getSystemTray().add(icon);
            trayIcon = icon;
        } catch (Exception e) {
            app.node.log("Tálcaikon nem hozható létre: " + e.getMessage());
        }
    }

    boolean hasTray() {
        return trayIcon != null;
    }

    void showWindow() {
        setVisible(true);
        setExtendedState(JFrame.NORMAL);
        toFront();
        requestFocus();
    }

    private void exit() {
        if (trayIcon != null) SystemTray.getSystemTray().remove(trayIcon);
        app.shutdown();
        dispose();
        System.exit(0);
    }
}
