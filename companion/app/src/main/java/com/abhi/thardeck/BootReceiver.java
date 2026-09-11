package com.abhi.thardeck;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Bring the watcher back after a reboot, so it needs no manual restart. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) {
            try {
                c.startForegroundService(new Intent(c, ServerService.class));
            } catch (Throwable t) {
                Hu.log("boot start failed: " + t);
            }
        }
    }
}
