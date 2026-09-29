package com.abhi.thardeck.wave;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * After a reboot, if autostart is on, bring up MainActivity, which starts the
 * camera service and finishes. A camera foreground service cannot be started
 * from the background on this Android version, but an app holding the overlay
 * permission may start an activity from the background, and the activity may
 * start the service.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) return;
        if (!Wave.autostart(c)) {
            Wave.log("boot: autostart off");
            return;
        }
        Wave.log("boot: autostart on, launching");
        try {
            c.startActivity(new Intent(c, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_START, true)
                    .putExtra(MainActivity.EXTRA_FINISH, true));
        } catch (Throwable t) {
            Wave.log("boot: launch failed: " + t);
        }
    }
}
