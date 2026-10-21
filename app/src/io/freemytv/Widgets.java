package io.freemytv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.Locale;

/**
 * Free My Projector: the data of the Control Center's extra widgets (like the widgets in tvOS's and macOS's Control
 * Center): air quality and UV (Open-Meteo), the next public holiday (Nager.Date), the moon's phase (computed here) and a
 * quick countdown timer. All free, no keys.
 */
final class Widgets {
    private Widgets() {}

    /** Air: US AQI and PM2.5 now, the UV index now and today's highest; NaN when unknown. */
    static volatile double aqi = Double.NaN, pm25 = Double.NaN, uv = Double.NaN, uvMax = Double.NaN;
    /** The next public holiday: its local name and day (epoch ms, 0 = none known). */
    static volatile String holiday = "";
    static volatile long holidayAt;
    private static volatile long airAt, holidayFetchedAt;
    private static volatile boolean fetching;

    /** Fetches what's old (air every 30 min, holidays every 12 h), in the background; `done` when something changed. */
    static void refresh(Context c, Runnable done) {
        long now = System.currentTimeMillis();
        boolean air = now - airAt > 30 * 60_000L, days = now - holidayFetchedAt > 12 * 3600_000L;
        if (fetching || !air && !days) return;
        fetching = true;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                if (air) fetchAir();
                if (days) fetchHoliday(app);
                if (done != null) done.run();
            } catch (Exception e) {
                AppLog.w("widgets: " + e.getMessage());
            } finally {
                fetching = false;
            }
        }, "widgets").start();
    }

    private static void fetchAir() throws Exception {
        JSONObject o = new JSONObject(get("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=" + Prefs.latitude()
                + "&longitude=" + Prefs.longitude() + "&current=us_aqi,pm2_5,uv_index&hourly=uv_index&forecast_days=1&timezone=auto"));
        JSONObject cur = o.getJSONObject("current");
        aqi = cur.optDouble("us_aqi", Double.NaN);
        pm25 = cur.optDouble("pm2_5", Double.NaN);
        uv = cur.optDouble("uv_index", Double.NaN);
        JSONArray hours = o.getJSONObject("hourly").getJSONArray("uv_index");
        double max = Double.NaN;
        for (int i = 0; i < hours.length(); i++) {
            double v = hours.optDouble(i, Double.NaN);
            if (!Double.isNaN(v) && (Double.isNaN(max) || v > max)) max = v;
        }
        uvMax = max;
        airAt = System.currentTimeMillis();
    }

    private static void fetchHoliday(Context c) throws Exception {
        String country = country(c);
        if (country.isEmpty()) return;
        JSONArray days = new JSONArray(get("https://date.nager.at/api/v3/NextPublicHolidays/" + country));
        holiday = "";
        holidayAt = 0;
        for (int i = 0; i < days.length(); i++) {
            JSONObject d = days.getJSONObject(i);
            if (!d.optBoolean("global", true)) continue;   // a regional one: not everyone's day off
            String[] ymd = d.getString("date").split("-");
            Calendar cal = Calendar.getInstance();
            cal.clear();
            cal.set(Integer.parseInt(ymd[0]), Integer.parseInt(ymd[1]) - 1, Integer.parseInt(ymd[2]));
            holidayAt = cal.getTimeInMillis();
            holiday = Ui.en ? d.optString("name") : d.optString("localName", d.optString("name"));
            break;
        }
        holidayFetchedAt = System.currentTimeMillis();
    }

    /** The city's country (ISO code), looked up once from its name; the TV's region often isn't where it is. */
    private static String country(Context c) throws Exception {
        SharedPreferences p = prefs(c);
        String city = Prefs.city();
        if (city.equals(p.getString("countryOf", null))) return p.getString("country", "");
        String code = "";
        if (!city.isEmpty()) {
            JSONObject o = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&format=json&name="
                    + java.net.URLEncoder.encode(city, "UTF-8")));
            JSONArray r = o.optJSONArray("results");
            if (r != null && r.length() > 0) code = r.getJSONObject(0).optString("country_code", "");
        }
        p.edit().putString("countryOf", city).putString("country", code).apply();
        return code;
    }

    /** "Good", "Moderate"… for a US AQI value, and its color (the EPA's scale, a little softened). */
    static String aqiLabel(double v) {
        if (v <= 50) return Ui.tr("Boa", "Good");
        if (v <= 100) return Ui.tr("Moderada", "Moderate");
        if (v <= 150) return Ui.tr("Ruim p/ sensíveis", "Unhealthy for some");
        if (v <= 200) return Ui.tr("Ruim", "Unhealthy");
        return Ui.tr("Muito ruim", "Very unhealthy");
    }

    static int aqiColor(double v) {
        if (v <= 50) return 0xFF30D158;
        if (v <= 100) return 0xFFFFD60A;
        if (v <= 150) return 0xFFFF9F0A;
        if (v <= 200) return 0xFFFF453A;
        return 0xFFBF5AF2;
    }

    static String uvLabel(double v) {
        if (v < 3) return Ui.tr("Baixo", "Low");
        if (v < 6) return Ui.tr("Moderado", "Moderate");
        if (v < 8) return Ui.tr("Alto", "High");
        if (v < 11) return Ui.tr("Muito alto", "Very high");
        return Ui.tr("Extremo", "Extreme");
    }

    // MARK: - Moon

    /** The moon's age as a fraction of its cycle: 0 new, 0.25 first quarter, 0.5 full, 0.75 last quarter. */
    static double moonPhase(long now) {
        double synodic = 29.530588853, days = (now - 947182440000L) / 86_400_000.0;   // from the new moon of 6 Jan 2000, 18:14 UTC
        double age = days % synodic;
        if (age < 0) age += synodic;
        return age / synodic;
    }

    /** Lit fraction of the disc (0–1). */
    static double moonLit(double phase) {
        return (1 - Math.cos(2 * Math.PI * phase)) / 2;
    }

    static String moonName(double phase) {
        int i = (int) Math.floor(phase * 8 + 0.5) % 8;
        switch (i) {
            case 0: return Ui.tr("Lua nova", "New moon");
            case 1: return Ui.tr("Lua crescente", "Waxing crescent");
            case 2: return Ui.tr("Quarto crescente", "First quarter");
            case 3: return Ui.tr("Crescente gibosa", "Waxing gibbous");
            case 4: return Ui.tr("Lua cheia", "Full moon");
            case 5: return Ui.tr("Minguante gibosa", "Waning gibbous");
            case 6: return Ui.tr("Quarto minguante", "Last quarter");
            default: return Ui.tr("Lua minguante", "Waning crescent");
        }
    }

    /** Days until the next full moon. */
    static int daysToFull(double phase) {
        double d = (phase <= 0.5 ? 0.5 - phase : 1.5 - phase) * 29.530588853;
        return (int) Math.round(d);
    }

    // MARK: - Timer

    /** The countdown's steps: each OK adds the next one (5, 10, 15, 30, 60 min), and after the last, it's cancelled. */
    private static final int[] STEPS = {5, 10, 15, 30, 60};

    /** When the countdown ends (epoch ms), 0 when none runs. */
    static long timerEnd(Context c) {
        long end = prefs(c).getLong("timerEnd", 0);
        return end > System.currentTimeMillis() ? end : 0;
    }

    static int timerMinutes(Context c) {
        return prefs(c).getInt("timerMinutes", 0);
    }

    /** OK on the timer widget: the next step, or off after the last. */
    static void cycleTimer(Context c) {
        int current = timerEnd(c) == 0 ? 0 : timerMinutes(c), next = 0;
        for (int s : STEPS) {
            if (s > current) {
                next = s;
                break;
            }
        }
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent done = PendingIntent.getActivity(c, 7, new Intent(c, TimerDone.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT);
        if (next == 0) {
            if (am != null) am.cancel(done);
            prefs(c).edit().putLong("timerEnd", 0).putInt("timerMinutes", 0).apply();
            return;
        }
        long end = System.currentTimeMillis() + next * 60_000L;
        if (am != null) am.setExact(AlarmManager.RTC_WAKEUP, end, done);
        prefs(c).edit().putLong("timerEnd", end).putInt("timerMinutes", next).apply();
    }

    static void timerFinished(Context c) {
        prefs(c).edit().putLong("timerEnd", 0).putInt("timerMinutes", 0).apply();
    }

    /** "4:59", "59:12". */
    static String left(long ms) {
        long s = Math.max(0, (ms + 999) / 1000);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("widgets", Context.MODE_PRIVATE);
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "FreeMyProjector/1.0");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
