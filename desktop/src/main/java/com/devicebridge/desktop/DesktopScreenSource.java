package com.devicebridge.desktop;

import com.devicebridge.common.feature.RemoteDesktopService;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** A PC elsődleges képernyőjének rögzítése java.awt.Robot segítségével. */
final class DesktopScreenSource implements RemoteDesktopService.ScreenSource {
    private Robot robot;
    private Rectangle bounds;
    private int maxSize;
    private BufferedImage scaled;
    private ImageWriter writer;

    static Rectangle primaryBounds() {
        return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                .getDefaultConfiguration().getBounds();
    }

    @Override
    public void start(int maxSize) throws Exception {
        this.robot = new Robot();
        this.bounds = primaryBounds();
        this.maxSize = maxSize;
        Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg");
        if (!it.hasNext()) throw new IOException("Nincs JPEG kódoló");
        writer = it.next();
    }

    @Override
    public RemoteDesktopService.Frame capture() {
        bounds = primaryBounds();
        BufferedImage img = robot.createScreenCapture(bounds);
        double scale = Math.min(1.0, (double) maxSize / Math.max(bounds.width, bounds.height));
        int fw = Math.max(1, (int) Math.round(bounds.width * scale));
        int fh = Math.max(1, (int) Math.round(bounds.height * scale));
        if (scaled == null || scaled.getWidth() != fw || scaled.getHeight() != fh) {
            scaled = new BufferedImage(fw, fh, BufferedImage.TYPE_INT_RGB);
        }
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, fw, fh, null);
        // Egérmutató berajzolása (a Robot nem rögzíti)
        PointerInfo pi = MouseInfo.getPointerInfo();
        if (pi != null) {
            Point pt = pi.getLocation();
            if (bounds.contains(pt)) {
                int cx = (int) ((pt.x - bounds.x) * scale);
                int cy = (int) ((pt.y - bounds.y) * scale);
                int[] xs = {cx, cx, cx + 4, cx + 7, cx + 9, cx + 6, cx + 11};
                int[] ys = {cy, cy + 16, cy + 12, cy + 18, cy + 17, cy + 11, cy + 11};
                g.setColor(Color.WHITE);
                g.fillPolygon(xs, ys, xs.length);
                g.setColor(Color.BLACK);
                g.drawPolygon(xs, ys, xs.length);
            }
        }
        g.dispose();
        int[] data = ((DataBufferInt) scaled.getRaster().getDataBuffer()).getData();
        return new RemoteDesktopService.Frame(fw, fh, data);
    }

    @Override
    public void stop() {
        if (writer != null) writer.dispose();
        writer = null;
    }

    @Override
    public int screenWidth() {
        return bounds == null ? 1 : bounds.width;
    }

    @Override
    public int screenHeight() {
        return bounds == null ? 1 : bounds.height;
    }

    @Override
    public byte[] encodeJpeg(int[] pixels, int stride, int x, int y, int w, int h, int quality) throws IOException {
        BufferedImage tile = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        tile.setRGB(0, 0, w, h, pixels, y * stride + x, stride);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bos)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality / 100f);
            writer.write(null, new IIOImage(tile, null, null), param);
            writer.reset();
        }
        return bos.toByteArray();
    }
}
