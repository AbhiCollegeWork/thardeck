package com.abhi.thardeck;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Media relay for Thar Deck Wave.
 *
 * Audio does not pass through the dashboard tablet: music plays from this
 * phone over Bluetooth to the car. So a hand gesture recognised on the tablet
 * has to be carried here and applied to this phone's music stream. The tablet
 * sends one ASCII line per UDP datagram to port 5299:
 *
 *   TD1 <token> <CMD>      CMD is PING, VOL_UP, VOL_DOWN, PLAY_PAUSE, NEXT, PREV
 *
 * A datagram with the wrong token, or anything malformed, is dropped silently.
 * PING is answered to the sender with "TD1 PONG <phone model>", which is how
 * the tablet discovers the phone. Everything else is fire-and-forget.
 *
 * The socket is bound to the wildcard address so it hears both unicast (the
 * tablet pinging its gateway on the phone's hotspot) and subnet broadcast (both
 * devices on home Wi-Fi). A network change can tear the socket down under us;
 * on any IOException the loop closes it, waits a second and binds again.
 *
 * Owned by ServerService: started in onCreate, stopped in onDestroy. The token
 * is read from prefs on every datagram, so SET_TOKEN takes effect at once. The
 * token is never logged.
 */
public final class MediaRelay {

    public static final int PORT = 5299;
    static final String MAGIC = "TD1";
    static final long REOPEN_DELAY_MS = 1_000;

    private final Context ctx;
    private final Runnable onStateChange;
    private volatile boolean running = false;
    private volatile DatagramSocket sock;
    private Thread thread;

    /** @param onStateChange called on the relay thread whenever the state
     *                       shown to the user changes; may be null. */
    public MediaRelay(Context c, Runnable onStateChange) {
        this.ctx = c.getApplicationContext();
        this.onStateChange = onStateChange;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "thardeck-relay");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        closeQuietly();
        if (thread != null) thread.interrupt();
        thread = null;
        state("stopped");
    }

    // ---- socket loop --------------------------------------------------------

    private void loop() {
        byte[] buf = new byte[256];
        while (running) {
            try {
                DatagramSocket s = new DatagramSocket(null);
                s.setReuseAddress(true);
                s.setBroadcast(true);
                s.bind(new InetSocketAddress(PORT));
                sock = s;
                state("listening on " + PORT);
                Hu.log("relay: listening on " + PORT);
                while (running) {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    s.receive(pkt);
                    handle(s, pkt);
                }
            } catch (IOException e) {
                if (!running) break;
                Hu.log("relay: socket error, reopening in 1 s: " + e);
                state("reopening after network change");
            } catch (Throwable t) {
                if (!running) break;
                Hu.log("relay: unexpected error, reopening in 1 s: " + t);
                state("reopening after error");
            }
            closeQuietly();
            if (!running) break;
            SystemClock.sleep(REOPEN_DELAY_MS);
        }
        closeQuietly();
    }

    private void closeQuietly() {
        DatagramSocket s = sock;
        sock = null;
        if (s != null) {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    // ---- protocol -----------------------------------------------------------

    private void handle(DatagramSocket s, DatagramPacket pkt) throws IOException {
        String line = new String(pkt.getData(), pkt.getOffset(), pkt.getLength(),
                StandardCharsets.US_ASCII).trim();
        String[] parts = line.split("\\s+");
        if (parts.length != 3 || !MAGIC.equals(parts[0])) { Hu.relayDropped++; return; }
        if (!tokenOk(parts[1])) { Hu.relayDropped++; return; }

        String cmd = parts[2];
        String from = pkt.getAddress().getHostAddress() + ":" + pkt.getPort();

        if ("PING".equals(cmd)) {
            byte[] reply = (MAGIC + " PONG " + Build.MODEL).getBytes(StandardCharsets.US_ASCII);
            s.send(new DatagramPacket(reply, reply.length, pkt.getAddress(), pkt.getPort()));
            Hu.log("relay: PING from " + from + ", PONG sent");
            return;
        }

        boolean ok;
        switch (cmd) {
            case "VOL_UP":     ok = volume(AudioManager.ADJUST_RAISE); break;
            case "VOL_DOWN":   ok = volume(AudioManager.ADJUST_LOWER); break;
            case "PLAY_PAUSE": ok = mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); break;
            case "NEXT":       ok = mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT); break;
            case "PREV":       ok = mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); break;
            default:           Hu.relayDropped++; return;
        }
        Hu.log("relay: " + cmd + " from " + from + (ok ? "" : " (failed)"));
        // No notification refresh per command: volume steps arrive up to 8 a
        // second and Android rate limits updates. The status screen polls this.
        Hu.relayLast = cmd + (ok ? "" : " (failed)") + " at " + ServerService.now();
    }

    /** Constant-time compare, so a wrong guess learns nothing from timing. */
    private boolean tokenOk(String got) {
        String want = Hu.token(ctx);
        return MessageDigest.isEqual(got.getBytes(StandardCharsets.UTF_8),
                want.getBytes(StandardCharsets.UTF_8));
    }

    private boolean volume(int direction) {
        try {
            AudioManager am = ctx.getSystemService(AudioManager.class);
            if (am == null) return false;
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
            return true;
        } catch (Throwable t) {
            Hu.log("relay: volume failed: " + t);
            return false;
        }
    }

    /** Public API that reaches whichever media session is active. */
    private boolean mediaKey(int code) {
        try {
            AudioManager am = ctx.getSystemService(AudioManager.class);
            if (am == null) return false;
            long t = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
            return true;
        } catch (Throwable t) {
            Hu.log("relay: media key failed: " + t);
            return false;
        }
    }

    private void state(String s) {
        Hu.relayState = s;
        if (onStateChange != null) onStateChange.run();
    }
}
