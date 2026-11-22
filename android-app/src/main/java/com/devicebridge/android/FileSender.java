package com.devicebridge.android;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.IOException;
import java.io.InputStream;

/** Fájlok küldése tartalom-URI alapján (fájlválasztó vagy Megosztás menü). */
final class FileSender {
    private FileSender() {}

    /**
     * A folyamot azonnal megnyitja (a Megosztás menüből kapott ideiglenes jogosultság
     * az ablak bezárásával elveszhet), majd háttérben elküldi.
     */
    static boolean send(Context ctx, AndroidBridge bridge, Uri uri) {
        ContentResolver cr = ctx.getContentResolver();
        String name = "fajl";
        long size = -1;
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
            }
        } catch (RuntimeException ignored) {
        }
        if (name == null || name.isEmpty()) {
            String seg = uri.getLastPathSegment();
            name = seg == null ? "fajl" : seg;
        }
        InputStream in;
        try {
            in = cr.openInputStream(uri);
            if (in == null) throw new IOException("Nem olvasható");
        } catch (Exception e) {
            bridge.node.log("A fájl nem nyitható meg: " + name);
            return false;
        }
        final InputStream stream = in;
        bridge.files.sendFile(name, size, () -> stream);
        return true;
    }
}
