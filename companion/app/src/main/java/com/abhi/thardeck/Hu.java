package com.abhi.thardeck;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Shared helpers and cross-component state for the head unit server switch.
 *
 * The whole app exists to press one menu item at the right moment. Android
 * Auto exposes its head unit server only through a developer-menu toggle, and
 * that toggle lives inside Android Auto's own process, so nothing outside it
 * can call the code directly. Every intent, service and broadcast route was
 * tried and none opens the port. The one lever that exists is the tap, so this
 * app presses it, keyed to the car's Bluetooth and verified against the port.
 */
public final class Hu {
    public static final String TAG = "THARDECK";

    /** Android Auto's head unit server listens here once the toggle is on. */
    /** Ground truth for whether the server is up, as last read from Android
     *  Auto's own menu by the tapper: "running", "stopped", or "unknown".
     *  A loopback port probe is unreliable on this phone (cross-UID loopback to
     *  5277 is dropped even from a shell), so the menu label is the signal. */
    public static volatile String lastKnownState = "unknown";

    /** The developer-menu entry, reached through the overflow. */
    public static final String MENU_START = "Start head unit server";
    public static final String MENU_STOP = "Stop head unit server";
    public static final String OVERFLOW_DESC = "More options";

    public static final String AA_PKG = "com.google.android.projection.gearhead";
    public static final String AA_SETTINGS =
            AA_PKG + "/.companion.settings.DefaultSettingsActivity";

    /**
     * What the accessibility tapper should do when it next sees the menu.
     * NONE means stand down; the service must never touch the UI unarmed.
     */
    public enum Intent2 { NONE, START, STOP }
    public static volatile Intent2 armed = Intent2.NONE;
    /** The tapper may act only until this time, so a stale arm cannot fire. */
    public static volatile long armWindowUntil = 0;
    /** True while the accessibility service is bound and able to tap. */
    public static volatile boolean tapperReady = false;
    /** Human-readable outcome of the last action, for the status screen. */
    public static volatile String lastResult = "nothing yet";

    public static void arm(Intent2 what, long forMs) {
        armed = what;
        armWindowUntil = System.currentTimeMillis() + forMs;
    }
    public static void disarm() {
        armed = Intent2.NONE;
        armWindowUntil = 0;
    }
    public static boolean armActive() {
        return armed != Intent2.NONE && System.currentTimeMillis() <= armWindowUntil;
    }

    public static void log(String s) { Log.i(TAG, s); }

    // ---- preferences ---------------------------------------------------------

    private static final String PREFS = "thardeck";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_ADDR = "car_addr";
    private static final String KEY_NAME = "car_name";

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Master switch. Off means the app watches nothing and touches nothing, so
     * the phone behaves exactly as a stock phone. Useful when lending the car,
     * or debugging, without uninstalling.
     */
    public static boolean isEnabled(Context c) {
        try { return p(c).getBoolean(KEY_ENABLED, true); }
        catch (Throwable t) { return true; }
    }
    public static void setEnabled(Context c, boolean on) {
        try { p(c).edit().putBoolean(KEY_ENABLED, on).apply(); }
        catch (Throwable ignored) {}
    }

    /** The car's Bluetooth address, chosen by the user from bonded devices.
     *  Never hardcoded: a MAC is personal, and it differs on every build. */
    public static String carAddr(Context c) {
        try { return p(c).getString(KEY_ADDR, null); }
        catch (Throwable t) { return null; }
    }
    public static String carName(Context c) {
        try { return p(c).getString(KEY_NAME, "not set"); }
        catch (Throwable t) { return "not set"; }
    }
    public static void setCar(Context c, String addr, String name) {
        try { p(c).edit().putString(KEY_ADDR, addr).putString(KEY_NAME, name).apply(); }
        catch (Throwable ignored) {}
    }

    public static boolean isCar(Context c, BluetoothDevice d) {
        if (d == null || d.getAddress() == null) return false;
        String want = carAddr(c);
        return want != null && want.equalsIgnoreCase(d.getAddress());
    }

    private Hu() {}
}
