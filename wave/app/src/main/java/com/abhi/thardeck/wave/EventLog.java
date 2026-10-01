package com.abhi.thardeck.wave;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A durable copy of every THARWAVE log line, for looking at a drive after the
 * fact (logcat on the tablet does not keep that long).
 *
 * Appends to wave-events.log in the app's external files directory, which adb
 * can pull without root:
 *
 *   adb pull /sdcard/Android/data/com.abhi.thardeck.wave/files/wave-events.log
 *
 * At 2 MB the file is renamed to wave-events.1.log, replacing the one before,
 * and a new one is started. Writes happen on one background thread, in order.
 */
final class EventLog {
    static final String NAME = "wave-events.log";
    static final String OLD = "wave-events.1.log";
    static final long MAX_BYTES = 2L * 1024 * 1024;

    private static volatile File dir;
    private static final ExecutorService writer = Executors.newSingleThreadExecutor();
    private static Writer out;      // writer thread only
    private static long size;       // writer thread only
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT); // writer thread only

    /** Called once the app has a context; lines before that go to logcat only. */
    static void init(Context c) {
        if (dir != null) return;
        File d = c.getApplicationContext().getExternalFilesDir(null);
        if (d != null) dir = d;
    }

    static void append(final String line) {
        if (dir == null) return;
        final long when = System.currentTimeMillis();
        writer.execute(new Runnable() { @Override public void run() { write(when, line); } });
    }

    private static void write(long when, String line) {
        try {
            if (out == null) open();
            if (out == null) return;
            String s = FMT.format(new Date(when)) + " " + line + "\n";
            out.write(s);
            out.flush();
            size += s.getBytes(StandardCharsets.UTF_8).length;
            if (size >= MAX_BYTES) rotate();
        } catch (Throwable t) {
            closeQuietly();
        }
    }

    private static void open() throws java.io.IOException {
        File f = new File(dir, NAME);
        size = f.length();
        out = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8);
    }

    private static void rotate() throws java.io.IOException {
        closeQuietly();
        File f = new File(dir, NAME), old = new File(dir, OLD);
        if (old.exists()) old.delete();
        f.renameTo(old);
        open();
    }

    private static void closeQuietly() {
        try { if (out != null) out.close(); } catch (Throwable ignored) {}
        out = null;
    }

    private EventLog() {}
}
