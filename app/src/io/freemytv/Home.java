package io.freemytv;

import android.view.KeyEvent;

/** The home screen's view, either design: classic (HomeView, tvOS glass) or light (ListHome, type only). */
interface Home {
    void resume();
    void pause();
    void destroy();
    void reset(boolean animated);
    void trimMemory();
    boolean onKey(KeyEvent e);
}
