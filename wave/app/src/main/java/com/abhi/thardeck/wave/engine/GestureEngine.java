package com.abhi.thardeck.wave.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The gesture state machine. Pure Java, no Android, so it is unit tested with
 * synthetic landmark sequences.
 *
 * Feed it one {@link HandFrame} per analysed camera frame, in time order, with
 * coordinates already in the driver's frame (+x to the driver's right, +y
 * down). It returns zero or more commands for that frame.
 *
 * Three recognisers, each checked in this order on every frame, at most one
 * firing per frame:
 *
 *   stroke  the open hand, fingers up, palm to the tablet, raised or lowered
 *           in a flick: the hand centre moves mostly vertically by at least
 *           strokeMinTravel within strokeWindowMs. Up is VOL_UP, down is
 *           VOL_DOWN, one step per flick, then a short refractory.
 *   swipe   the hand centre moves mostly horizontally, far and fast.
 *   palm    all four fingers open, held still: play or pause, once, then the
 *           hand has to stop being an open palm (or leave) before it can fire
 *           again. A palm that moves is a stroke or a swipe, never a hold.
 *
 * Nothing counts until the hand is close to the camera (bounding box height at
 * least minBoxHeight) and has been there for a moment, so a hand resting on
 * the wheel is ignored. Once armed, the hand stays armed while its box is at
 * least strokeMinBox, because at the bottom of a down stroke it shrinks and
 * half leaves the frame; swipes and the palm hold still want the box above
 * minBoxHeightHold. Short tracking dropouts are forgiven, but they clear the
 * stroke buffer, so a hand that drops out of view and comes back lower is not
 * read as a down stroke.
 *
 * Not thread safe: call it from one thread.
 */
public final class GestureEngine {

    public enum State { IDLE, ARMING, READY, PALM_HOLD, PALM_LATCHED, COOLDOWN }

    /** Receives one line per state transition and per emitted command. */
    public interface Log { void log(String line); }

    private static final int WRIST = 0;
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
    /** Size gate with hysteresis: set at minBoxHeight, held down to the
     *  stroke floor. */
    private boolean sizeLatched = false;
    /** Last frame with any hand at all, of any size. */
    private long lastPresentMs = -1;

    // stroke: {t, x, y}
    private final ArrayDeque<double[]> strokeBuf = new ArrayDeque<>();
    private long refractoryUntil = Long.MIN_VALUE / 4;

    // swipe: {t, x, y}
    private final ArrayDeque<double[]> centres = new ArrayDeque<>();
    private long cooldownUntil = Long.MIN_VALUE / 4;

    // palm
    private long palmSince = -1;
    private double palmX, palmY;
    private boolean palmLatched = false;
    private long notPalmSince = -1;

    // live values for the calibrate screen
    private double lastBoxH, lastDy, lastDx;
    private int lastExtended;

    public GestureEngine(Tuning tuning) { this.tu = tuning; }

    public void setLog(Log l) {
        this.log = l != null ? l : new Log() { @Override public void log(String line) {} };
    }

    public State state() { return state; }

    /** One line of live engine state for the calibrate screen. */
    public String debugLine() {
        return String.format(java.util.Locale.ROOT,
                "%s box=%.2f%s fingers=%d%s stroke dy=%+.2f dx=%.2f",
                state, lastBoxH, sizeLatched ? "(armed)" : "", lastExtended,
                palmLatched ? " palm(latched)" : "", lastDy, lastDx);
    }

    /** Forget everything, as if no hand had ever been seen, including a fired
     *  palm. For a pipeline restart, not for tracking dropouts. */
    public void reset() {
        resetAll();
        palmLatched = false;
        lastPresentMs = -1;
        setState(State.IDLE, "reset");
    }

    // ---- per frame -----------------------------------------------------------

    public List<Cmd> onFrame(HandFrame f) {
        List<Cmd> out = new ArrayList<>(1);
        long t = f.t;
        double h = f.boxHeight();
        lastBoxH = h;

        // A fired palm re-arms when the hand has really gone: no hand of any
        // size for palmUnlatchAbsentMs. Measured as a gap, so it also works
        // when the camera side sent no frames at all while the hand was away.
        if (palmLatched && lastPresentMs >= 0 && t - lastPresentMs > tu.palmUnlatchAbsentMs) {
            palmLatched = false;
            log.log("engine: palm latch cleared (hand gone)");
        }
        if (f.present) lastPresentMs = t;

        // Size gate with hysteresis. Arm at minBoxHeight, then keep tracking
        // down to the stroke floor. A frame with no hand leaves it alone, so a
        // short tracking dropout does not lose it.
        double hold = Math.min(tu.minBoxHeightHold, tu.minBoxHeight);
        double floor = Math.min(tu.strokeMinBox, hold);
        if (f.present) {
            if (h >= tu.minBoxHeight) sizeLatched = true;
            else if (h < floor) sizeLatched = false;
        }
        boolean tracked = f.present && sizeLatched;

        if (!tracked) {
            lastExtended = 0;
            // Lost or below the floor: the stroke buffer goes, so the hand
            // coming back lower is not read as a down stroke.
            strokeBuf.clear();
            if (lastGoodFrame >= 0 && t - lastGoodFrame <= tu.lostGraceMs) {
                return out; // a short dropout: keep arming and the palm hold
            }
            if (state != State.IDLE) {
                resetAll();
                setState(State.IDLE, f.present ? "hand too small" : "no hand");
            }
            return out;
        }

        lastGoodFrame = t;
        boolean justArmed = false;
        if (presentSince < 0) presentSince = t;
        if (t - presentSince < tu.armMs) {
            setState(State.ARMING, "hand close");
            return out;
        }
        if (state == State.IDLE || state == State.ARMING) {
            setState(State.READY, "armed");
            justArmed = true;
        }

        double hx = 0, hy = 0;
        for (int i : CENTRE_IDX) { hx += f.x[i]; hy += f.y[i]; }
        hx /= CENTRE_IDX.length; hy /= CENTRE_IDX.length;
        int ext = extended(f);
        lastExtended = ext;
        boolean palm = ext == 4;
        boolean full = h >= hold; // swipes and the palm hold want a clear view

        // Cooldown after a swipe: nothing fires, and both buffers stay empty
        // so the tail of the same swipe cannot fire anything.
        if (t < cooldownUntil) {
            centres.clear();
            strokeBuf.clear();
            return out;
        }
        if (state == State.COOLDOWN) setState(State.READY, "cooldown over");

        // ---- stroke, checked first so a vertical move is never a swipe ----
        strokeBuf.addLast(new double[]{t, hx, hy});
        while (!strokeBuf.isEmpty() && t - strokeBuf.peekFirst()[0] > tu.strokeWindowMs) {
            strokeBuf.removeFirst();
        }
        double bestDy = 0, bestDx = 0;
        for (double[] p : strokeBuf) {
            double dy = hy - p[2];
            if (Math.abs(dy) > Math.abs(bestDy)) { bestDy = dy; bestDx = Math.abs(hx - p[1]); }
        }
        lastDy = bestDy;
        lastDx = bestDx;
        if (t >= refractoryUntil) {
            for (double[] p : strokeBuf) {
                double dx = hx - p[1], dy = hy - p[2];
                if (Math.abs(dy) >= tu.strokeMinTravel
                        && Math.abs(dy) > tu.strokeVerticalRatio * Math.abs(dx)) {
                    boolean up = (dy < 0) != tu.strokeInvert; // y is down on screen
                    emit(out, up ? Cmd.VOL_UP : Cmd.VOL_DOWN);
                    strokeBuf.clear();
                    centres.clear();
                    refractoryUntil = t + (long) tu.strokeRefractoryMs;
                    palmSince = -1;
                    if (state != State.PALM_LATCHED) setState(State.READY, up ? "stroke up" : "stroke down");
                    return out;
                }
            }
        }

        if (!full) {
            // Between the stroke floor and the hold height: strokes only.
            palmSince = -1;
            return out;
        }

        centres.addLast(new double[]{t, hx, hy});
        while (!centres.isEmpty() && t - centres.peekFirst()[0] > tu.swipeWindowMs) centres.removeFirst();

        // ---- open palm hold ----
        if (palmLatched) {
            if (palm) {
                notPalmSince = -1;
            } else {
                if (notPalmSince < 0) notPalmSince = t;
                if (t - notPalmSince >= tu.palmReleaseMs) {
                    palmLatched = false;
                    notPalmSince = -1;
                    setState(State.READY, "palm closed");
                }
            }
            if (palmLatched) setState(State.PALM_LATCHED, palm ? "palm still open" : "palm closing");
        }
        if (!palmLatched) {
            if (palm) {
                boolean moved = palmSince >= 0 && Math.hypot(hx - palmX, hy - palmY) > tu.palmMaxTravel;
                if (palmSince < 0 || moved) {
                    // A hand that arrived as a still palm has been holding
                    // since it arrived; arming time counts toward the hold.
                    palmSince = (justArmed && palmSince < 0) ? presentSince : t;
                    palmX = hx; palmY = hy;
                    setState(State.PALM_HOLD, moved ? "palm moved, restart hold" : "open palm");
                } else if (t - palmSince >= tu.palmHoldMs) {
                    emit(out, Cmd.PLAY_PAUSE);
                    palmSince = -1;
                    palmLatched = true;
                    notPalmSince = -1;
                    centres.clear();
                    strokeBuf.clear();
                    setState(State.PALM_LATCHED, "palm held");
                    return out;
                }
            } else if (palmSince >= 0) {
                palmSince = -1;
                setState(State.READY, "palm released early");
            }
        }

        // ---- swipe ----
        for (double[] p : centres) {
            double dx = hx - p[1], dy = hy - p[2];
            if (Math.abs(dx) >= tu.swipeMinDx && Math.abs(dy) < tu.swipeMaxDyRatio * Math.abs(dx)) {
                emit(out, dx > 0 ? Cmd.NEXT : Cmd.PREV);
                cooldownUntil = t + (long) tu.swipeCooldownMs;
                centres.clear();
                strokeBuf.clear();
                palmSince = -1;
                setState(State.COOLDOWN, dx > 0 ? "swipe right" : "swipe left");
                return out;
            }
        }
        return out;
    }

    // ---- hand shape ------------------------------------------------------------

    /** Fingers (index, middle, ring, pinky) whose tip is farther from the
     *  wrist than their own PIP joint. Four is an open palm, none a fist. */
    static int extended(HandFrame f) {
        int ext = 0;
        for (int k = 0; k < 4; k++) {
            if (distToWrist(f, TIP[k]) > distToWrist(f, PIP[k])) ext++;
        }
        return ext;
    }

    private static double distToWrist(HandFrame f, int i) {
        return Math.hypot(f.x[i] - f.x[WRIST], f.y[i] - f.y[WRIST]);
    }

    // ---- bookkeeping -------------------------------------------------------------

    private void emit(List<Cmd> out, Cmd c) {
        out.add(c);
        log.log("engine: emit " + c);
    }

    private void setState(State s, String why) {
        if (s == state) return;
        log.log("engine: " + state + " -> " + s + " (" + why + ")");
        state = s;
    }

    /** Drops the hand: arming, buffers and a palm hold in progress. A fired
     *  palm's latch is deliberately kept; it clears only when the hand stops
     *  being an open palm on armed frames or has been gone for
     *  palmUnlatchAbsentMs, so a size dropout under a still palm cannot fire
     *  it twice. */
    private void resetAll() {
        presentSince = -1;
        lastGoodFrame = -1;
        sizeLatched = false;
        strokeBuf.clear();
        centres.clear();
        palmSince = -1;
        notPalmSince = -1;
    }
}
