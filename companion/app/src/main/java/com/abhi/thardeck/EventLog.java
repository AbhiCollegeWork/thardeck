package com.abhi.thardeck;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Durable copy of every Hu.log line, so a drive can be analysed afterwards.
 *
 * Logcat alone is not enough: the ring buffer holds minutes, and an
 * adb-spawned capture on the phone died silently once and lost a drive. This
 * appends to getExternalFilesDir(null)/thardeck-events.log, which needs no
 * permission and can be pulled with adb:
 *
 *   adb pull /sdcard/Android/data/com.abhi.thardeck/files/thardeck-events.log
 *
 * The file opens lazily on the first line, flushes after every line, and at
 * 2 MB rotates to thardeck-events.1.log, keeping one old file. Callers must
 * never pass a secret; the relay token is never given to Hu.log.
 */
final class EventLog {

    static final String NAME = "thardeck-events.log";
    static final String OLD_NAME = "thardeck-events.1.log";
    static final long MAX_BYTES = 2L * 1024 * 1024;

    private static Context ctx;
    private static File file;
    private static Writer out;
    private static long size;
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    /** Called once from ThardeckApp before any component runs. */
    static synchronized void init(Context c) {
        if (ctx == null && c != null) ctx = c.getApplicationContext();
    }

    static synchronized void append(String line) {
        if (ctx == null) return;
        try {
            if (out == null && !open()) return;
            String s = TS.format(new Date()) + " " + line + "\n";
            out.write(s);
            out.flush();
            size += s.getBytes(StandardCharsets.UTF_8).length;
            if (size >= MAX_BYTES) rotate();
        } catch (Throwable t) {
            // Never call Hu.log from here; that would recurse.
            Log.w(Hu.TAG, "event log write failed: " + t);
            closeQuietly();
        }
    }

    private static boolean open() throws IOException {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) return false;   // storage not available right now
        if (!dir.exists() && !dir.mkdirs()) return false;
        file = new File(dir, NAME);
        size = file.length();
        out = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8);
        return true;
    }

    private static void rotate() {
        closeQuietly();
        File old = new File(file.getParentFile(), OLD_NAME);
        if (old.exists() && !old.delete()) Log.w(Hu.TAG, "event log: could not delete old file");
        if (!file.renameTo(old)) Log.w(Hu.TAG, "event log: rotate rename failed");
        // The next append reopens a fresh file.
    }

    private static void closeQuietly() {
        if (out != null) {
            try { out.close(); } catch (Throwable ignored) {}
        }
        out = null;
    }

    private EventLog() {}
}
