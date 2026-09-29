package com.abhi.thardeck;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * The watcher.
 *
 * Android Auto's head unit server is the only wireless path that still works
 * since AA 17.3 removed the helper connection intent. It is a developer toggle
 * that has to be pressed by hand, and it dies with Android Auto's background
 * process, so it comes back at unpredictable times, not just after a reboot.
 * This service presses it for you, and only in the car.
 *
 * Keyed to the car's Bluetooth, which is the one signal that actually means
 * "driving". The hotspot is not: the tablet is tethered to the same phone for
 * desk work too, so gating on the network would start projection at the desk.
 * Bluetooth to the car audio is present when driving and absent otherwise.
 *
 *   car Bluetooth connects   -> if the server is down, start it
 *   car Bluetooth disconnects -> stop it, so a later desk tether cannot connect
 *
 * The tap needs the screen on and unlocked, because the settings UI cannot be
 * driven behind a secure keyguard. When unlocked it acts silently; when locked
 * it posts a one-tap notification and, for a stop, defers until the next
 * unlock. This is the notification mode chosen for this build; there is no
 * attempt to bypass the lock.
 */
public class ServerService extends Service {

    public static final String ACTION_START_NOW = "com.abhi.thardeck.START_NOW";
    public static final String ACTION_STOP_NOW = "com.abhi.thardeck.STOP_NOW";
    public static final String ACTION_REFRESH = "com.abhi.thardeck.REFRESH";

    /** Cranking the engine flaps the audio link, so wait before believing a
     *  connect. Short enough that the server is up before you have the tablet
     *  awake. */
    static final long CONNECT_DEBOUNCE_MS = 5_000;
    /** Parking with the engine briefly off, or a momentary dropout, should not
     *  tear the server down. Confirm the car is really gone first. */
    static final long DISCONNECT_DEBOUNCE_MS = 30_000;
    /** How long the tapper may act after we arm it. */
    static final long ARM_WINDOW_MS = 40_000;
    /** If the tapper has not opened the overflow this long after arming,
     *  Android Auto's settings did not come to the front; launch them again. */
    static final long RELAUNCH_CHECK_MS = 3_000;

    final Handler h = new Handler(Looper.getMainLooper());
    Runnable pendingConnect, pendingDisconnect, relaunchCheck;
    boolean deferredStop = false;

    /** Carries Wave's gestures from the tablet to this phone's music. */
    MediaRelay relay;
    /** The relay thread asks for a notification refresh through this. */
    final Runnable relayChanged = new Runnable() {
        @Override public void run() {
            h.post(new Runnable() { @Override public void run() { refresh(); } });
        }
    };

    // ---- Bluetooth ----------------------------------------------------------

    final BroadcastReceiver bt = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (!Hu.isCar(ServerService.this, d)) return;
            String a = i.getAction();
            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(a)) {
                Hu.log("car connected");
                onCarConnected();
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(a)) {
                Hu.log("car disconnected");
                onCarDisconnected();
            }
        }
    };

    /** A deferred stop runs on the next unlock, if the car is still gone. */
    final BroadcastReceiver wake = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (deferredStop && unlocked()) {
                deferredStop = false;
                Hu.log("unlocked, running deferred stop");
                doStop(false);
            }
        }
    };

    void onCarConnected() {
        cancel(pendingDisconnect); pendingDisconnect = null;
        deferredStop = false;
        if (!Hu.isEnabled(this)) { Hu.log("disabled, ignoring connect"); return; }
        cancel(pendingConnect);
        pendingConnect = new Runnable() { @Override public void run() {
            pendingConnect = null;
            doStart();
        }};
        h.postDelayed(pendingConnect, CONNECT_DEBOUNCE_MS);
    }

    void onCarDisconnected() {
        cancel(pendingConnect); pendingConnect = null;
        if (!Hu.isEnabled(this)) return;
        cancel(pendingDisconnect);
        pendingDisconnect = new Runnable() { @Override public void run() {
            pendingDisconnect = null;
            doStop(true);
        }};
        h.postDelayed(pendingDisconnect, DISCONNECT_DEBOUNCE_MS);
    }

    // ---- start --------------------------------------------------------------

    void doStart() {
        if (!tapperEnabled()) { status("accessibility off, cannot start"); return; }
        if (unlocked()) {
            Hu.log("unlocked, ensuring server started");
            Hu.arm(Hu.Intent2.START, ARM_WINDOW_MS);
            launchSettings();
            scheduleRelaunchCheck();
        } else {
            Hu.log("locked, posting one-tap start");
            postAction("Tap to start Android Auto", "In the car. One tap starts the head unit server.",
                    ACTION_START_NOW);
        }
    }

    // ---- stop ---------------------------------------------------------------

    /** @param defer if locked, remember to stop on the next unlock. */
    void doStop(final boolean defer) {
        if (!tapperEnabled()) { status("accessibility off, cannot stop"); return; }
        if (unlocked()) {
            Hu.log("unlocked, ensuring server stopped");
            Hu.arm(Hu.Intent2.STOP, ARM_WINDOW_MS);
            launchSettings();
            scheduleRelaunchCheck();
        } else if (defer) {
            deferredStop = true;
            Hu.log("locked, deferring stop to next unlock");
        }
    }

    // ---- shared -------------------------------------------------------------

    void launchSettings() {
        try {
            Intent i = new Intent();
            i.setClassName(Hu.AA_PKG, Hu.AA_PKG + ".companion.settings.DefaultSettingsActivity");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
        } catch (Throwable t) {
            Hu.log("launch settings failed: " + t);
        }
    }

    /** One-shot: right after an unlock, the settings launch can lose the race
     *  to the launcher, so the tapper never sees Android Auto. */
    void scheduleRelaunchCheck() {
        cancel(relaunchCheck);
        relaunchCheck = new Runnable() { @Override public void run() {
            relaunchCheck = null;
            if (Hu.armActive() && Hu.overflowClicks == 0) {
                Hu.log("settings not in front, relaunching");
                launchSettings();
            }
        }};
        h.postDelayed(relaunchCheck, RELAUNCH_CHECK_MS);
    }
    // The tapper reads Android Auto's own menu to confirm the state changed and
    // then returns to the home screen itself, so there is no port check here.

    boolean unlocked() {
        KeyguardManager km = getSystemService(KeyguardManager.class);
        PowerManager pm = getSystemService(PowerManager.class);
        boolean locked = km != null && km.isKeyguardLocked();
        boolean interactive = pm != null && pm.isInteractive();
        return interactive && !locked;
    }

    boolean tapperEnabled() {
        if (Hu.tapperReady) return true;
        try {
            String s = android.provider.Settings.Secure.getString(getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains(getPackageName() + "/");
        } catch (Throwable t) { return false; }
    }

    void cancel(Runnable r) { if (r != null) h.removeCallbacks(r); }

    void status(String s) {
        Hu.lastResult = s + " at " + now();
        Hu.log(s);
        refresh();
    }
    static String now() {
        return android.text.format.DateFormat.format("HH:mm:ss",
                new java.util.Date()).toString();
    }

    // ---- lifecycle + notification ------------------------------------------

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                "thardeck", "Head unit server", NotificationManager.IMPORTANCE_MIN));
        nm.createNotificationChannel(new NotificationChannel(
                "thardeck_act", "Action needed", NotificationManager.IMPORTANCE_HIGH));
        startForeground(1, buildOngoing());
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        registerReceiver(bt, f);
        IntentFilter wf = new IntentFilter(Intent.ACTION_USER_PRESENT);
        wf.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(wake, wf);
        relay = new MediaRelay(this, relayChanged);
        relay.start();
        Hu.log("ServerService up");
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        String a = i == null ? null : i.getAction();
        if (ACTION_START_NOW.equals(a)) { Hu.log("manual start"); doStart(); }
        else if (ACTION_STOP_NOW.equals(a)) { Hu.log("manual stop"); doStop(false); }
        refresh();
        return START_STICKY;
    }

    Notification buildOngoing() {
        boolean on = Hu.isEnabled(this);
        String car = Hu.carName(this);

        PendingIntent toggle = PendingIntent.getBroadcast(this, 2,
                new Intent(this, ControlReceiver.class).setAction(ControlReceiver.ACTION_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // The pause switch covers the car watcher only; the relay keeps
        // listening, so its state leads the text either way.
        String text = "relay " + Hu.relayState + ". "
                + (on ? Hu.lastResult : "Not watching for the car.");
        return new Notification.Builder(this, "thardeck")
                .setContentTitle(on ? "Watching for " + car : "Thar Deck paused")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_status)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(
                        null, on ? "Pause" : "Resume", toggle).build())
                .setOngoing(true)
                .build();
    }

    void postAction(String title, String text, String action) {
        PendingIntent pi = PendingIntent.getForegroundService(this, 7,
                new Intent(this, ServerService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "thardeck_act")
                .setContentTitle(title).setContentText(text)
                .setSmallIcon(R.drawable.ic_status)
                .setContentIntent(pi).setAutoCancel(true)
                .addAction(new Notification.Action.Builder(null, "Start", pi).build())
                .build();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(2, n);
    }

    void refresh() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(1, buildOngoing());
        } catch (Throwable ignored) {}
    }

    @Override public void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(bt); } catch (Throwable ignored) {}
        try { unregisterReceiver(wake); } catch (Throwable ignored) {}
        cancel(pendingConnect); cancel(pendingDisconnect); cancel(relaunchCheck);
        if (relay != null) { relay.stop(); relay = null; }
        Hu.log("ServerService down");
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
