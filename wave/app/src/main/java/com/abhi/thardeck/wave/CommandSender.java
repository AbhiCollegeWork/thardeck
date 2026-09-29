package com.abhi.thardeck.wave;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Finds the phone's media relay and sends it commands over UDP.
 *
 * Protocol (shared with the companion app): one ASCII datagram per command to
 * port 5299, "TD1 token CMD". The phone answers only PING, with
 * "TD1 PONG model", sent back to our address and port.
 *
 * Discovery, in order, first responder wins and is cached for ten minutes:
 *   1. the default gateway, which is the phone when we are on its hotspot
 *   2. the subnet broadcast address, for a shared home network
 *   3. a manually configured address, if one is set
 *
 * Commands are fire and forget. To notice a phone that has gone away, each
 * command is followed by a PING to the cached target; three commands in a row
 * without a PONG drop the cache and the next command re-probes. A Wi-Fi change
 * re-probes as well.
 *
 * Everything that touches the network runs on one sender thread; replies are
 * read on a second thread.
 */
final class CommandSender {
    static final int PORT = 5299;
    static final long CACHE_MS = 10 * 60_000L;
    static final long PONG_WAIT_MS = 600;
    /** After a failed discovery, commands do not re-probe for this long. */
    static final long FAILED_QUIET_MS = 5_000;
    static final int MAX_UNANSWERED = 3;

    private final Context ctx;
    private HandlerThread thread;
    private Handler h;
    private Thread rx;
    private volatile boolean running;
    private volatile DatagramSocket sock;

    private volatile InetAddress target;
    private volatile String targetName;
    private final AtomicInteger unanswered = new AtomicInteger();
    private long lastFailedDiscovery = Long.MIN_VALUE / 4;

    private final Object pongLock = new Object();
    private boolean awaiting;
    private InetAddress pongFrom;
    private String pongName;

    private final Runnable expire = new Runnable() { @Override public void run() {
        discover("cache expired");
    }};
    private final Runnable netChanged = new Runnable() { @Override public void run() {
        target = null;
        publish();
        discover("network change");
    }};

    CommandSender(Context c) { this.ctx = c.getApplicationContext(); }

    void start() {
        if (running) return;
        running = true;
        try {
            DatagramSocket s = new DatagramSocket();
            s.setBroadcast(true);
            sock = s;
        } catch (IOException e) {
            Wave.log("socket: could not open yet (" + e.getMessage() + "), receive thread will retry");
        }
        thread = new HandlerThread("wave-send");
        thread.start();
        h = new Handler(thread.getLooper());
        rx = new Thread(new Runnable() { @Override public void run() { rxLoop(); } }, "wave-rx");
        rx.start();
        h.post(new Runnable() { @Override public void run() { discover("startup"); } });
    }

    void stop() {
        running = false;
        DatagramSocket s = sock;
        if (s != null) s.close();
        if (thread != null) thread.quitSafely();
        target = null;
        publish();
    }

    /** Queue a protocol command (VOL_UP, NEXT, ...) for sending. */
    void send(final String cmd) {
        if (!running) return;
        h.post(new Runnable() { @Override public void run() { doSend(cmd); } });
    }

    /** Forget the cached phone and probe again, now. */
    void rediscover(final String why) {
        if (!running) return;
        h.post(new Runnable() { @Override public void run() {
            target = null;
            publish();
            discover(why);
        }});
    }

    /** Wi-Fi changed. Debounced, because one change fires several callbacks. */
    void networkChanged() {
        if (!running) return;
        h.removeCallbacks(netChanged);
        h.postDelayed(netChanged, 1500);
    }

    // ---- sender thread -----------------------------------------------------------

    private void doSend(String cmd) {
        if (target != null && unanswered.get() >= MAX_UNANSWERED) {
            Wave.log("relay: " + MAX_UNANSWERED + " commands without a PONG, re-probing");
            target = null;
            publish();
        }
        if (target == null) {
            if (SystemClock.uptimeMillis() - lastFailedDiscovery < FAILED_QUIET_MS
                    || !discover("command " + cmd)) {
                Wave.log("send: " + cmd + " not sent, no phone found");
                return;
            }
        }
        InetAddress t = target;
        if (t == null) return;
        if (sendRaw(cmd, t)) {
            Wave.log("send: " + cmd + " -> " + t.getHostAddress() + ":" + PORT);
            sendRaw("PING", t); // liveness check, the answer resets the counter
            unanswered.incrementAndGet();
        }
    }

    /**
     * Probes gateway, broadcast, then the manual address, stopping at the
     * first PONG. Runs on the sender thread and blocks it for at most three
     * short waits.
     *
     * @return true if a phone answered.
     */
    private boolean discover(String why) {
        h.removeCallbacks(expire);
        InetAddress[] cand = new InetAddress[3];
        String[] label = {"gateway", "broadcast", "manual"};
        findLocal(cand);
        String manual = Wave.manualAddress(ctx);
        if (!manual.isEmpty()) {
            try { cand[2] = InetAddress.getByName(manual); }
            catch (Exception e) { Wave.log("discovery: manual address does not resolve: " + e.getMessage()); }
        }

        StringBuilder tried = new StringBuilder();
        for (int i = 0; i < cand.length; i++) {
            if (tried.length() > 0) tried.append(", ");
            if (cand[i] == null) { tried.append(label[i]).append(" none"); continue; }
            tried.append(label[i]).append(' ').append(cand[i].getHostAddress());
            synchronized (pongLock) { awaiting = true; pongFrom = null; pongName = null; }
            Wave.log("discovery: PING " + label[i] + " " + cand[i].getHostAddress() + ":" + PORT);
            if (!sendRaw("PING", cand[i])) continue;
            InetAddress from; String name;
            synchronized (pongLock) {
                long until = SystemClock.uptimeMillis() + PONG_WAIT_MS;
                long left;
                while (pongFrom == null && (left = until - SystemClock.uptimeMillis()) > 0) {
                    try { pongLock.wait(left); } catch (InterruptedException e) { break; }
                }
                awaiting = false;
                from = pongFrom; name = pongName;
            }
            if (from != null) {
                target = from;
                targetName = name;
                unanswered.set(0);
                publish();
                Wave.log("discovery (" + why + "): found " + name + " at "
                        + from.getHostAddress() + " via " + label[i]);
                h.postDelayed(expire, CACHE_MS);
                return true;
            }
        }
        target = null;
        lastFailedDiscovery = SystemClock.uptimeMillis();
        publish();
        // Expected at a desk without the phone: an info line, not an error.
        Wave.log("discovery (" + why + "): no phone found, tried " + tried
                + ". Commands are not sent until a phone answers.");
        return false;
    }

    /** Default gateway and subnet broadcast of the active network, IPv4. */
    private void findLocal(InetAddress[] out) {
        try {
            ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
            Network n = cm == null ? null : cm.getActiveNetwork();
            LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
            if (lp == null) { Wave.log("discovery: no active network"); return; }
            for (RouteInfo r : lp.getRoutes()) {
                if (r.isDefaultRoute() && r.hasGateway() && r.getGateway() instanceof Inet4Address) {
                    out[0] = r.getGateway();
                    break;
                }
            }
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (!(la.getAddress() instanceof Inet4Address)) continue;
                int prefix = la.getPrefixLength();
                int ip = ByteBuffer.wrap(la.getAddress().getAddress()).getInt();
                int mask = prefix == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - prefix));
                int bc = ip | ~mask;
                out[1] = InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(bc).array());
                break;
            }
        } catch (Throwable t) {
            Wave.log("discovery: could not read network: " + t);
        }
    }

    private boolean sendRaw(String cmd, InetAddress to) {
        DatagramSocket s = sock;
        if (s == null) {
            Wave.log("send: socket not ready, " + cmd + " dropped");
            return false;
        }
        byte[] b = ("TD1 " + Wave.token(ctx) + " " + cmd).getBytes(StandardCharsets.US_ASCII);
        try {
            s.send(new DatagramPacket(b, b.length, to, PORT));
            return true;
        } catch (IOException e) {
            Wave.log("send: " + cmd + " to " + to.getHostAddress() + " failed: " + e.getMessage());
            return false;
        }
    }

    private void publish() {
        InetAddress t = target;
        Wave.relayTarget = t == null ? "not found" : targetName + " at " + t.getHostAddress();
    }

    // ---- receive thread ----------------------------------------------------------

    private void rxLoop() {
        byte[] buf = new byte[512];
        while (running) {
            DatagramSocket s = sock;
            try {
                if (s == null || s.isClosed()) {
                    s = new DatagramSocket();
                    s.setBroadcast(true);
                    sock = s;
                }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                onPacket(new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII).trim(),
                        p.getAddress());
            } catch (IOException e) {
                if (!running) break;
                // A network change can tear the socket down. Reopen after a pause.
                Wave.log("socket: " + e.getMessage() + ", reopening in 1 s");
                if (s != null) s.close();
                sock = null;
                SystemClock.sleep(1000);
            }
        }
        DatagramSocket s = sock;
        if (s != null) s.close();
        sock = null;
    }

    private void onPacket(String msg, InetAddress from) {
        if (!msg.startsWith("TD1 PONG")) return;
        String name = msg.length() > 8 ? msg.substring(8).trim() : "phone";
        if (name.isEmpty()) name = "phone";
        Wave.log("pong: " + name + " from " + from.getHostAddress());
        if (from.equals(target)) unanswered.set(0);
        synchronized (pongLock) {
            if (awaiting && pongFrom == null) {
                pongFrom = from;
                pongName = name;
                pongLock.notifyAll();
            }
        }
    }
}
