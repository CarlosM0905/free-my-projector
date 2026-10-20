package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.TextView;

/**
 * The Free My TV home screen (Android's "Home" role in Free My TV mode). It runs in the :home process: if it crashes,
 * the safety net in the main process keeps working. On the 3rd crash within 5 min, the Watchdog gives the home screen
 * back to the TV's original one.
 */
public class HomeActivity extends Activity {
    private Home home;
    private boolean list;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        if (Watchdog.crashedTooOften(this)) {
            // Don't try to draw again: only show a notice while the main process restores the original home screen.
            HomeMode.requestRevertHome(this);
            TextView t = new TextView(this);
            t.setText(Ui.tr("O Início do Free My Projector teve um problema.\nVoltando para o Início original da TV…",
                    "Free My Projector's home screen ran into a problem.\nGoing back to the TV's original home screen…"));
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            t.setGravity(Gravity.CENTER);
            t.setBackgroundColor(Color.rgb(7, 11, 30));
            setContentView(t);
            return;
        }
        Ui.init(this);
        Heat.start(this);
        // Free My Projector: the light design (a list of names, no glass) or the classic one, from Home Settings.
        list = ListHome.chosen(this);
        home = list ? new ListHome(this) : new HomeView(this);
        setContentView((android.view.View) home);
        getWindow().setBackgroundDrawable(null);
    }

    @Override protected void onResume() {
        super.onResume();
        if (home != null && list != ListHome.chosen(this)) {   // the design was changed in the settings
            recreate();
            return;
        }
        if (home != null) home.resume();
    }

    @Override protected void onPause() {
        if (home != null) home.pause();
        super.onPause();
    }

    /**
     * Free My Projector: once another app is in front, the home screen lets go of the photos it isn't showing (tens of
     * MB of GPU memory on a 2 GB projector); they are decoded again in the background when Home comes back.
     */
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (home != null && level >= TRIM_MEMORY_UI_HIDDEN) home.trimMemory();
    }

    /** Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (home != null) home.reset(true);
    }

    @Override protected void onDestroy() {
        if (home != null) home.destroy();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (home != null && home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

}
