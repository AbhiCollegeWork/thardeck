package com.abhi.thardeck.wave;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.abhi.thardeck.wave.engine.Cmd;

/**
 * The gesture service. Owns the camera pipeline, the sender and the HUD.
 *
 * A camera foreground service, which Android only lets an app start while it
 * is in the foreground, so MainActivity starts it (and the boot receiver goes
 * through MainActivity). Pause releases the camera and the model but keeps the
 * service, so Resume from the notification works from the background.
 */
public class GestureService extends Service {

    public static final String ACTION_PAUSE = "com.abhi.thardeck.wave.PAUSE";
    public static final String ACTION_RESUME = "com.abhi.thardeck.wave.RESUME";
    public static final String ACTION_STOP = "com.abhi.thardeck.wave.STOP";
    public static final String ACTION_RESTART = "com.abhi.thardeck.wave.RESTART";

    private static final String CHANNEL = "wave";
    private static final int NOTE_ID = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Pipeline pipeline;
    private CommandSender sender;
    private Hud hud;
    private boolean paused = false;
    private ConnectivityManager.NetworkCallback netCb;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Gesture control", NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTE_ID, buildNote(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);

        hud = new Hud(this);
        sender = new CommandSender(this);
        sender.start();
        pipeline = new Pipeline(this, new Pipeline.Listener() {
            @Override public void onCommand(Cmd c) { command(c, "gesture"); }
            @Override public void onTilt(boolean active, double progress) {
                hud.tilt(active, progress);
            }
        });
        Wave.service = this;
        registerNetwork();
        pipeline.start();
        Wave.log("GestureService up");
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        String a = i == null ? null : i.getAction();
        if (ACTION_PAUSE.equals(a)) pause();
        else if (ACTION_RESUME.equals(a)) resume();
        else if (ACTION_STOP.equals(a)) { Wave.log("stop requested"); stopSelf(); }
        else if (ACTION_RESTART.equals(a) && !paused) {
            Wave.log("restarting pipeline for new settings");
            pipeline.stop();
            pipeline.start();
        }
        return START_STICKY;
    }

    // ---- commands ------------------------------------------------------------

    /** From the engine or the simulation hook. Any thread. */
    void command(Cmd c, String source) {
        Wave.lastCommand = c + " (" + source + ") at " + Wave.now();
        Wave.log("command: " + c + " from " + source);
        hud.command(c);
        sender.send(c.name());
    }

    /** Simulation hook entry: straight to the sender and the HUD. */
    void simulate(Cmd c) { command(c, "sim"); }

    void rediscover(String why) { sender.rediscover(why); }

    // ---- pause and resume -----------------------------------------------------

    void pause() {
        if (paused) return;
        paused = true;
        pipeline.stop();
        hud.release();
        Wave.serviceState = "paused";
        Wave.log("paused, camera released");
        refreshNote();
    }

    void resume() {
        if (!paused) return;
        paused = false;
        pipeline.start();
        Wave.log("resumed");
        refreshNote();
    }

    boolean isPaused() { return paused; }

    // ---- network ------------------------------------------------------------

    private void registerNetwork() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        netCb = new ConnectivityManager.NetworkCallback() {
            // Registration replays the current network, and several callbacks
            // fire for one change, so act only when the network or its
            // addresses and routes actually differ from what was last seen.
            private String seen = null;
            private void check(Network n, LinkProperties lp) {
                String sig = n + " " + (lp == null ? "" : lp.getLinkAddresses() + " " + lp.getRoutes());
                if (seen == null) { seen = sig; return; }
                if (sig.equals(seen)) return;
                seen = sig;
                Wave.log("network: changed");
                sender.networkChanged();
            }
            @Override public void onAvailable(Network n) {
                check(n, getSystemService(ConnectivityManager.class).getLinkProperties(n));
            }
            @Override public void onLinkPropertiesChanged(Network n, LinkProperties lp) {
                check(n, lp);
            }
            @Override public void onLost(Network n) {
                seen = "lost";
                Wave.log("network: lost");
                sender.networkChanged();
            }
        };
        try { cm.registerDefaultNetworkCallback(netCb); }
        catch (Throwable t) { Wave.log("network: callback failed: " + t); netCb = null; }
    }

    // ---- notification -------------------------------------------------------

    private Notification buildNote() {
        PendingIntent open = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent toggle = PendingIntent.getService(this, 2,
                new Intent(this, GestureService.class).setAction(paused ? ACTION_RESUME : ACTION_PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle(paused ? "Wave paused" : "Wave watching for gestures")
                .setContentText(paused ? "Camera off. Resume to control music by hand."
                        : "Front camera on, low rate until a hand is close.")
                .setSmallIcon(R.drawable.ic_status)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, paused ? "Resume" : "Pause", toggle).build())
                .setOngoing(true)
                .build();
    }

    private void refreshNote() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTE_ID, buildNote());
        } catch (Throwable ignored) {}
    }

    @Override public void onDestroy() {
        super.onDestroy();
        Wave.service = null;
        if (netCb != null) {
            try { getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCb); }
            catch (Throwable ignored) {}
        }
        pipeline.destroy();
        sender.stop();
        hud.release();
        Wave.serviceState = "stopped";
        Wave.relayTarget = "not found";
        Wave.log("GestureService down");
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
