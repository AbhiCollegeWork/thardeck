package com.abhi.thardeck;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Manual controls, from the notification, the status screen, or adb:
 *   adb shell am broadcast -a com.abhi.thardeck.TOGGLE
 *   adb shell am broadcast -a com.abhi.thardeck.ON
 *   adb shell am broadcast -a com.abhi.thardeck.OFF
 *   adb shell am broadcast -a com.abhi.thardeck.START   (start the server now)
 *   adb shell am broadcast -a com.abhi.thardeck.STOP    (stop it now)
 *   adb shell am broadcast -a com.abhi.thardeck.SET_CAR --es name "Auto 12"
 *
 * SET_CAR picks the bonded device by name, so setup needs no address typed
 * anywhere. The name defaults to the one Android Auto vehicles usually carry.
 */
public class ControlReceiver extends BroadcastReceiver {

    public static final String ACTION_TOGGLE = "com.abhi.thardeck.TOGGLE";
    public static final String ACTION_ON = "com.abhi.thardeck.ON";
    public static final String ACTION_OFF = "com.abhi.thardeck.OFF";
    public static final String ACTION_START = "com.abhi.thardeck.START";
    public static final String ACTION_STOP = "com.abhi.thardeck.STOP";
    public static final String ACTION_SET_CAR = "com.abhi.thardeck.SET_CAR";

    @Override public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (a == null) return;
        switch (a) {
            case ACTION_TOGGLE:
            case ACTION_ON:
            case ACTION_OFF: {
                boolean on = ACTION_ON.equals(a) || (ACTION_TOGGLE.equals(a) && !Hu.isEnabled(c));
                Hu.setEnabled(c, on);
                Hu.log("watcher " + (on ? "ON" : "OFF"));
                svc(c, ServerService.ACTION_REFRESH);
                break;
            }
            case ACTION_START:
                svc(c, ServerService.ACTION_START_NOW);
                break;
            case ACTION_STOP:
                svc(c, ServerService.ACTION_STOP_NOW);
                break;
            case ACTION_SET_CAR:
                setCarByName(c, i.getStringExtra("name"));
                break;
        }
    }

    /** Store the bonded device whose name matches, so no address is ever typed.
     *  Defaults to the usual Android Auto vehicle name. */
    private void setCarByName(Context c, String name) {
        if (name == null || name.isEmpty()) name = "Auto 12";
        try {
            BluetoothManager bm = c.getSystemService(BluetoothManager.class);
            BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
            if (ad == null) { Hu.log("no bluetooth adapter"); return; }
            for (BluetoothDevice d : ad.getBondedDevices()) {
                String n = null;
                try { n = d.getName(); } catch (Throwable ignored) {}
                if (name.equals(n)) {
                    Hu.setCar(c, d.getAddress(), name);
                    Hu.log("car set to '" + name + "' by name");
                    svc(c, ServerService.ACTION_REFRESH);
                    return;
                }
            }
            Hu.log("no bonded device named '" + name + "'");
        } catch (Throwable t) {
            Hu.log("set car failed: " + t);
        }
    }

    private void svc(Context c, String action) {
        try {
            c.startForegroundService(new Intent(c, ServerService.class).setAction(action));
        } catch (Throwable t) {
            Hu.log("service start failed: " + t);
        }
    }
}
