package io.freemytv;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;

import java.util.ArrayList;
import java.util.List;

/**
 * Free My Projector: Now Playing, like tvOS. What music or video app is playing (Spotify, AuraMusic, SmartTube…),
 * shown on the home screen's tab row. Android only shares the media sessions with an enabled notification listener;
 * this app turns its own on once through the TV's ADB (it reads no notifications).
 */
final class NowPlaying {
    private NowPlaying() {}

    /** Only here so Android shares the media sessions with the home screen. */
    public static final class Listener extends NotificationListenerService {
        @Override public void onListenerConnected() {
            Heat.start(this);   // the system keeps this process alive, so the heat notice works over any app
        }
    }

    static final class Track {
        String title, artist;
    }

    private static volatile boolean enabling;

    /** The playing track, or null (nothing playing, or the listener isn't enabled yet). */
    static Track current(Context c) {
        for (MediaController m : controllers(c)) {
            PlaybackState state = m.getPlaybackState();
            MediaMetadata meta = m.getMetadata();
            if (state == null || meta == null || state.getState() != PlaybackState.STATE_PLAYING) continue;
            Track t = new Track();
            t.title = meta.getString(MediaMetadata.METADATA_KEY_TITLE);
            t.artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (t.artist == null) t.artist = meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            if (t.title != null && !t.title.trim().isEmpty()) return t;
        }
        return null;
    }

    /** Calls `changed` when a track starts, stops or changes (until `stop`). */
    static Runnable watch(Context c, Handler h, Runnable changed) {
        MediaSessionManager msm = c.getSystemService(MediaSessionManager.class);
        List<MediaController> watched = new ArrayList<>();
        MediaController.Callback cb = new MediaController.Callback() {
            @Override public void onPlaybackStateChanged(PlaybackState state) { changed.run(); }
            @Override public void onMetadataChanged(MediaMetadata metadata) { changed.run(); }
        };
        MediaSessionManager.OnActiveSessionsChangedListener sessions = list -> {
            for (MediaController m : watched) m.unregisterCallback(cb);
            watched.clear();
            if (list != null) for (MediaController m : list) {
                m.registerCallback(cb, h);
                watched.add(m);
            }
            changed.run();
        };
        try {
            msm.addOnActiveSessionsChangedListener(sessions, component(c), h);
            sessions.onActiveSessionsChanged(msm.getActiveSessions(component(c)));
        } catch (SecurityException e) {
            enable(c);
            return () -> {};
        }
        return () -> {
            msm.removeOnActiveSessionsChangedListener(sessions);
            for (MediaController m : watched) m.unregisterCallback(cb);
            watched.clear();
        };
    }

    private static List<MediaController> controllers(Context c) {
        try {
            return c.getSystemService(MediaSessionManager.class).getActiveSessions(component(c));
        } catch (SecurityException e) {
            return new ArrayList<>();
        }
    }

    private static ComponentName component(Context c) {
        return new ComponentName(c, Listener.class);
    }

    /** Turns the listener on through the TV's own ADB (once; it takes effect the next time the home screen shows). */
    private static void enable(Context c) {
        String on = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        if (enabling || (on != null && on.contains(component(c).flattenToString()))) return;
        enabling = true;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try (Adb adb = Adb.connect(app, 5000, null)) {
                adb.shell("cmd notification allow_listener '" + component(app).flattenToString() + "'");
            } catch (Exception e) {
                AppLog.w("now playing: " + e.getMessage());
            } finally {
                enabling = false;
            }
        }, "now-playing").start();
    }
}
