package com.devicebridge.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.concurrent.atomic.AtomicInteger;

/** Értesítési csatornák és segédfüggvények. */
final class Notifications {
    private Notifications() {}

    static final String CH_SERVICE = "service";
    static final String CH_EVENTS = "events";

    static final int ID_SERVICE = 1;
    static final int ID_PAIRING = 2;
    static final int ID_SCREEN_REQUEST = 3;
    static final int ID_RING = 4;
    static final int ID_ACCESSIBILITY = 5;

    private static final AtomicInteger NEXT_ID = new AtomicInteger(100);

    static void createChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel service = new NotificationChannel(CH_SERVICE, "Háttérszolgáltatás", NotificationManager.IMPORTANCE_LOW);
        service.setDescription("Jelzi, hogy a DeviceBridge fut és kapcsolatra vár");
        service.setShowBadge(false);
        NotificationChannel events = new NotificationChannel(CH_EVENTS, "Események", NotificationManager.IMPORTANCE_HIGH);
        events.setDescription("Párosítás, fogadott fájlok, üzenetek, képernyőmegosztási kérések");
        nm.createNotificationChannel(service);
        nm.createNotificationChannel(events);
    }

    static PendingIntent activity(Context c, int requestCode, Intent i) {
        return PendingIntent.getActivity(c, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent service(Context c, int requestCode, Intent i) {
        return PendingIntent.getService(c, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static Notification.Builder event(Context c, String title, String text) {
        return new Notification.Builder(c, CH_EVENTS)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true);
    }

    static void show(Context c, int id, String title, String text, PendingIntent pi) {
        Notification.Builder b = event(c, title, text);
        if (pi == null) pi = activity(c, 0, new Intent(c, MainActivity.class));
        b.setContentIntent(pi);
        try {
            c.getSystemService(NotificationManager.class).notify(id, b.build());
        } catch (SecurityException ignored) {
            // nincs POST_NOTIFICATIONS engedély
        }
    }

    static void show(Context c, String title, String text, PendingIntent pi) {
        show(c, NEXT_ID.incrementAndGet(), title, text, pi);
    }

    static void cancel(Context c, int id) {
        c.getSystemService(NotificationManager.class).cancel(id);
    }
}
