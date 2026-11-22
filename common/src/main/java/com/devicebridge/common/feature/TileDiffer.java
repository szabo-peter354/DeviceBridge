package com.devicebridge.common.feature;

import java.util.ArrayList;
import java.util.List;

/**
 * Megkeresi a képkocka megváltozott csempéit az előzőhöz képest, és soronként összevonja
 * a szomszédos változásokat téglalapokká. Így statikus képnél szinte nincs adatforgalom –
 * fontos a lassú Bluetooth kapcsolatnál.
 */
public final class TileDiffer {
    private final int tile;
    private int[] prev;
    private int width;
    private int height;

    public TileDiffer(int tileSize) {
        this.tile = tileSize;
    }

    public void reset() {
        prev = null;
    }

    /**
     * @param px  pixelek (ARGB), sorfolytonosan, sorhossz = w
     * @return változott téglalapok: {x, y, szélesség, magasság}
     */
    public List<int[]> diff(int[] px, int w, int h) {
        List<int[]> out = new ArrayList<>();
        boolean full = prev == null || width != w || height != h;
        int cols = (w + tile - 1) / tile;
        int rows = (h + tile - 1) / tile;
        for (int ty = 0; ty < rows; ty++) {
            int runStart = -1;
            for (int tx = 0; tx <= cols; tx++) {
                boolean changed = tx < cols && (full || tileChanged(px, tx, ty, w, h));
                if (changed && runStart < 0) runStart = tx;
                if (!changed && runStart >= 0) {
                    int x = runStart * tile;
                    int y = ty * tile;
                    int x2 = Math.min(tx * tile, w);
                    int y2 = Math.min(y + tile, h);
                    out.add(new int[]{x, y, x2 - x, y2 - y});
                    runStart = -1;
                }
            }
        }
        int n = w * h;
        if (prev == null || prev.length != n) prev = new int[n];
        System.arraycopy(px, 0, prev, 0, n);
        width = w;
        height = h;
        return out;
    }

    private boolean tileChanged(int[] px, int tx, int ty, int w, int h) {
        int x0 = tx * tile;
        int y0 = ty * tile;
        int x1 = Math.min(x0 + tile, w);
        int y1 = Math.min(y0 + tile, h);
        int[] p = prev;
        for (int y = y0; y < y1; y++) {
            int off = y * w;
            for (int x = x0; x < x1; x++) {
                if (px[off + x] != p[off + x]) return true;
            }
        }
        return false;
    }
}
