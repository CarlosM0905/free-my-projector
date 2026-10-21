package io.freemytv;

import android.app.Application;
import android.content.Context;

/** Free My Projector: keeps the app's context for Prefs, set before any screen or service starts (every process). */
public final class App extends Application {
    static volatile Context context;

    @Override public void onCreate() {
        context = getApplicationContext();
        super.onCreate();
    }
}
