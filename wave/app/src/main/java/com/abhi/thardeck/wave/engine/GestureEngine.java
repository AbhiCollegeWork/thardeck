package com.abhi.thardeck.wave.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The gesture state machine. Pure Java, no Android, so it is unit tested with
 * synthetic landmark sequences.
 *
 * Feed it one {@link HandFrame} per analysed camera frame, in time order, with
 * coordinates already in the driver's frame (+x to the driver's right, +y
 * down). It returns zero or more commands for that frame.
 *
 * Three recognisers, only one acting at a time, rotation first:
 *
 *   rotate  index fingertip circling: signed angle about the centre of a
 *           short ring buffer is accumulated, one volume step per rotStepDeg.
 *           Clockwise as the driver sees it is up.
 *   swipe   the hand centre travels far sideways, fast, and mostly level.
 *   fist    all four fingers curled, held still: play or pause, once, then
 *           the hand has to open before it can fire again.
 *
 * Nothing counts until the hand is close to the camera (bounding box height)
 * and has been there for a moment, so a hand resting on the wheel is ignored.
 *
 * Not thread safe: call it from one thread.
 */
public final class GestureEngine {

    public enum State { IDLE, ARMING, READY, ROTATING, FIST_HOLD, FIST_LATCHED, COOLDOWN }

    /** Receives one line per state transition and per emitted command. */
    public interface Log { void log(String line); }

    private static final int WRIST = 0, INDEX_TIP = 8;
    private static final int[] CENTRE_IDX = {0, 5, 9, 13, 17};
    /** PIP joint and tip for index, middle, ring and pinky. */
    private static final int[] PIP = {6, 10, 14, 18};
    private static final int[] TIP = {8, 12, 16, 20};

    private final Tuning tu;
    private Log log = new Log() { @Override public void log(String line) {} };

    private State state = State.IDLE;

    // arming
    private long presentSince = -1;
    private long lastGoodFrame = -1;

    // rotation: {t, x, y}
    private final ArrayDeque<double[]> tips = new ArrayDeque<>();
    private boolean rotating = false;
    private double acc = 0;
    private long lastRotMs = 0;
    private long lastStepMs = Long.MIN_VALUE / 4;

    // swipe: {t, x, y}
    private final ArrayDeque<double[]> centres = new ArrayDeque<>();
    private long cooldownUntil = Long.MIN_VALUE / 4;

    // fist
    private long fistSince = -1;
    private double fistX, fistY;
    private boolean fistLatched = false;

    // live values for the calibrate screen
    private double lastRadius, lastAspect, lastBoxH;
    private boolean lastFist;

    public GestureEngine(Tuning tuning) { this.tu = tuning; }

    public void setLog(Log l) {
        this.log = l != null ? l : new Log() { @Override public void log(String line) {} };
    }

    public State state() { return state; }
    public boolean rotating() { return rotating; }
    /** Progress toward the next volume step, -1..1, for the HUD ring. */
    public double rotationProgress() {
        double step = tu.rotStepDeg;
        return step <= 0 ? 0 : Math.max(-1, Math.min(1, acc / step));
    }

    /** One line of live engine state for the calibrate screen. */
    public String debugLine() {
        return String.format(java.util.Locale.ROOT,
                "%s box=%.2f fist=%s r=%.3f round=%.2f acc=%.0f",
                state, lastBoxH, lastFist, lastRadius, lastAspect, acc);
    }

    /** Forget everything, as if no hand had ever been seen. */
    public void reset() {
        resetAll();
        setState(State.IDLE, "reset");
    }

    // ---- per frame -----------------------------------------------------------

    public List<Cmd> onFrame(HandFrame f) {
        List<Cmd> out = new ArrayList<>(1);
        long t = f.t;
        lastBoxH = f.boxHeight();
        boolean big = f.present && f.boxHeight() >= tu.minBoxHeight;

        if (!big) {
            lastFist = false;
            if (lastGoodFrame >= 0 && t - lastGoodFrame <= tu.lostGraceMs) {
                // A short tracking dropout: hold state, just let rotation time out.
                maybeReleaseRotation(t);
                return out;
            }
            if (state != State.IDLE) {
                resetAll();
                setState(State.IDLE, f.present ? "hand too small" : "no hand");
            }
            return out;
        }

        lastGoodFrame = t;
        if (presentSince < 0) presentSince = t;
        if (t - presentSince < tu.armMs) {
            setState(State.ARMING, "hand close");
            return out;
        }
        if (state == State.IDLE || state == State.ARMING) setState(State.READY, "armed");

        double hx = 0, hy = 0;
        for (int i : CENTRE_IDX) { hx += f.x[i]; hy += f.y[i]; }
        hx /= CENTRE_IDX.length; hy /= CENTRE_IDX.length;
        boolean fist = isFist(f);
        lastFist = fist;

        push(tips, t, f.x[INDEX_TIP], f.y[INDEX_TIP], tu.rotWindowMs);

        // Cooldown after a swipe: nothing fires, and the swipe buffer stays
        // empty so the tail of the same swipe cannot fire again.
        if (t < cooldownUntil) {
            centres.clear();
            return out;
        }
        if (state == State.COOLDOWN) setState(State.READY, "cooldown over");

        push(centres, t, hx, hy, tu.swipeWindowMs);

        // ---- rotate, which has priority while active ----
        if (stepRotation(t, out)) {
            fistSince = -1;
            centres.clear();
            return out;
        }

        // ---- fist hold ----
        if (fistLatched) {
            if (isOpen(f)) {
                fistLatched = false;
                setState(State.READY, "hand opened");
            } else {
                return out;
            }
        }
        if (fist) {
            if (fistSince < 0 || Math.hypot(hx - fistX, hy - fistY) > tu.fistMaxTravel) {
                fistSince = t; fistX = hx; fistY = hy;
                setState(State.FIST_HOLD, "fist");
            } else if (t - fistSince >= tu.fistHoldMs) {
                emit(out, Cmd.PLAY_PAUSE);
                fistSince = -1;
                fistLatched = true;
                centres.clear();
                setState(State.FIST_LATCHED, "fist held");
                return out;
            }
        } else if (fistSince >= 0) {
            fistSince = -1;
            setState(State.READY, "fist released early");
        }

        // ---- swipe ----
        for (double[] p : centres) {
            double dx = hx - p[1], dy = hy - p[2];
            if (Math.abs(dx) >= tu.swipeMinDx && Math.abs(dy) < tu.swipeMaxDyRatio * Math.abs(dx)) {
                emit(out, dx > 0 ? Cmd.NEXT : Cmd.PREV);
                cooldownUntil = t + (long) tu.swipeCooldownMs;
                centres.clear();
                tips.clear();
                fistSince = -1;
                setState(State.COOLDOWN, dx > 0 ? "swipe right" : "swipe left");
                return out;
            }
        }
        return out;
    }

    // ---- rotation ------------------------------------------------------------

    /** @return true while rotation is active, so the other recognisers stand down. */
    private boolean stepRotation(long t, List<Cmd> out) {
        int n = tips.size();
        if (n < Math.max(3, (int) tu.rotMinPoints)) {
            lastRadius = 0; lastAspect = 0;
            return maybeReleaseRotation(t);
        }

        double cx = 0, cy = 0;
        for (double[] p : tips) { cx += p[1]; cy += p[2]; }
        cx /= n; cy /= n;
        double r = 0, sxx = 0, syy = 0, sxy = 0;
        for (double[] p : tips) {
            double dx = p[1] - cx, dy = p[2] - cy;
            r += Math.hypot(dx, dy);
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy;
        }
        r /= n; sxx /= n; syy /= n; sxy /= n;
        double half = (sxx + syy) / 2;
        double disc = Math.sqrt(Math.max(0, (sxx - syy) * (sxx - syy) / 4 + sxy * sxy));
        double l1 = half + disc, l2 = half - disc;
        double aspect = l1 > 1e-12 ? Math.sqrt(Math.max(0, l2) / l1) : 0;
        lastRadius = r; lastAspect = aspect;

        boolean shapeOk = r >= tu.rotMinRadius && aspect >= tu.rotMinAspect;
        if (!shapeOk) return maybeReleaseRotation(t);

        if (!rotating) {
            // Engage when the whole buffer has swept a full step's worth of
            // angle; the first step fires at once, like the first detent.
            double swept = 0;
            double[] prev = null;
            for (double[] p : tips) {
                if (prev != null) {
                    double d = delta(prev, p, cx, cy);
                    if (Math.abs(d) <= tu.rotMaxDeltaDeg) swept += d;
                }
                prev = p;
            }
            if (Math.abs(swept) < tu.rotStepDeg) return false;
            rotating = true;
            acc = Math.signum(swept) * tu.rotStepDeg;
            lastRotMs = t;
            fistSince = -1;
            setState(State.ROTATING, swept > 0 ? "circling clockwise" : "circling anticlockwise");
        } else {
            Iterator<double[]> it = tips.descendingIterator();
            double[] newest = it.next();
            double[] before = it.next();
            double d = delta(before, newest, cx, cy);
            if (Math.abs(d) <= tu.rotMaxDeltaDeg && Math.abs(d) >= tu.rotMinDeltaDeg) {
                acc += d;
                lastRotMs = t;
            } else if (maybeReleaseRotation(t) == false) {
                return false;
            }
        }

        double step = tu.rotStepDeg;
        long minGap = (long) (1000.0 / Math.max(0.1, tu.rotMaxStepsPerSec));
        if (Math.abs(acc) >= step) {
            if (t - lastStepMs >= minGap) {
                emit(out, acc > 0 ? Cmd.VOL_UP : Cmd.VOL_DOWN);
                acc -= Math.signum(acc) * step;
                lastStepMs = t;
            } else {
                acc = Math.signum(acc) * step; // hold at the detent until the cap allows
            }
        }
        return true;
    }

    /** Releases rotation after rotReleaseMs without movement.
     *  @return true if rotation is still active. */
    private boolean maybeReleaseRotation(long t) {
        if (!rotating) return false;
        if (t - lastRotMs > tu.rotReleaseMs) {
            rotating = false;
            acc = 0;
            centres.clear();
            setState(State.READY, "rotation released");
            return false;
        }
        return true;
    }

    /** Signed angle in degrees from a to b about (cx, cy), in (-180, 180].
     *  With +y down, positive is clockwise as the driver sees it. */
    private static double delta(double[] a, double[] b, double cx, double cy) {
        double a1 = Math.toDegrees(Math.atan2(a[2] - cy, a[1] - cx));
        double a2 = Math.toDegrees(Math.atan2(b[2] - cy, b[1] - cx));
        double d = a2 - a1;
        while (d > 180) d -= 360;
        while (d <= -180) d += 360;
        return d;
    }

    // ---- hand shape ------------------------------------------------------------

    /** Every fingertip closer to the wrist than its own PIP joint. */
    static boolean isFist(HandFrame f) {
        for (int k = 0; k < 4; k++) {
            if (distToWrist(f, TIP[k]) >= distToWrist(f, PIP[k])) return false;
        }
        return true;
    }

    /** At least three fingers extended (tip farther from the wrist than its PIP). */
    static boolean isOpen(HandFrame f) {
        int ext = 0;
        for (int k = 0; k < 4; k++) {
            if (distToWrist(f, TIP[k]) > distToWrist(f, PIP[k])) ext++;
        }
        return ext >= 3;
    }

    private static double distToWrist(HandFrame f, int i) {
        return Math.hypot(f.x[i] - f.x[WRIST], f.y[i] - f.y[WRIST]);
    }

    // ---- bookkeeping -------------------------------------------------------------

    private static void push(ArrayDeque<double[]> q, long t, double x, double y, double windowMs) {
        q.addLast(new double[]{t, x, y});
        while (!q.isEmpty() && t - q.peekFirst()[0] > windowMs) q.removeFirst();
    }

    private void emit(List<Cmd> out, Cmd c) {
        out.add(c);
        log.log("engine: emit " + c);
    }

    private void setState(State s, String why) {
        if (s == state) return;
        log.log("engine: " + state + " -> " + s + " (" + why + ")");
        state = s;
    }

    private void resetAll() {
        presentSince = -1;
        lastGoodFrame = -1;
        tips.clear();
        centres.clear();
        rotating = false;
        acc = 0;
        fistSince = -1;
        fistLatched = false;
    }
}
