package com.abhi.thardeck.wave;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.abhi.thardeck.wave.engine.Cmd;

/**
 * Simulation and bench hook, for testing without a hand in front of the camera.
 *
 *   am broadcast -p com.abhi.thardeck.wave -a com.abhi.thardeck.wave.SIM --es token thardeck --es cmd NEXT
 *
 * cmd is one of:
 *   VOL_UP, VOL_DOWN, PLAY_PAUSE, NEXT, PREV  straight to the sender and the
 *        HUD, exactly as a recognised gesture would (this reaches the phone)
 *   PING  re-run discovery
 *   SNAP  save the next analysed frame, already in the driver's frame, to
 *        files/snap.png, to check the orientation transform by eye
 *   DUMP  write the current state to the log (and so to the drive log)
 *   HUD   show each HUD state in turn on the screen; display only
 *
 * Exported so adb can reach it, which means any app on the tablet can send
 * it an intent. So it demands the relay token, the same shared secret the
 * phone checks in every datagram; without it the intent is dropped.
 *
 * Needs the service running; it never starts the camera itself.
 */
public class SimReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        EventLog.init(c);
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
        if ("DUMP".equalsIgnoreCase(cmd)) {
            dump(s);
            return;
        }
        if (s == null) {
            Wave.log("sim: service not running, " + cmd + " dropped");
            return;
        }
        if ("HUD".equalsIgnoreCase(cmd)) {
            Wave.log("sim: HUD preview");
            s.hudPreview();
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

    /** Everything the status screen shows, as log lines. */
    private static void dump(GestureService s) {
        Wave.log("dump: service " + (s == null ? "not running" : Wave.serviceState)
                + ", camera " + Wave.cameraInfo + ", delegate " + Wave.delegate);
        Wave.log(String.format(java.util.Locale.ROOT,
                "dump: camera %.1f fps, analysed %.1f fps, inference %.0f ms, hand %s (%d%% of last 5 s)",
                Wave.cameraFps, Wave.analysedFps, Wave.inferMs,
                Wave.handPresent ? "present" : "absent", Wave.handPct));
        Wave.log(String.format(java.util.Locale.ROOT,
                "dump: gate %s, motion %.1f, light %s, luma %d, exposure %.1f ms",
                Wave.gateOn ? "on" : "off", Wave.motion, Wave.night ? "night" : "day",
                Wave.luma, Wave.exposureMs));
        Wave.log("dump: engine " + Wave.engineLine);
        Wave.log("dump: last command " + Wave.lastCommand + ", relay " + Wave.relayTarget
                + ", transform " + Wave.transform);
    }
}
