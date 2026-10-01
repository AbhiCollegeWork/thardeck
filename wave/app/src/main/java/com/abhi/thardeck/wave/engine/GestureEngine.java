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
 * Deliberate entry. The engine acts only in a short listening window after a
 * hand ENTERS the view, meaning it appears after being out of view for at
 * least entryAbsentMs. The window lasts entryWindowMs and is extended to at
 * least entryExtendMs after each command, so a second stroke or a swipe can
 * follow. When it closes with the hand still there the engine goes DORMANT:
 * nothing fires and nothing accumulates until the hand has left again. A hand
 * resting in view while driving, or moving about doing other things, gets one
 * window at most and then is ignored.
 *
 * Three recognisers, checked in this order on every frame, at most one firing
 * per frame:
 *
 *   stroke  the open hand, fingers up, palm to the tablet, raised or lowered
 *           in a flick: the hand centre moves mostly vertically by at least
 *           strokeMinTravel within strokeWindowMs. Up is VOL_UP, down is
 *           VOL_DOWN, one step per flick, then a short refractory. The stroke
 *           buffer is fed from the first frame the hand is seen, so a quick
 *           flick is not lost to the arming delay, and the entry itself may be
 *           the stroke. Not while the hand is so big it is reaching for the
 *           screen.
 *   swipe   the hand centre moves mostly horizontally, far and fast, measured
 *           from points where the hand had been in view a moment. It fires one
 *           frame late, and only if the hand is still clearly in view then, so
 *           a hand dropping out of view does not swipe.
 *   palm    all four fingers open, close to the tablet, flat to the camera,
 *           held still for palmHoldMs: play or pause, once, then the hand has
 *           to stop being an open palm (or leave) before it can fire again. A
 *           palm that moves is a stroke or a swipe, never a hold.
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

    public enum State { IDLE, ARMING, READY, PALM_HOLD, PALM_LATCHED, COOLDOWN, DORMANT }

    /** Receives one line per state transition and per emitted command. */
    public interface Log { void log(String line); }

    private static final int WRIST = 0, INDEX_MCP = 5, MIDDLE_MCP = 9, PINKY_MCP = 17;
    private static final int[] CENTRE_IDX = {0, 5, 9, 13, 17};
    /** PIP joint and tip for index, middle, ring and pinky. */
    private static final int[] PIP = {6, 10, 14, 18};
    private static final int[] TIP = {8, 12, 16, 20};

    private final Tuning tu;
    private Log log = new Log() { @Override public void log(String line) {} };

    private State state = State.IDLE;

    // deliberate entry
    /** Last frame with a hand in view (box at least the stroke floor). */
    private long lastSeenMs = -1;
    private long windowUntil = Long.MIN_VALUE / 4;
    private boolean windowClosedLogged = true;

    // arming
    private long presentSince = -1;
    private long lastGoodFrame = -1;
    /** Size gate with hysteresis: set at minBoxHeight, held down to the
     *  stroke floor. */
    private boolean sizeLatched = false;
    /** Last frame whose box reached minBoxHeight. */
    private long lastBigMs = -1;
    /** Last frame with any hand at all, of any size. */
    private long lastPresentMs = -1;

    // stroke: {t, x, y}
    private final ArrayDeque<double[]> strokeBuf = new ArrayDeque<>();
    private long refractoryUntil = Long.MIN_VALUE / 4;

    // swipe: {t, x, y}, and a swipe waiting for its confirming frame
    private final ArrayDeque<double[]> centres = new ArrayDeque<>();
    private long fullSince = -1;
    private Cmd pendingSwipe = null;
    private long cooldownUntil = Long.MIN_VALUE / 4;

    // palm
    private long palmSince = -1;
    private double palmX, palmY;
    private boolean palmLatched = false;
    private long notPalmSince = -1;
    private double palmProgress = 0;

    // live values for the calibrate screen
    private double lastBoxH, lastDy, lastDx, lastWidth;
    private int lastExtended;

    public GestureEngine(Tuning tuning) { this.tu = tuning; }

    public void setLog(Log l) {
        this.log = l != null ? l : new Log() { @Override public void log(String line) {} };
    }

    public State state() { return state; }

    /** End of the listening window, in the frames' clock, or a time already
     *  past when not listening. For the HUD's listening dot. */
    public long listeningUntil() { return windowUntil; }

    /** How far a palm hold has got, 0..1, for the HUD ring; 0 when none. */
    public double palmProgress() { return palmProgress; }

    /** One line of live engine state for the calibrate screen. */
    public String debugLine() {
        return String.format(java.util.Locale.ROOT,
                "%s box=%.2f%s fingers=%d width=%.2f%s stroke dy=%+.2f dx=%.2f",
                state, lastBoxH, sizeLatched ? "(armed)" : "", lastExtended, lastWidth,
                palmLatched ? " palm(latched)" : "", lastDy, lastDx);
    }

    /** Forget everything, as if no hand had ever been seen, including a fired
     *  palm and the listening window. For a pipeline restart, not for
     *  tracking dropouts. */
    public void reset() {
        resetAll();
        palmLatched = false;
        lastPresentMs = -1;
        lastBigMs = -1;
        lastSeenMs = -1;
        windowUntil = Long.MIN_VALUE / 4;
        windowClosedLogged = true;
        setState(State.IDLE, "reset");
    }

    // ---- per frame -----------------------------------------------------------

    public List<Cmd> onFrame(HandFrame f) {
        List<Cmd> out = new ArrayList<>(1);
        long t = f.t;
        double h = f.boxHeight();
        lastBoxH = h;
        palmProgress = 0;

        double hold = Math.min(tu.minBoxHeightHold, tu.minBoxHeight);
        double floor = Math.min(tu.strokeMinBox, hold);
        boolean seen = f.present && h >= floor;

        // ---- deliberate entry ----
        if (seen) {
            if (lastSeenMs < 0 || t - lastSeenMs >= tu.entryAbsentMs) {
                if (state != State.IDLE) {
                    resetAll();
                    setState(State.IDLE, "hand came back");
                }
                windowUntil = t + (long) tu.entryWindowMs;
                windowClosedLogged = false;
                log.log("engine: entry, listening for " + (long) tu.entryWindowMs + " ms");
            }
            lastSeenMs = t;
        }
        boolean listening = t <= windowUntil;
        if (!listening && !windowClosedLogged) {
            windowClosedLogged = true;
            log.log("engine: listening window closed" + (seen ? ", dormant until the hand leaves" : ""));
        }

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
        if (f.present) {
            if (h >= tu.minBoxHeight) { sizeLatched = true; lastBigMs = t; }
            else if (h < floor) sizeLatched = false;
        }
        boolean tracked = f.present && sizeLatched;
        if (f.present && h >= hold) { if (fullSince < 0) fullSince = t; } else fullSince = -1;

        double hx = 0, hy = 0;
        if (f.present) {
            for (int i : CENTRE_IDX) { hx += f.x[i]; hy += f.y[i]; }
            hx /= CENTRE_IDX.length; hy /= CENTRE_IDX.length;
        }

        // Stroke buffer, fed from the first frame the hand is seen, before the
        // arming delay: armed for strokes once the box has reached
        // minBoxHeight within the stroke window, and down to the floor. Not
        // while dormant: nothing accumulates then.
        boolean strokeArmed = listening && seen
                && (sizeLatched || (lastBigMs >= 0 && t - lastBigMs <= tu.strokeWindowMs));
        if (strokeArmed) {
            strokeBuf.addLast(new double[]{t, hx, hy});
            while (!strokeBuf.isEmpty() && t - strokeBuf.peekFirst()[0] > tu.strokeWindowMs) {
                strokeBuf.removeFirst();
            }
        } else {
            // Lost, below the floor, or dormant: the stroke buffer goes, so
            // the hand coming back lower is not read as a down stroke.
            strokeBuf.clear();
        }

        if (!tracked) {
            lastExtended = 0;
            dropSwipe();
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
        if (!listening) {
            // Dormant: the hand is here but did not just arrive.
            dropSwipe();
            palmSince = -1;
            setState(State.DORMANT, "listening window closed");
            return out;
        }

        boolean justArmed = false;
        if (presentSince < 0) presentSince = t;
        if (t - presentSince < tu.armMs) {
            setState(State.ARMING, "hand close");
            return out;
        }
        if (state == State.IDLE || state == State.ARMING || state == State.DORMANT) {
            setState(State.READY, "armed");
            justArmed = true;
        }

        int ext = extended(f);
        lastExtended = ext;
        boolean open = ext == 4;
        lastWidth = palmWidth(f);
        // A palm that counts for play or pause: open, close, flat to the camera.
        boolean palm = open && h >= tu.palmMinBox && lastWidth >= tu.palmMinWidth;
        boolean full = h >= hold; // swipes and the palm hold want a clear view

        // Cooldown after a swipe: nothing fires, and the buffers stay empty
        // so the tail of the same swipe cannot fire anything.
        if (t < cooldownUntil) {
            strokeBuf.clear();
            dropSwipe();
            return out;
        }
        if (state == State.COOLDOWN) setState(State.READY, "cooldown over");

        // ---- stroke, checked first so a vertical move is never a swipe ----
        double bestDy = 0, bestDx = 0;
        for (double[] p : strokeBuf) {
            double dy = hy - p[2];
            if (Math.abs(dy) > Math.abs(bestDy)) { bestDy = dy; bestDx = Math.abs(hx - p[1]); }
        }
        lastDy = bestDy;
        lastDx = bestDx;
        if (t >= refractoryUntil && h <= tu.strokeMaxBox) {
            for (double[] p : strokeBuf) {
                double dx = hx - p[1], dy = hy - p[2];
                if (Math.abs(dy) >= tu.strokeMinTravel
                        && Math.abs(dy) > tu.strokeVerticalRatio * Math.abs(dx)) {
                    boolean up = (dy < 0) != tu.strokeInvert; // y is down on screen
                    fire(out, up ? Cmd.VOL_UP : Cmd.VOL_DOWN, t);
                    strokeBuf.clear();
                    dropSwipe();
                    refractoryUntil = t + (long) tu.strokeRefractoryMs;
                    palmSince = -1;
                    if (state != State.PALM_LATCHED) setState(State.READY, up ? "stroke up" : "stroke down");
                    return out;
                }
            }
        }

        if (!full) {
            // Between the stroke floor and the hold height: strokes only, and
            // a swipe in waiting is dropped because the hand is leaving view.
            palmSince = -1;
            dropSwipe();
            return out;
        }

        // ---- swipe confirmation: the frame after the threshold was met ----
        if (pendingSwipe != null) {
            Cmd c = pendingSwipe;
            pendingSwipe = null;
            fire(out, c, t);
            cooldownUntil = t + (long) tu.swipeCooldownMs;
            strokeBuf.clear();
            centres.clear();
            palmSince = -1;
            setState(State.COOLDOWN, c == Cmd.NEXT ? "swipe right" : "swipe left");
            return out;
        }

        // Swipe points only once the hand has been in view a moment.
        if (t - fullSince >= tu.swipeMinPresentMs) {
            centres.addLast(new double[]{t, hx, hy});
        }
        while (!centres.isEmpty() && t - centres.peekFirst()[0] > tu.swipeWindowMs) centres.removeFirst();

        // ---- open palm hold ----
        if (palmLatched) {
            // The latch follows the hand's shape only, not its size or angle,
            // so a wobble in either cannot re-arm it under a still-open hand.
            if (open) {
                notPalmSince = -1;
            } else {
                if (notPalmSince < 0) notPalmSince = t;
                if (t - notPalmSince >= tu.palmReleaseMs) {
                    palmLatched = false;
                    notPalmSince = -1;
                    setState(State.READY, "palm closed");
                }
            }
            if (palmLatched) setState(State.PALM_LATCHED, open ? "palm still open" : "palm closing");
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
                    fire(out, Cmd.PLAY_PAUSE, t);
                    palmSince = -1;
                    palmLatched = true;
                    notPalmSince = -1;
                    strokeBuf.clear();
                    centres.clear();
                    setState(State.PALM_LATCHED, "palm held");
                    return out;
                } else {
                    palmProgress = Math.min(1, (t - palmSince) / Math.max(1.0, tu.palmHoldMs));
                }
            } else if (palmSince >= 0) {
                palmSince = -1;
                setState(State.READY, "palm released early");
            }
        }

        // ---- swipe: threshold now, confirmed next frame ----
        for (double[] p : centres) {
            double dx = hx - p[1], dy = hy - p[2];
            if (Math.abs(dx) >= tu.swipeMinDx && Math.abs(dy) < tu.swipeMaxDyRatio * Math.abs(dx)) {
                pendingSwipe = dx > 0 ? Cmd.NEXT : Cmd.PREV;
                log.log("engine: swipe " + (dx > 0 ? "right" : "left") + " reached, confirming next frame");
                break;
            }
        }
        return out;
    }

    /** Emits a command and keeps the listening window open long enough for a
     *  follow-up. */
    private void fire(List<Cmd> out, Cmd c, long t) {
        out.add(c);
        log.log("engine: emit " + c);
        long until = t + (long) tu.entryExtendMs;
        if (until > windowUntil) windowUntil = until;
    }

    /** Forgets the swipe buffer and any swipe waiting for confirmation. */
    private void dropSwipe() {
        if (pendingSwipe != null) log.log("engine: swipe dropped, hand left view");
        pendingSwipe = null;
        centres.clear();
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

    /**
     * How square-on the palm is to the camera: the knuckle line (5 to 17)
     * measured across the hand axis (wrist 0 to middle knuckle 9), over the
     * length of that axis. About 0.6 or more for a flat palm facing the
     * tablet; an edge-on or tilted-back hand reads much narrower.
     */
    static double palmWidth(HandFrame f) {
        double ax = f.x[MIDDLE_MCP] - f.x[WRIST], ay = f.y[MIDDLE_MCP] - f.y[WRIST];
        double len = Math.hypot(ax, ay);
        if (len < 1e-9) return 0;
        double kx = f.x[PINKY_MCP] - f.x[INDEX_MCP], ky = f.y[PINKY_MCP] - f.y[INDEX_MCP];
        double across = Math.abs(kx * ay - ky * ax) / len;
        return across / len;
    }

    private static double distToWrist(HandFrame f, int i) {
        return Math.hypot(f.x[i] - f.x[WRIST], f.y[i] - f.y[WRIST]);
    }

    // ---- bookkeeping -------------------------------------------------------------

    private void setState(State s, String why) {
        if (s == state) return;
        log.log("engine: " + state + " -> " + s + " (" + why + ")");
        state = s;
    }

    /** Drops the hand: arming, buffers, a waiting swipe and a palm hold in
     *  progress. A fired palm's latch is deliberately kept; it clears only
     *  when the hand stops being an open palm on armed frames or has been gone
     *  for palmUnlatchAbsentMs, so a size dropout under a still palm cannot
     *  fire it twice. */
    private void resetAll() {
        presentSince = -1;
        lastGoodFrame = -1;
        sizeLatched = false;
        strokeBuf.clear();
        dropSwipe();
        fullSince = -1;
        palmSince = -1;
        notPalmSince = -1;
    }
}
