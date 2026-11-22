package com.devicebridge.desktop.ui;

import com.devicebridge.common.feature.RemoteDesktopService;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/** A telefon képernyőjének megjelenítése és vezérlése egérrel/billentyűzettel. */
public final class RemoteViewerWindow extends JFrame implements RemoteDesktopService.Viewer {
    private static final int[] QUALITIES = {40, 65, 85};

    private final RemoteDesktopService remote;
    private final ScreenPanel panel = new ScreenPanel();
    private final JLabel status = new JLabel("Kapcsolódás a képernyőhöz…");
    private final JComboBox<String> quality = new JComboBox<>(new String[]{"Gyors (Bluetooth)", "Normál", "Éles"});
    private final Object lock = new Object();
    private BufferedImage frame;
    private int frameW = 1;
    private int frameH = 1;
    private boolean sized;
    private boolean stopped;

    public RemoteViewerWindow(RemoteDesktopService remote, String peerName) {
        super("DeviceBridge – " + peerName + " képernyője");
        this.remote = remote;
        setIconImages(Icons.appIcons());
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        bar.add(button("◀ Vissza", () -> remote.sendAction(RemoteDesktopService.ACTION_BACK)));
        bar.add(button("Kezdőlap", () -> remote.sendAction(RemoteDesktopService.ACTION_HOME)));
        bar.add(button("Alkalmazások", () -> remote.sendAction(RemoteDesktopService.ACTION_RECENTS)));
        bar.add(button("Értesítések", () -> remote.sendAction(RemoteDesktopService.ACTION_NOTIFICATIONS)));
        bar.add(button("Zárolás", () -> remote.sendAction(RemoteDesktopService.ACTION_LOCK)));
        bar.add(new JLabel("  Minőség:"));
        quality.setSelectedIndex(1);
        quality.addActionListener(e -> restart());
        bar.add(quality);

        JPanel south = new JPanel(new BorderLayout());
        south.add(status, BorderLayout.WEST);
        south.add(new JLabel("Kattintás = érintés · húzás = csúsztatás · jobb gomb = vissza · görgő = görgetés · gépelés = szöveg  "), BorderLayout.EAST);

        getContentPane().add(bar, BorderLayout.NORTH);
        getContentPane().add(panel, BorderLayout.CENTER);
        getContentPane().add(south, BorderLayout.SOUTH);
        setSize(480, 860);
        setLocationByPlatform(true);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                remote.stopViewing(RemoteViewerWindow.this);
            }

            @Override
            public void windowOpened(WindowEvent e) {
                panel.requestFocusInWindow();
            }
        });
    }

    public void start() {
        setVisible(true);
        restart();
    }

    private void restart() {
        int q = QUALITIES[Math.max(0, quality.getSelectedIndex())];
        int maxSize = q <= 40 ? 960 : 1600;
        int fps = q <= 40 ? 8 : 20;
        if (!remote.startViewing(this, maxSize, q, fps)) status.setText("Nincs kapcsolat");
        panel.requestFocusInWindow();
    }

    private JButton button(String text, Runnable r) {
        JButton b = new JButton(text);
        b.setFocusable(false);
        b.addActionListener(e -> {
            r.run();
            panel.requestFocusInWindow();
        });
        return b;
    }

    // ---------------------------------------------------------------- Viewer

    @Override
    public void onFrameInfo(int screenWidth, int screenHeight, int frameWidth, int frameHeight) {
        synchronized (lock) {
            frame = new BufferedImage(frameWidth, frameHeight, BufferedImage.TYPE_INT_RGB);
            frameW = frameWidth;
            frameH = frameHeight;
        }
        SwingUtilities.invokeLater(() -> {
            status.setText(" Képernyő: " + screenWidth + "×" + screenHeight + " (átvitel: " + frameWidth + "×" + frameHeight + ")");
            if (!sized) {
                sized = true;
                Rectangle avail = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
                double scale = Math.min(1.0, Math.min((avail.height - 140.0) / frameHeight, (avail.width - 40.0) / frameWidth));
                panel.setPreferredSize(new Dimension((int) (frameWidth * scale), (int) (frameHeight * scale)));
                pack();
            }
        });
    }

    @Override
    public void onTile(int x, int y, int w, int h, byte[] jpeg) {
        try {
            BufferedImage tile = ImageIO.read(new ByteArrayInputStream(jpeg));
            if (tile == null) return;
            synchronized (lock) {
                if (frame == null) return;
                Graphics g = frame.getGraphics();
                g.drawImage(tile, x, y, null);
                g.dispose();
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onFrameEnd() {
        panel.repaint();
    }

    @Override
    public void onStatus(String message) {
        SwingUtilities.invokeLater(() -> status.setText(" " + message));
    }

    @Override
    public void onRemoteStopped(String reason) {
        SwingUtilities.invokeLater(() -> {
            if (stopped) return;
            stopped = true;
            status.setText(" Leállítva: " + reason);
            status.setForeground(Color.RED);
        });
    }

    // ---------------------------------------------------------------- rajzolás és bemenet

    private final class ScreenPanel extends JPanel {
        private double scale = 1;
        private int ox;
        private int oy;

        ScreenPanel() {
            setBackground(Color.BLACK);
            setFocusable(true);
            setPreferredSize(new Dimension(420, 760));
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    int[] p = toFrame(e.getX(), e.getY());
                    if (p == null) return;
                    int button = SwingUtilities.isRightMouseButton(e) ? RemoteDesktopService.BUTTON_RIGHT
                            : SwingUtilities.isMiddleMouseButton(e) ? RemoteDesktopService.BUTTON_MIDDLE
                            : RemoteDesktopService.BUTTON_LEFT;
                    remote.sendMouseButton(button, true, p[0], p[1]);
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    int[] p = toFrame(e.getX(), e.getY());
                    if (p != null) remote.sendMouseMove(p[0], p[1]);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    int[] p = toFrame(e.getX(), e.getY());
                    if (p == null) p = new int[]{0, 0};
                    int button = SwingUtilities.isRightMouseButton(e) ? RemoteDesktopService.BUTTON_RIGHT
                            : SwingUtilities.isMiddleMouseButton(e) ? RemoteDesktopService.BUTTON_MIDDLE
                            : RemoteDesktopService.BUTTON_LEFT;
                    remote.sendMouseButton(button, false, p[0], p[1]);
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent e) {
                    int[] p = toFrame(e.getX(), e.getY());
                    if (p != null) remote.sendWheel(e.getWheelRotation(), p[0], p[1]);
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(mouse);
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    int code = e.getKeyCode();
                    switch (code) {
                        case KeyEvent.VK_BACK_SPACE:
                        case KeyEvent.VK_ENTER:
                        case KeyEvent.VK_ESCAPE:
                        case KeyEvent.VK_DELETE:
                        case KeyEvent.VK_TAB:
                        case KeyEvent.VK_LEFT:
                        case KeyEvent.VK_RIGHT:
                        case KeyEvent.VK_UP:
                        case KeyEvent.VK_DOWN:
                        case KeyEvent.VK_HOME:
                            remote.sendKeyPress(code);
                            e.consume();
                            break;
                        default:
                            break;
                    }
                }

                @Override
                public void keyTyped(KeyEvent e) {
                    char c = e.getKeyChar();
                    if (c >= 32 && c != 127 && c != KeyEvent.CHAR_UNDEFINED) remote.sendText(String.valueOf(c));
                }
            });
            setFocusTraversalKeysEnabled(false);
        }

        int[] toFrame(int x, int y) {
            if (scale <= 0) return null;
            int fx = (int) ((x - ox) / scale);
            int fy = (int) ((y - oy) / scale);
            if (fx < 0 || fy < 0 || fx >= frameW || fy >= frameH) return null;
            return new int[]{fx, fy};
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            synchronized (lock) {
                if (frame == null) {
                    g.setColor(Color.GRAY);
                    g.drawString("Várakozás a képre…", 20, 30);
                    return;
                }
                scale = Math.min((double) getWidth() / frameW, (double) getHeight() / frameH);
                int dw = (int) (frameW * scale);
                int dh = (int) (frameH * scale);
                ox = (getWidth() - dw) / 2;
                oy = (getHeight() - dh) / 2;
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.drawImage(frame, ox, oy, dw, dh, null);
            }
        }
    }
}
