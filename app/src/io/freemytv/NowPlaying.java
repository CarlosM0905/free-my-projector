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
        long duration = -1;          // ms, or -1
        long position = -1;          // ms at `updatedAt` (SystemClock.elapsedRealtime), or -1
        long updatedAt;
        float speed = 1;

        /** Where the song is now (it moves on between the app's reports), or -1. */
        long positionNow() {
            if (position < 0) return -1;
            return position + (long) ((android.os.SystemClock.elapsedRealtime() - updatedAt) * speed);
        }
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
            t.duration = meta.containsKey(MediaMetadata.METADATA_KEY_DURATION) ? meta.getLong(MediaMetadata.METADATA_KEY_DURATION) : -1;
            t.position = state.getPosition() == PlaybackState.PLAYBACK_POSITION_UNKNOWN ? -1 : state.getPosition();
            t.updatedAt = state.getLastPositionUpdateTime();
            t.speed = state.getPlaybackSpeed() > 0 ? state.getPlaybackSpeed() : 1;
            if (t.title != null && !t.title.trim().isEmpty()) return t;
        }
        return null;
    }

    /**
     * Something is playing (music or video, with or without a title). Some apps (SmartTube) never report "playing" in
     * their media session, so sound going out counts too, even at volume 0.
     */
    static boolean playing(Context c) {
        android.media.AudioManager am = (android.media.AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am != null && am.isMusicActive()) return true;
        for (MediaController m : controllers(c)) {
            PlaybackState state = m.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) return true;
        }
        return false;
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
