package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * (upstream) Sees the remote's keys before any app does, so the microphone button works from inside Netflix, Spotify
 * and the rest. The system waits for it on every key, so it runs in a process of its own and does next to nothing:
 * only F6 is looked at (RemoteKeys).
 */
public class RemoteKeyService extends AccessibilityService {
    @Override protected boolean onKeyEvent(KeyEvent event) {
        return RemoteKeys.onKey(this, event);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}
}
