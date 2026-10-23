package io.freemytv;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Soft click for the home screen (moving focus and opening). The app synthesizes the sound itself (a short, low
 * "tock"), writes it once as a WAV in the cache and plays it quietly through SoundPool. It only plays on the home
 * screen, never inside apps.
 */
final class Click {
    private static final int RATE = 48_000;
    private SoundPool pool;
    private int move, open, back;

    Click(Context c) {
        try {
            pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
            // Free My Projector: tvOS-like set. Move: a short bright tick; open: a rising pair; back: a falling pair.
            move = pool.load(wav(c, "fmp-move.wav", 2100f, 1700f, 0.006f, 0.025f), 1);
            open = pool.load(wav(c, "fmp-open.wav", 660f, 990f, 0.030f, 0.120f), 1);
            back = pool.load(wav(c, "fmp-back.wav", 880f, 587f, 0.026f, 0.100f), 1);
        } catch (IOException | RuntimeException e) {
            AppLog.w("click sound unavailable: " + e.getMessage());
            release();
        }
    }

    void move() {
        if (pool != null && Prefs.sounds()) pool.play(move, 0.30f, 0.30f, 1, 0, 1f);
    }

    void open() {
        if (pool != null && Prefs.sounds()) pool.play(open, 0.34f, 0.34f, 1, 0, 1f);
    }

    void back() {
        if (pool != null && Prefs.sounds()) pool.play(back, 0.28f, 0.28f, 1, 0, 1f);
    }

    void release() {
        if (pool != null) pool.release();
        pool = null;
    }

    /** Sine wave plus one harmonic gliding from `from` to `to` Hz, 1.5 ms attack (no pop) and exponential decay. */
    private static String wav(Context c, String name, float from, float to, float decay, float seconds) throws IOException {
        File f = new File(c.getCacheDir(), name);
        if (f.exists() && f.length() > 44) return f.getPath();
        int n = Math.round(RATE * seconds);
        ByteBuffer b = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes("US-ASCII")).putInt(36 + n * 2).put("WAVE".getBytes("US-ASCII"));
        b.put("fmt ".getBytes("US-ASCII")).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes("US-ASCII")).putInt(n * 2);
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            // Two notes for the pairs (the first half on `from`, then `to`), a smooth glide for the tick.
            double freq = from == to ? from : seconds > 0.05 ? (t < seconds * 0.4 ? from : to) : from + (to - from) * t / seconds;
            phase += 2 * Math.PI * freq / RATE;
            double local = seconds > 0.05 && t >= seconds * 0.4 ? t - seconds * 0.4 : t;
            double env = Math.min(1, local / 0.0015) * Math.exp(-local / decay);
            double s = 0.75 * Math.sin(phase) + 0.25 * Math.sin(phase * 2.01);
            b.putShort((short) Math.round(s * env * 0.7 * Short.MAX_VALUE));
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(b.array());
        }
        return f.getPath();
    }
}
