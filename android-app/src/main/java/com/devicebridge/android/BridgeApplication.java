package com.devicebridge.android;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

public class BridgeApplication extends Application {
    private static int startedActivities;

    /** Igaz, ha az alkalmazás valamelyik ablaka éppen látható. */
    static synchronized boolean isInForeground() {
        return startedActivities > 0;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity a) { synchronized (BridgeApplication.class) { startedActivities++; } }
            @Override public void onActivityStopped(Activity a) { synchronized (BridgeApplication.class) { startedActivities--; } }
            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityResumed(Activity a) { }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
    }
}
