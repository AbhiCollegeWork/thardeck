package com.abhi.thardeck.wave;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.abhi.thardeck.wave.engine.Cmd;

/**
 * Simulation hook for testing without a hand in front of the camera.
 *
 *   am broadcast -p com.abhi.thardeck.wave -a com.abhi.thardeck.wave.SIM --es token thardeck --es cmd NEXT
 *
 * cmd is a protocol command (VOL_UP, VOL_DOWN, PLAY_PAUSE, NEXT, PREV) and
 * goes straight to the sender and the HUD, exactly as a recognised gesture
 * would. Two extras for the bench: cmd PING re-runs discovery, and cmd SNAP
 * saves the next analysed frame (already rotated and mirrored into the
 * driver's frame) to files/snap.png so the transform can be checked by eye.
 *
 * Exported so adb can reach it, which means any app on the tablet can send
 * it an intent. So it demands the relay token, the same shared secret the
 * phone checks in every datagram; without it the intent is dropped.
 *
 * Needs the service running; it never starts the camera itself.
 */
public class SimReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String given = i.getStringExtra("token");
        if (given == null || !given.trim().equals(Wave.token(c))) {
            Wave.log("sim: bad token, dropped"); // never log either token
            return;
        }
        String cmd = i.getStringExtra("cmd");
        GestureService s = Wave.service;
        if ("SNAP".equalsIgnoreCase(cmd)) {
            Wave.snapRequested = true;
            Wave.log("sim: snapshot requested");
            return;
        }
        if (s == null) {
            Wave.log("sim: service not running, " + cmd + " dropped");
            return;
        }
        if ("PING".equalsIgnoreCase(cmd)) {
            Wave.log("sim: PING, re-running discovery");
            s.rediscover("sim");
            return;
        }
        Cmd c2 = Cmd.parse(cmd);
        if (c2 == null) {
            Wave.log("sim: unknown command " + cmd);
            return;
        }
        Wave.log("sim: " + c2);
        s.simulate(c2);
    }
}
