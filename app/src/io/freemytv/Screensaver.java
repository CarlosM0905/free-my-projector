package io.freemytv;

import android.content.Context;

import android.service.dreams.DreamService;

/**
 * The Free My TV screensaver (enabled in Free My TV mode via `settings put secure screensaver_components`). The system
 * still decides when it starts and when the TV turns off (e.g. after 10 min idle, then 2 h later), as before: the app
 * doesn't change those timings. Any key exits.
 */
public class Screensaver extends DreamService {
    private Dream view;

    /** The two styles: the recommendations' photos (DreamView) or framed landscapes (FrameView). */
    interface Dream {
        void start();
        void stop();
    }

    /**
     * Style (home settings): "full" = landscapes edge to edge with the information in a glass box (default), "frame" =
     * the landscape framed with a mat like The Frame's art mode, "recs" = the recommendations' photos.
     */
    static final String[] STYLES = {"full", "frame", "recs"};

    static String style(Context c) {
        return c.getSharedPreferences("home-ui", Context.MODE_PRIVATE).getString("dreamStyle", "full");
    }

    static android.view.View make(Context c) {
        String s = style(c);
        return s.equals("recs") ? new DreamView(c) : new FrameView(c, s.equals("frame"));
    }

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Watchdog.install(this);
        Ui.init(this);
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        android.view.View v = make(this);
        view = (Dream) v;
        setContentView(v);
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        if (view != null) view.start();
    }

    @Override public void onDreamingStopped() {
        if (view != null) view.stop();
        super.onDreamingStopped();
    }

    @Override public void onDetachedFromWindow() {
        if (view != null) view.stop();
        super.onDetachedFromWindow();
    }
}
