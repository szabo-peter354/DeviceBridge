package com.devicebridge.desktop.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/** Programból rajzolt alkalmazásikon (nincs szükség képfájlra). */
public final class Icons {
    private Icons() {}

    public static BufferedImage app(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(0x3B82F6), size, size, new Color(0x1D4ED8)));
        int arc = Math.max(4, size / 4);
        g.fillRoundRect(0, 0, size, size, arc, arc);
        g.setColor(Color.WHITE);
        float s = size / 32f;
        g.setStroke(new BasicStroke(Math.max(1.5f, 3f * s), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        // két eszköz és a híd
        g.drawRoundRect(Math.round(4 * s), Math.round(9 * s), Math.round(9 * s), Math.round(14 * s), Math.round(3 * s), Math.round(3 * s));
        g.drawRoundRect(Math.round(19 * s), Math.round(9 * s), Math.round(9 * s), Math.round(14 * s), Math.round(3 * s), Math.round(3 * s));
        g.drawLine(Math.round(13 * s), Math.round(16 * s), Math.round(19 * s), Math.round(16 * s));
        g.dispose();
        return img;
    }

    public static List<Image> appIcons() {
        List<Image> list = new ArrayList<>();
        for (int s : new int[]{16, 24, 32, 48, 64, 128}) list.add(app(s));
        return list;
    }

    public static BufferedImage dot(Color c) {
        BufferedImage img = new BufferedImage(12, 12, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(c);
        g.fillOval(1, 1, 10, 10);
        g.dispose();
        return img;
    }
}
