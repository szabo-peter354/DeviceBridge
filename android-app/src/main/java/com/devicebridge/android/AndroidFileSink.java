package com.devicebridge.android;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import com.devicebridge.common.feature.FileTransferService;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

/** A fogadott fájlokat a Letöltések/DeviceBridge mappába menti (MediaStore, engedély nélkül). */
final class AndroidFileSink implements FileTransferService.FileSink {
    private static final String FOLDER = Environment.DIRECTORY_DOWNLOADS + "/DeviceBridge";
    private final Context ctx;

    AndroidFileSink(Context ctx) {
        this.ctx = ctx;
    }

    private static String mime(String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (m != null) return m;
        }
        return "application/octet-stream";
    }

    @Override
    public FileTransferService.IncomingFile begin(String name, long size) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime(name));
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("A fájl nem hozható létre a Letöltések mappában");
        OutputStream raw = cr.openOutputStream(uri, "w");
        if (raw == null) {
            cr.delete(uri, null, null);
            throw new IOException("A fájl nem írható");
        }
        OutputStream out = new BufferedOutputStream(raw, 256 * 1024);
        return new FileTransferService.IncomingFile() {
            @Override
            public OutputStream stream() {
                return out;
            }

            @Override
            public String complete() throws IOException {
                out.close();
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, done, null, null);
                return "Letöltések/DeviceBridge/" + name;
            }

            @Override
            public void abort() {
                try { out.close(); } catch (IOException ignored) { }
                try { cr.delete(uri, null, null); } catch (RuntimeException ignored) { }
            }
        };
    }
}
