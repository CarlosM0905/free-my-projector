package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Settings changed on the projector itself (Home Settings > Preferences), like upstream's Prefs: config.properties
 * only gives the first values, and anything picked here wins. Read in every process (App sets the context first).
 */
final class Prefs {
    private Prefs() {}

    static final String[] LANGUAGES = {"auto", "es", "en", "pt"};
    static final String[] TEMPERATURES = {"auto", "C", "F"};
    static final String[] THEMES = {"auto", "dark", "light"};

    private static SharedPreferences sp() {
        return App.context == null ? null : App.context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    private static String get(String key, String fallback) {
        SharedPreferences p = sp();
        return p == null ? fallback : p.getString(key, fallback);
    }

    private static void put(String key, String value) {
        SharedPreferences p = sp();
        if (p != null) p.edit().putString(key, value).apply();
    }

    /** "auto" (the projector's language), "es", "en" or "pt". */
    static String language() {
        String v = get("language", Config.LANGUAGE).trim().toLowerCase(Locale.ROOT);
        return v.startsWith("es") ? "es" : v.startsWith("en") ? "en" : v.startsWith("pt") ? "pt" : "auto";
    }

    static void setLanguage(String v) { put("language", v); }

    /** "auto", "C" or "F". */
    static String temperature() {
        String v = get("temperature", Config.TEMPERATURE).trim().toUpperCase(Locale.ROOT);
        return v.startsWith("F") ? "F" : v.startsWith("C") ? "C" : "auto";
    }

    static void setTemperature(String v) { put("temperature", v); }

    /** "auto" (dark from sunset to sunrise), "dark" or "light". */
    static String theme() {
        String v = get("theme", Config.THEME).trim().toLowerCase(Locale.ROOT);
        return v.startsWith("dark") || v.startsWith("oscuro") ? "dark" : v.startsWith("light") || v.startsWith("claro") ? "light" : "auto";
    }

    static void setTheme(String v) { put("theme", v); }

    static String city() { return get("city", Config.CITY).trim(); }
    static String latitude() { return get("latitude", Config.LATITUDE); }
    static String longitude() { return get("longitude", Config.LONGITUDE); }

    static void setCity(String name, String latitude, String longitude) {
        SharedPreferences p = sp();
        if (p != null) p.edit().putString("city", name).putString("latitude", latitude).putString("longitude", longitude).apply();
    }

    /** The crypto prices on the home screen. */
    static boolean quotes() {
        SharedPreferences p = sp();
        return p == null || p.getBoolean("quotes", true);
    }

    static void setQuotes(boolean on) {
        SharedPreferences p = sp();
        if (p != null) p.edit().putBoolean("quotes", on).apply();
    }

    /** The TMDB key (from Ichiro786's and Arthur997's forks): typed on the projector, or config.properties'. */
    static String tmdbKey() { return get("tmdbKey", Config.TMDB_KEY).trim(); }
    static void setTmdbKey(String v) { put("tmdbKey", v.trim()); }

    /** The football team for the widget ("" = none). */
    static String footballTeam() { return get("footballTeam", Config.FOOTBALL_TEAM).trim(); }
    static void setFootballTeam(String v) { put("footballTeam", v.trim()); }
}
