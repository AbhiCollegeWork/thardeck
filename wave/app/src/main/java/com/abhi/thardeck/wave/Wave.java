package com.abhi.thardeck.wave;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.util.Log;

import com.abhi.thardeck.wave.engine.Tuning;

/**
 * Shared helpers, preferences and live state for the status screen.
 *
 * The service and the screen run in one process, so live values are plain
 * volatile statics: the service writes them, the screen polls them.
 */
public final class Wave {
    public static final String TAG = "THARWAVE";

    /** To logcat, and to the durable drive log once it is initialised. */
    public static void log(String s) {
        Log.i(TAG, s);
        EventLog.append(s);
    }

    // ---- live state ------------------------------------------------------------

    public static volatile GestureService service;
    public static volatile String serviceState = "stopped";
    public static volatile float cameraFps, analysedFps, inferMs;
    public static volatile int handPct;
    public static volatile boolean handPresent;
    public static volatile String lastCommand = "none";
    public static volatile String relayTarget = "not found";
    public static volatile String delegate = "none";
    public static volatile String transform = "not chosen yet";
    public static volatile String cameraInfo = "closed";
    public static volatile String engineLine = "";
    /** Presence gate: whether the landmarker is running, and the last motion
     *  reading (mean absolute luma difference, 0..255). */
    public static volatile boolean gateOn;
    public static volatile float motion;
    /** Low light: night mode, the mean luma it is judged on, and the sensor
     *  exposure time the camera last reported. */
    public static volatile boolean night;
    public static volatile int luma;
    public static volatile float exposureMs;

    /** Set while the calibrate view is on screen. */
    public static volatile boolean calibrating;
    /** Latest analysed frame, already in the driver frame, and its landmarks
     *  as x0,y0,x1,y1..., or null when no hand. */
    public static volatile Bitmap calibFrame;
    public static volatile float[] calibLandmarks;
    /** Hand centre trail for the last half second as x0,y0,x1,y1..., and the
     *  last stroke fired as {x, y, +1 up or -1 down} at uptime strokeMarkAt. */
    public static volatile float[] calibTrail;
    public static volatile float[] strokeMark;
    public static volatile long strokeMarkAt;
    /** One-shot request to save the next analysed frame, for checking the
     *  orientation transform by eye. */
    public static volatile boolean snapRequested;

    // ---- preferences -------------------------------------------------------------

    private static final String PREFS = "wave";
    public static final String DEFAULT_TOKEN = "thardeck";

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String token(Context c) {
        String t = p(c).getString("token", DEFAULT_TOKEN);
        return t == null || t.trim().isEmpty() ? DEFAULT_TOKEN : t.trim();
    }
    public static void setToken(Context c, String t) {
        p(c).edit().putString("token", t == null ? DEFAULT_TOKEN : t.trim()).apply();
    }

    /** Optional fixed address or host name of the phone; empty when unset. */
    public static String manualAddress(Context c) {
        String a = p(c).getString("manualAddress", "");
        return a == null ? "" : a.trim();
    }
    public static void setManualAddress(Context c, String a) {
        p(c).edit().putString("manualAddress", a == null ? "" : a.trim()).apply();
    }

    /** Landmarker delegate preference: "gpu" (try GPU, fall back to CPU, the
     *  default) or "cpu" (CPU only). */
    public static String delegatePref(Context c) {
        String d = p(c).getString("delegate", "gpu");
        return "cpu".equalsIgnoreCase(d) ? "cpu" : "gpu";
    }
    public static void setDelegatePref(Context c, String d) {
        p(c).edit().putString("delegate", "cpu".equalsIgnoreCase(d) ? "cpu" : "gpu").apply();
    }

    public static boolean autostart(Context c) { return p(c).getBoolean("autostart", false); }
    public static void setAutostart(Context c, boolean on) {
        p(c).edit().putBoolean("autostart", on).apply();
    }

    // ---- tuning ------------------------------------------------------------------

    /** The one tuning instance, shared by the engine and the sliders. */
    public static final Tuning TUNING = new Tuning();
    private static boolean tuningLoaded;

    public static synchronized Tuning tuning(Context c) {
        if (!tuningLoaded) {
            SharedPreferences sp = p(c);
            // Only keys someone saved by hand override the defaults, so a new
            // default in a new build reaches every untouched slider.
            for (Tuning.Param prm : Tuning.PARAMS) {
                String k = "t_" + prm.key;
                if (sp.contains(k)) {
                    TUNING.set(prm.key, Double.longBitsToDouble(sp.getLong(k, 0)));
                }
            }
            // Values saved for recognisers this build no longer has are stale.
            SharedPreferences.Editor e = sp.edit();
            int stale = 0;
            for (String k : sp.getAll().keySet()) {
                if (k.startsWith("t_") && !Tuning.isKey(k.substring(2))) { e.remove(k); stale++; }
            }
            if (stale > 0) { e.apply(); log("tuning: removed " + stale + " saved values for retired keys"); }
            if (sp.contains("strokeInvert")) TUNING.strokeInvert = sp.getBoolean("strokeInvert", false);
            tuningLoaded = true;
        }
        return TUNING;
    }

    public static void saveTuning(Context c, String key, double v) {
        TUNING.set(key, v);
        p(c).edit().putLong("t_" + key, Double.doubleToLongBits(v)).apply();
    }

    /** Which way of stroking is volume up; saved like a slider value. */
    public static void saveStrokeInvert(Context c, boolean on) {
        TUNING.strokeInvert = on;
        p(c).edit().putBoolean("strokeInvert", on).apply();
    }

    public static void resetTuning(Context c) {
        TUNING.reset();
        SharedPreferences.Editor e = p(c).edit();
        for (Tuning.Param prm : Tuning.PARAMS) e.remove("t_" + prm.key);
        e.remove("strokeInvert");
        e.apply();
    }

    public static String now() {
        return android.text.format.DateFormat.format("HH:mm:ss", new java.util.Date()).toString();
    }

    private Wave() {}
}
