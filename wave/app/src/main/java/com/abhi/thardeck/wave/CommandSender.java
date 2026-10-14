package com.abhi.thardeck.wave;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
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

    /** Told whether a command went out, so the HUD can say "No phone". */
    interface Result { void done(boolean sent); }

    /** True when a phone is known, so a command will go straight out. */
    boolean hasTarget() { return target != null; }

    /** Queue a protocol command (VOL_UP, NEXT, ...) for sending. */
    void send(final String cmd, final Result result) {
        if (!running) { if (result != null) result.done(false); return; }
        h.post(new Runnable() { @Override public void run() {
            boolean sent = doSend(cmd);
            if (result != null) result.done(sent);
        }});
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

    /** @return true if the command went out to a phone. */
    private boolean doSend(String cmd) {
        if (target != null && unanswered.get() >= MAX_UNANSWERED) {
            Wave.log("relay: " + MAX_UNANSWERED + " commands without a PONG, re-probing");
            target = null;
            publish();
        }
        if (target == null) {
            if (SystemClock.uptimeMillis() - lastFailedDiscovery < FAILED_QUIET_MS
                    || !discover("command " + cmd)) {
                Wave.log("send: " + cmd + " not sent, no phone found");
                return false;
            }
        }
        InetAddress t = target;
        if (t == null) return false;
        if (!sendRaw(cmd, t)) return false;
        Wave.log("send: " + cmd + " -> " + t.getHostAddress() + ":" + PORT);
        sendRaw("PING", t); // liveness check, the answer resets the counter
        unanswered.incrementAndGet();
        return true;
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
        Local loc = findLocal();
        logNet(loc);
        cand[0] = loc.gateway;
        cand[1] = loc.broadcast;
        String manual = Wave.manualAddress(ctx);
        if (!manual.isEmpty()) {
            try {
                InetAddress m = InetAddress.getByName(manual);
                // Only where it could answer: on the subnet the tablet is on.
                if (loc.sameSubnet(m)) cand[2] = m;
                else Wave.log("discovery: manual address not on this subnet, skipped");
            } catch (Exception e) {
                Wave.log("discovery: manual address does not resolve: " + e.getMessage());
            }
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
        notOnHotspot(loc);
        // Expected at a desk without the phone: an info line, not an error.
        Wave.log("discovery (" + why + "): no phone found, tried " + tried
                + ". Commands are not sent until a phone answers.");
        return false;
    }

    /** The tablet's own network, as discovery sees it. */
    static final class Local {
        String wifi = "off";        // on, off, or disconnected
        Inet4Address ip;            // the tablet's IPv4 address, or null
        int prefix;                 // its prefix length
        InetAddress gateway;        // default gateway, IPv4, or null
        InetAddress broadcast;      // subnet broadcast, or null

        boolean sameSubnet(InetAddress a) {
            if (ip == null || !(a instanceof Inet4Address)) return false;
            int mask = prefix == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - prefix));
            int mine = ByteBuffer.wrap(ip.getAddress()).getInt();
            int theirs = ByteBuffer.wrap(a.getAddress()).getInt();
            return (mine & mask) == (theirs & mask);
        }
    }

    /** Wi-Fi state, own address, default gateway and subnet broadcast. */
    private Local findLocal() {
        Local l = new Local();
        try {
            WifiManager wm = ctx.getSystemService(WifiManager.class);
            ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
            Network n = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
            boolean onWifi = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            l.wifi = wm != null && !wm.isWifiEnabled() ? "off" : onWifi ? "on" : "disconnected";
            LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
            if (lp == null) return l;
            for (RouteInfo r : lp.getRoutes()) {
                if (r.isDefaultRoute() && r.hasGateway() && r.getGateway() instanceof Inet4Address) {
                    l.gateway = r.getGateway();
                    break;
                }
            }
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (!(la.getAddress() instanceof Inet4Address)) continue;
                l.ip = (Inet4Address) la.getAddress();
                l.prefix = la.getPrefixLength();
                int ip = ByteBuffer.wrap(l.ip.getAddress()).getInt();
                int mask = l.prefix == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - l.prefix));
                l.broadcast = InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(ip | ~mask).array());
                break;
            }
        } catch (Throwable t) {
            Wave.log("discovery: could not read network: " + t);
        }
        return l;
    }

    /**
     * One line per discovery attempt with the tablet's own network state. A
     * network name is present exactly when Wi-Fi is connected; the name
     * itself is never read or logged.
     */
    private static void logNet(Local l) {
        Wave.log("net: ssid-set=" + ("on".equals(l.wifi) ? "yes" : "no")
                + " ip=" + (l.ip == null ? "none" : l.ip.getHostAddress())
                + " gateway=" + (l.gateway == null ? "none" : l.gateway.getHostAddress())
                + " wifi=" + l.wifi);
    }

    private String hotspotNoteKey;
    private long hotspotNoteAt = Long.MIN_VALUE / 4;

    /**
     * After a failed discovery on Wi-Fi: the gateway is not the phone, so the
     * tablet is not on the phone's hotspot. Said once per network, again at
     * most every minute. Nothing is done about it: the app never switches
     * networks. Android does not let an app read the list of saved networks,
     * so whether the hotspot is one of them cannot be checked here.
     */
    private void notOnHotspot(Local l) {
        if (!"on".equals(l.wifi) || l.gateway == null) return;
        String key = l.gateway.getHostAddress() + "/" + (l.ip == null ? "" : l.ip.getHostAddress());
        long now = SystemClock.uptimeMillis();
        if (key.equals(hotspotNoteKey) && now - hotspotNoteAt < 60_000) return;
        hotspotNoteKey = key;
        hotspotNoteAt = now;
        Wave.log("net: not on the phone's hotspot (gateway did not answer)");
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
