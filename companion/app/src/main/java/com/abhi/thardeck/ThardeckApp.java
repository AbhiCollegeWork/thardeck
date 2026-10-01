package com.abhi.thardeck;

import android.app.Application;

/** Gives the durable event log its context before any service, receiver or
 *  activity runs, so every Hu.log line from any entry point reaches the file. */
public class ThardeckApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        EventLog.init(this);
    }
}
