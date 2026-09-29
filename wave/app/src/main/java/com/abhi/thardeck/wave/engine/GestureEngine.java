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
 * Three recognisers, only one acting at a time, tilt first:
 *
 *   tilt    hand up with the fingers out, palm to the tablet, pivoting at the
 *           wrist like a dial. The in-plane angle of the wrist to
 *           middle-knuckle line (landmarks 0 to 9) is unwrapped and its
 *           per-frame change accumulated, one volume step per tiltStepDeg.
 *           Fingers toward the driver's right (clockwise on a y-down screen,
 *           which is clockwise as the driver sees it) is up. Only while the
 *           hand stays in place, so a swipe is never read as a tilt.
 *           tiltInvert flips the direction.
 *   swipe   the hand centre travels sideways, fast, and mostly level.
 *   palm    all four fingers open, held still: play or pause, once, then the
 *           hand has to stop being an open palm (or leave) before it can fire
 *           again.
 *
 * Nothing counts until the hand is close to the camera (bounding box height)
 * and has been there for a moment, so a hand resting on the wheel is ignored.
 * The size gate has hysteresis (arm high, hold lower) and short tracking
 * dropouts are forgiven, because a real hand's box wobbles as it moves.
 *
 * Not thread safe: call it from one thread.
 */
public final class GestureEngine {

    public enum State { IDLE, ARMING, READY, TILTING, PALM_HOLD, PALM_LATCHED, COOLDOWN }

    /** Receives one line per state transition and per emitted command. */
    public interface Log { void log(String line); }

    private static final int WRIST = 0, MIDDLE_MCP = 9;
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
    /** Size gate with hysteresis: set at minBoxHeight, held down to
     *  minBoxHeightHold. */
    private boolean sizeLatched = false;
    /** Last frame with any hand at all, of any size. */
    private long lastPresentMs = -1;

    // tilt: {t, unwrapped tilt angle, centre x, centre y}
    private final ArrayDeque<double[]> tiltBuf = new ArrayDeque<>();
    private boolean haveTilt = false;
    private double prevRaw, tilt;
    private boolean tilting = false;
    private double acc = 0;
    private long lastTiltMs = Long.MIN_VALUE / 4;
    private long lastStepMs = Long.MIN_VALUE / 4;

    // swipe: {t, x, y}
    private final ArrayDeque<double[]> centres = new ArrayDeque<>();
    private long cooldownUntil = Long.MIN_VALUE / 4;

    // palm
    private long palmSince = -1;
    private double palmX, palmY, palmTilt;
    private boolean palmLatched = false;
    private long notPalmSince = -1;

    // live values for the calibrate screen
    private double lastBoxH, lastTravel;
    private int lastExtended;

    public GestureEngine(Tuning tuning) { this.tu = tuning; }

    public void setLog(Log l) {
        this.log = l != null ? l : new Log() { @Override public void log(String line) {} };
    }

    public State state() { return state; }
    /** True while tilting is active; the HUD shows its ring meanwhile. */
    public boolean tilting() { return tilting; }
    /** Progress toward the next volume step, -1..1, for the HUD ring. */
    public double tiltProgress() {
        double step = tu.tiltStepDeg;
        return step <= 0 ? 0 : Math.max(-1, Math.min(1, acc / step));
    }

    /** One line of live engine state for the calibrate screen. */
    public String debugLine() {
        return String.format(java.util.Locale.ROOT,
                "%s box=%.2f%s fingers=%d%s tilt=%.0f acc=%.0f travel=%.3f",
                state, lastBoxH, sizeLatched ? "(armed)" : "", lastExtended,
                palmLatched ? " palm(latched)" : "", tilt, acc, lastTravel);
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

        // Size gate with hysteresis. Arm at minBoxHeight, then stay armed down
        // to minBoxHeightHold. A frame with no hand leaves it alone, so a short
        // tracking dropout does not lose it.
        double hold = Math.min(tu.minBoxHeightHold, tu.minBoxHeight);
        if (f.present) {
            if (h >= tu.minBoxHeight) sizeLatched = true;
            else if (h < hold) sizeLatched = false;
        }
        boolean big = f.present && sizeLatched;

        if (!big) {
            lastExtended = 0;
            if (lastGoodFrame >= 0 && t - lastGoodFrame <= tu.lostGraceMs) {
                // A short tracking dropout: hold state, just let tilt time out.
                maybeReleaseTilt(t);
                return out;
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

        // In-plane tilt of the wrist to middle-knuckle line, unwrapped.
        double raw = Math.toDegrees(Math.atan2(f.y[MIDDLE_MCP] - f.y[WRIST], f.x[MIDDLE_MCP] - f.x[WRIST]));
        double d = 0;
        if (!haveTilt) {
            haveTilt = true;
            tilt = raw;
        } else {
            d = wrap(raw - prevRaw);
            if (tu.tiltInvert) d = -d;
            tilt += d;
        }
        prevRaw = raw;
        tiltBuf.addLast(new double[]{t, tilt, hx, hy});
        while (!tiltBuf.isEmpty() && t - tiltBuf.peekFirst()[0] > tu.tiltWindowMs) tiltBuf.removeFirst();

        // Cooldown after a swipe: nothing fires, and the swipe buffer stays
        // empty so the tail of the same swipe cannot fire again.
        if (t < cooldownUntil) {
            centres.clear();
            return out;
        }
        if (state == State.COOLDOWN) setState(State.READY, "cooldown over");

        centres.addLast(new double[]{t, hx, hy});
        while (!centres.isEmpty() && t - centres.peekFirst()[0] > tu.swipeWindowMs) centres.removeFirst();

        // ---- tilt, which has priority while active ----
        if (stepTilt(t, d, hx, hy, out)) {
            palmSince = -1;
            centres.clear();
            return out;
        }

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
                boolean moved = palmSince >= 0 && (Math.hypot(hx - palmX, hy - palmY) > tu.palmMaxTravel
                        || Math.abs(tilt - palmTilt) > tu.palmMaxTiltDeg);
                if (palmSince < 0 || moved) {
                    // A hand that arrived as a still palm has been holding
                    // since it arrived; arming time counts toward the hold.
                    palmSince = (justArmed && palmSince < 0) ? presentSince : t;
                    palmX = hx; palmY = hy; palmTilt = tilt;
                    setState(State.PALM_HOLD, moved ? "palm moved, restart hold" : "open palm");
                } else if (t - palmSince >= tu.palmHoldMs) {
                    emit(out, Cmd.PLAY_PAUSE);
                    palmSince = -1;
                    palmLatched = true;
                    notPalmSince = -1;
                    centres.clear();
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
                tiltBuf.clear();
                acc = 0;
                palmSince = -1;
                setState(State.COOLDOWN, dx > 0 ? "swipe right" : "swipe left");
                return out;
            }
        }
        return out;
    }

    // ---- tilt ------------------------------------------------------------------

    /**
     * Accumulates this frame's tilt change when the hand is staying in place
     * and the change is inside the jitter floor and jump ceiling. No finger
     * count is required. Emits a step per tiltStepDeg.
     *
     * @return true while tilting is active, so the other recognisers stand down.
     */
    private boolean stepTilt(long t, double d, double hx, double hy, List<Cmd> out) {
        double travel = 0;
        for (double[] p : tiltBuf) travel = Math.max(travel, Math.hypot(hx - p[2], hy - p[3]));
        lastTravel = travel;

        boolean qualifies = travel < tu.tiltMaxTravel
                && Math.abs(d) >= tu.tiltMinDeltaDeg && Math.abs(d) <= tu.tiltMaxDeltaDeg;
        if (qualifies) {
            acc += d; // signed, so tilting back unwinds it like a dial
            lastTiltMs = t;
        } else if (!maybeReleaseTilt(t)) {
            if (t - lastTiltMs > tu.tiltReleaseMs) acc = 0;
            return false;
        }

        double step = tu.tiltStepDeg;
        long minGap = (long) (1000.0 / Math.max(0.1, tu.tiltMaxStepsPerSec));
        if (Math.abs(acc) >= step) {
            if (!tilting) {
                tilting = true;
                palmSince = -1;
                setState(State.TILTING, acc > 0 ? "tilting clockwise" : "tilting anticlockwise");
            }
            if (t - lastStepMs >= minGap) {
                emit(out, acc > 0 ? Cmd.VOL_UP : Cmd.VOL_DOWN);
                acc -= Math.signum(acc) * step;
                lastStepMs = t;
            } else {
                acc = Math.signum(acc) * step; // hold at the detent until the cap allows
            }
        }
        return tilting;
    }

    /** Releases tilting after tiltReleaseMs without movement.
     *  @return true if tilting is still active. */
    private boolean maybeReleaseTilt(long t) {
        if (!tilting) return false;
        if (t - lastTiltMs > tu.tiltReleaseMs) {
            tilting = false;
            acc = 0;
            centres.clear();
            setState(State.READY, "tilt released");
            return false;
        }
        return true;
    }

    private static double wrap(double d) {
        while (d > 180) d -= 360;
        while (d <= -180) d += 360;
        return d;
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

    /** Drops the hand: arming, buffers, tilt and a palm hold in progress. A
     *  fired palm's latch is deliberately kept; it clears only when the hand
     *  stops being an open palm on armed frames or has been gone for
     *  palmUnlatchAbsentMs, so a size dropout under a still palm cannot fire
     *  it twice. */
    private void resetAll() {
        presentSince = -1;
        lastGoodFrame = -1;
        sizeLatched = false;
        tiltBuf.clear();
        centres.clear();
        haveTilt = false;
        tilting = false;
        acc = 0;
        palmSince = -1;
        notPalmSince = -1;
    }
}
