package com.abhi.thardeck.wave.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToDoubleFunction;

import org.junit.Before;
import org.junit.Test;

/**
 * Synthetic landmark sequences through the engine. Coordinates are in the
 * driver's frame: +x is the driver's right, +y is down. Frames arrive every
 * 66 ms, which is the 15 fps ceiling the app analyses at with a hand present.
 * A tilt is the whole hand turned in the image plane about its centre;
 * positive degrees are clockwise on a y-down screen.
 */
public class GestureEngineTest {

    static final long DT = 66;

    Tuning tuning;
    GestureEngine engine;
    List<Cmd> emitted;
    long t;

    @Before public void setUp() {
        tuning = new Tuning();
        engine = new GestureEngine(tuning);
        engine.setLog(new GestureEngine.Log() {
            @Override public void log(String line) { System.out.println(t + "ms " + line); }
        });
        emitted = new ArrayList<>();
        t = 1000;
    }

    // ---- synthetic hands ---------------------------------------------------------

    /** Open hand in its own frame, fingers up, wrist at the bottom, total
     *  height 1 from wrist to middle fingertip. */
    static final double[][] OPEN = {
        {0.00, 0.50},                                                   // 0 wrist
        {-0.15, 0.40}, {-0.25, 0.30}, {-0.32, 0.20}, {-0.38, 0.10},     // thumb
        {-0.12, 0.05}, {-0.13, -0.15}, {-0.14, -0.28}, {-0.15, -0.40},  // index
        {-0.02, 0.05}, {-0.02, -0.17}, {-0.02, -0.33}, {-0.02, -0.50},  // middle
        {0.08, 0.05}, {0.09, -0.14}, {0.10, -0.27}, {0.11, -0.38},      // ring
        {0.17, 0.08}, {0.19, -0.08}, {0.20, -0.17}, {0.21, -0.26},      // pinky
    };

    /** Closed fist: every fingertip curled back below its PIP joint. */
    static final double[][] FIST = {
        {0.00, 0.50},
        {-0.15, 0.40}, {-0.22, 0.30}, {-0.20, 0.20}, {-0.12, 0.15},
        {-0.12, 0.05}, {-0.13, -0.12}, {-0.13, 0.00}, {-0.12, 0.08},
        {-0.02, 0.05}, {-0.02, -0.13}, {-0.02, 0.00}, {-0.02, 0.08},
        {0.08, 0.05}, {0.09, -0.11}, {0.09, 0.00}, {0.08, 0.08},
        {0.17, 0.08}, {0.18, -0.06}, {0.18, 0.03}, {0.17, 0.10},
    };

    /** Index and middle out, ring and pinky curled. */
    static double[][] twoFingerShape() {
        double[][] p = new double[21][];
        for (int i = 0; i < 21; i++) p[i] = (i >= 13 ? FIST[i] : OPEN[i]).clone();
        return p;
    }

    static float[][] openHand(double cx, double cy, double s) { return hand(OPEN, cx, cy, s, 0); }
    static float[][] openHand(double cx, double cy, double s, double deg) { return hand(OPEN, cx, cy, s, deg); }
    static float[][] fist(double cx, double cy, double s) { return hand(FIST, cx, cy, s, 0); }

    /**
     * Places a shape scaled by s, tilted by deg (positive is clockwise on a
     * y-down screen) about the hand centre, with the hand centre (mean of
     * landmarks 0, 5, 9, 13, 17) exactly at (cx, cy).
     */
    static float[][] hand(double[][] p, double cx, double cy, double s, double deg) {
        int[] c = {0, 5, 9, 13, 17};
        double mx = 0, my = 0;
        for (int i : c) { mx += p[i][0]; my += p[i][1]; }
        mx /= c.length; my /= c.length;
        double a = Math.toRadians(deg), cos = Math.cos(a), sin = Math.sin(a);
        float[] x = new float[21], y = new float[21];
        for (int i = 0; i < 21; i++) {
            double lx = p[i][0] - mx, ly = p[i][1] - my;
            x[i] = (float) (cx + (lx * cos - ly * sin) * s);
            y[i] = (float) (cy + (lx * sin + ly * cos) * s);
        }
        return new float[][]{x, y};
    }

    void feed(float[][] hand) {
        emitted.addAll(engine.onFrame(HandFrame.of(t, hand[0], hand[1])));
        t += DT;
    }

    /** A frame with an exact bounding box height, for the hysteresis tests. */
    void feedBox(float[][] hand, double boxH) {
        emitted.addAll(engine.onFrame(HandFrame.of(t, hand[0], hand[1],
                0.3f, 0.2f, 0.7f, (float) (0.2 + boxH))));
        t += DT;
    }

    void feedEmpty() {
        emitted.addAll(engine.onFrame(HandFrame.empty(t)));
        t += DT;
    }

    int count(Cmd c) {
        int n = 0;
        for (Cmd e : emitted) if (e == c) n++;
        return n;
    }

    /** Arm with the hand still, then tilt it degPerFrame for n frames, the
     *  centre fixed. */
    void tilt(double[][] shape, double degPerFrame, int n, double s) {
        for (int i = 0; i < 3; i++) feed(hand(shape, 0.5, 0.5, s, 0));
        for (int i = 1; i <= n; i++) feed(hand(shape, 0.5, 0.5, s, degPerFrame * i));
    }

    // ---- tilt ------------------------------------------------------------------------

    @Test public void clockwiseTiltEmitsVolumeUpOnly() {
        tilt(OPEN, 5, 12, 0.5);
        System.out.println("clockwise tilt emitted " + emitted);
        assertTrue("expected VOL_UP steps, got " + emitted, count(Cmd.VOL_UP) >= 2);
        assertEquals("only VOL_UP expected, got " + emitted, count(Cmd.VOL_UP), emitted.size());
    }

    @Test public void anticlockwiseTiltEmitsVolumeDownOnly() {
        tilt(OPEN, -5, 12, 0.5);
        System.out.println("anticlockwise tilt emitted " + emitted);
        assertTrue("expected VOL_DOWN steps, got " + emitted, count(Cmd.VOL_DOWN) >= 2);
        assertEquals("only VOL_DOWN expected, got " + emitted, count(Cmd.VOL_DOWN), emitted.size());
    }

    @Test public void twoFingerTiltWorksBothWays() {
        tilt(twoFingerShape(), 5, 12, 0.5);
        assertTrue("two-finger clockwise, got " + emitted, count(Cmd.VOL_UP) >= 2);
        assertEquals(count(Cmd.VOL_UP), emitted.size());
        for (int i = 0; i < 10; i++) feedEmpty();
        emitted.clear();
        tilt(twoFingerShape(), -5, 12, 0.5);
        assertTrue("two-finger anticlockwise, got " + emitted, count(Cmd.VOL_DOWN) >= 2);
        assertEquals(count(Cmd.VOL_DOWN), emitted.size());
    }

    /** No finger-count gate for tilt. */
    @Test public void tiltWithNoFingerExtendedStillCounts() {
        tilt(FIST, 5, 12, 0.6);
        assertTrue("a closed hand tilts the volume too, got " + emitted, count(Cmd.VOL_UP) >= 2);
        assertEquals(count(Cmd.VOL_UP), emitted.size());
    }

    @Test public void tiltInvertFlipsDirection() {
        tuning.tiltInvert = true;
        tilt(OPEN, 5, 12, 0.5);
        assertTrue("inverted clockwise is VOL_DOWN, got " + emitted, count(Cmd.VOL_DOWN) >= 2);
        assertEquals(count(Cmd.VOL_DOWN), emitted.size());
    }

    /** Tilting back unwinds the count, like a dial: out and back again by the
     *  same amount, slowly enough to stay under a step, emits nothing. */
    @Test public void tiltOutAndBackUnderAStepEmitsNothing() {
        for (int i = 0; i < 3; i++) feed(openHand(0.5, 0.5, 0.5, 0));
        for (int i = 1; i <= 4; i++) feed(openHand(0.5, 0.5, 0.5, 5 * i));      // +20
        for (int i = 3; i >= 0; i--) feed(openHand(0.5, 0.5, 0.5, 5 * i));      // back to 0
        assertEquals("a wobble under a step is not volume, got " + emitted, 0,
                count(Cmd.VOL_UP) + count(Cmd.VOL_DOWN));
    }

    /** A tilting open palm turns the volume and never plays or pauses, fast
     *  or slow. */
    @Test public void tiltingPalmNeverPlays() {
        tilt(OPEN, 5, 30, 0.5);
        for (int i = 0; i < 10; i++) feedEmpty();
        tilt(OPEN, 2, 40, 0.5);
        System.out.println("tilting palm emitted " + emitted);
        assertEquals("tilting palm must never PLAY_PAUSE, got " + emitted, 0, count(Cmd.PLAY_PAUSE));
        assertTrue("tilting palm steps the volume, got " + emitted, count(Cmd.VOL_UP) >= 6);
    }

    // ---- open palm hold ----------------------------------------------------------------

    @Test public void stillPalmPlaysOnceThenNeedsCloseAndReopen() {
        for (int i = 0; i < 11; i++) feed(openHand(0.5, 0.5, 0.5)); // 0..660 ms, still
        assertEquals("a still palm for 700 ms fires once, got " + emitted, 1, count(Cmd.PLAY_PAUSE));
        for (int i = 0; i < 30; i++) feed(openHand(0.5, 0.5, 0.5)); // keeps holding, 2 s
        assertEquals("holding on must not fire again, got " + emitted, 1, count(Cmd.PLAY_PAUSE));
        for (int i = 0; i < 2; i++) feed(fist(0.5, 0.5, 0.5));      // closes for 132 ms only
        for (int i = 0; i < 15; i++) feed(openHand(0.5, 0.5, 0.5));
        assertEquals("a blink shorter than 300 ms does not re-arm, got " + emitted, 1, count(Cmd.PLAY_PAUSE));
        for (int i = 0; i < 6; i++) feed(fist(0.5, 0.5, 0.5));      // closes for 330 ms
        for (int i = 0; i < 11; i++) feed(openHand(0.5, 0.5, 0.5)); // reopens, still 700 ms
        assertEquals("close then reopen fires again, got " + emitted, 2, count(Cmd.PLAY_PAUSE));
        assertEquals("nothing but PLAY_PAUSE, got " + emitted, 2, emitted.size());
    }

    /** A palm that drifts more than allowed keeps restarting the hold. */
    @Test public void palmThatWandersNeverPlays() {
        for (int i = 0; i < 40; i++) feed(openHand(0.5 + 0.01 * i, 0.5, 0.5));
        assertEquals("a drifting palm must not fire, got " + emitted, 0, count(Cmd.PLAY_PAUSE));
    }

    // ---- palm latch survives dropouts ---------------------------------------------------

    /** Size dropout in the middle of the hold: fires once, not twice. */
    @Test public void palmSizeDropoutMidHoldDoesNotDoubleFire() {
        for (int i = 0; i < 5; i++) feed(openHand(0.5, 0.5, 0.5));
        for (int i = 0; i < 3; i++) feed(openHand(0.5, 0.5, 0.10)); // box 0.10, under the hold
        for (int i = 0; i < 30; i++) feed(openHand(0.5, 0.5, 0.5));
        assertEquals("one hold, one PLAY_PAUSE, got " + emitted, 1, count(Cmd.PLAY_PAUSE));
    }

    /** Same, with the dropout long enough (660 ms) to pass the grace and reset. */
    @Test public void palmLongSizeDropoutMidHoldDoesNotDoubleFire() {
        for (int i = 0; i < 5; i++) feed(openHand(0.5, 0.5, 0.5));
        for (int i = 0; i < 10; i++) feed(openHand(0.5, 0.5, 0.10));
        for (int i = 0; i < 30; i++) feed(openHand(0.5, 0.5, 0.5));
        assertEquals("one hold, one PLAY_PAUSE, got " + emitted, 1, count(Cmd.PLAY_PAUSE));
    }

    /** Dropout after the palm fired, still open throughout: the latch holds. */
    @Test public void palmLatchSurvivesSizeDropoutAfterFiring() {
        for (int i = 0; i < 11; i++) feed(openHand(0.5, 0.5, 0.5));
        assertEquals(1, count(Cmd.PLAY_PAUSE));
        for (int i = 0; i < 10; i++) feed(openHand(0.5, 0.5, 0.10));
        assertEquals(GestureEngine.State.IDLE, engine.state());
        for (int i = 0; i < 20; i++) feed(openHand(0.5, 0.5, 0.5));
        assertEquals("still-open palm after a dropout must not fire, got " + emitted,
                1, count(Cmd.PLAY_PAUSE));
    }

    /** No hand at all for longer than palmUnlatchAbsentMs re-arms the palm. */
    @Test public void palmLatchClearsAfterHandGone() {
        for (int i = 0; i < 11; i++) feed(openHand(0.5, 0.5, 0.5));
        for (int i = 0; i < 25; i++) feedEmpty(); // 1650 ms
        for (int i = 0; i < 11; i++) feed(openHand(0.5, 0.5, 0.5));
        assertEquals("palm re-arms after the hand was gone, got " + emitted, 2, count(Cmd.PLAY_PAUSE));
    }

    /** A shorter absence (990 ms) resets the engine but keeps the latch. */
    @Test public void palmLatchKeptAfterShortAbsence() {
        for (int i = 0; i < 11; i++) feed(openHand(0.5, 0.5, 0.5));
        for (int i = 0; i < 15; i++) feedEmpty();
        assertEquals(GestureEngine.State.IDLE, engine.state());
        for (int i = 0; i < 20; i++) feed(openHand(0.5, 0.5, 0.5));
        assertEquals("short absence must not re-arm the palm, got " + emitted, 1, count(Cmd.PLAY_PAUSE));
    }

    // ---- swipes ------------------------------------------------------------------------

    @Test public void fastRightwardSweepEmitsNextOnly() {
        for (int i = 0; i < 3; i++) feed(openHand(0.25, 0.5, 0.5));
        for (int i = 1; i <= 6; i++) feed(openHand(0.25 + 0.09 * i, 0.5 + 0.005 * i, 0.5));
        for (int i = 0; i < 4; i++) feedEmpty(); // hand leaves, as it does after a swipe
        System.out.println("sweep emitted " + emitted);
        assertEquals("expected exactly one NEXT, got " + emitted, 1, count(Cmd.NEXT));
        assertEquals("no tilt steps or anything else, got " + emitted, 1, emitted.size());
    }

    /** The owner's measured swipes travel 0.10 to 0.20 of the frame: 0.16 in
     *  about 260 ms, with some vertical drift, fires; 0.10 does not. */
    @Test public void measuredSizeSwipeFiresAndShortOneDoesNot() {
        for (int i = 0; i < 3; i++) feed(openHand(0.40, 0.5, 0.5));
        for (int i = 1; i <= 4; i++) feed(openHand(0.40 + 0.04 * i, 0.5 + 0.02 * i, 0.5));
        assertEquals("0.16 sideways with 0.08 vertical is a swipe, got " + emitted, 1, count(Cmd.NEXT));
        for (int i = 0; i < 10; i++) feedEmpty();
        emitted.clear();
        for (int i = 0; i < 3; i++) feed(openHand(0.60, 0.5, 0.5));
        for (int i = 1; i <= 4; i++) feed(openHand(0.60 - 0.025 * i, 0.5, 0.5));
        assertEquals("0.10 sideways is not a swipe, got " + emitted, 0, emitted.size());
    }

    /** A swipe whose hand also tilts as the arm sweeps is still a swipe: the
     *  hand is travelling, so its tilt is not counted. */
    @Test public void sweepWithTiltingHandIsNotATilt() {
        for (int i = 0; i < 3; i++) feed(openHand(0.25, 0.5, 0.5, 0));
        for (int i = 1; i <= 6; i++) feed(openHand(0.25 + 0.09 * i, 0.5, 0.5, 8 * i));
        assertEquals("one NEXT, got " + emitted, 1, count(Cmd.NEXT));
        assertEquals("no volume from a swipe, got " + emitted, 0, count(Cmd.VOL_UP) + count(Cmd.VOL_DOWN));
    }

    @Test public void fastLeftwardSweepEmitsPrev() {
        for (int i = 0; i < 3; i++) feed(openHand(0.75, 0.5, 0.5));
        for (int i = 1; i <= 6; i++) feed(openHand(0.75 - 0.09 * i, 0.5, 0.5));
        assertEquals("expected exactly one PREV, got " + emitted, 1, count(Cmd.PREV));
        assertEquals(1, emitted.size());
    }

    // ---- size gate ---------------------------------------------------------------------

    /** Hand far from the camera: tilt, sweep and still palm, at a size whose
     *  bounding box (at most 0.15, less when tilted) stays under the 0.18
     *  arming height. */
    void smallHandRoutine() {
        double s = 0.15;
        tilt(OPEN, 5, 12, s);
        for (int i = 0; i < 10; i++) feedEmpty();
        for (int i = 0; i < 3; i++) feed(openHand(0.25, 0.5, s));
        for (int i = 1; i <= 6; i++) feed(openHand(0.25 + 0.09 * i, 0.5, s));
        for (int i = 0; i < 10; i++) feedEmpty();
        for (int i = 0; i < 20; i++) feed(openHand(0.5, 0.5, s));
    }

    @Test public void tooSmallHandEmitsNothing() {
        smallHandRoutine();
        System.out.println("small hand emitted " + emitted);
        assertEquals("a far hand must emit nothing, got " + emitted, 0, emitted.size());
    }

    /** Control: the same motions with the size gate lowered do fire, so it is
     *  the gate, not the motion, that kept them quiet. */
    @Test public void sameSmallMotionsFireWhenGateLowered() {
        tuning.minBoxHeight = 0.05;
        tuning.minBoxHeightHold = 0.03;
        smallHandRoutine();
        System.out.println("small hand, gate lowered, emitted " + emitted);
        assertTrue("tilt should fire with the gate lowered, got " + emitted, count(Cmd.VOL_UP) >= 1);
        assertEquals("sweep should fire with the gate lowered, got " + emitted, 1, count(Cmd.NEXT));
        assertEquals("palm should fire with the gate lowered, got " + emitted, 1, count(Cmd.PLAY_PAUSE));
    }

    @Test public void handLeavingResetsToIdle() {
        for (int i = 0; i < 3; i++) feed(fist(0.5, 0.5, 0.5));
        assertEquals(GestureEngine.State.READY, engine.state());
        for (int i = 0; i < 5; i++) feedEmpty(); // 330 ms, inside the 400 ms grace
        assertEquals(GestureEngine.State.READY, engine.state());
        for (int i = 0; i < 3; i++) feedEmpty(); // now past it
        assertEquals(GestureEngine.State.IDLE, engine.state());
        assertEquals(0, emitted.size());
    }

    // ---- size hysteresis during a tilt -----------------------------------------------------

    /**
     * Arms with the hand still (box 0.22), then tilts it 5 degrees a frame for
     * n frames with the box height given per frame. Records the state after
     * each tilting frame and how many commands that frame emitted.
     */
    void tiltWithBox(int n, IntToDoubleFunction boxAt, GestureEngine.State[] st, int[] em) {
        for (int i = 0; i < 3; i++) feedBox(openHand(0.5, 0.5, 0.5, 0), 0.22);
        for (int i = 0; i < n; i++) {
            int before = emitted.size();
            feedBox(openHand(0.5, 0.5, 0.5, 5 * (i + 1)), boxAt.applyAsDouble(i));
            st[i] = engine.state();
            em[i] = emitted.size() - before;
        }
    }

    /** Box dips from 0.22 to 0.16 for three frames mid-tilt: under the arming
     *  height but over the hold height, so the tilt carries on. */
    @Test public void tiltSurvivesShortSizeDip() {
        final int from = 8, to = 10, n = 24;
        GestureEngine.State[] st = new GestureEngine.State[n];
        int[] em = new int[n];
        tiltWithBox(n, i -> (i >= from && i <= to) ? 0.16 : 0.22, st, em);
        int before = 0, after = 0;
        for (int i = 0; i < n; i++) {
            if (i >= from && i <= to) {
                assertEquals("frame " + i + " in the dip", GestureEngine.State.TILTING, st[i]);
            }
            if (i < from) before += em[i]; else after += em[i];
        }
        assertTrue("steps before the dip, got " + emitted, before >= 1);
        assertTrue("steps keep coming through and after the dip, got " + emitted, after >= 2);
        assertEquals("only VOL_UP, got " + emitted, count(Cmd.VOL_UP), emitted.size());
    }

    /** A dip longer than the dropout grace (8 frames, 528 ms) isolates the
     *  hysteresis: tilting stays active and steps come inside the dip. */
    @Test public void tiltSurvivesLongSizeDipAboveHold() {
        final int from = 8, to = 15, n = 24;
        GestureEngine.State[] st = new GestureEngine.State[n];
        int[] em = new int[n];
        tiltWithBox(n, i -> (i >= from && i <= to) ? 0.16 : 0.22, st, em);
        int inDip = 0;
        for (int i = from; i <= to; i++) {
            assertEquals("frame " + i + " in the dip", GestureEngine.State.TILTING, st[i]);
            inDip += em[i];
        }
        assertTrue("steps emitted during the dip, got " + emitted, inDip >= 1);
    }

    /** Control: the same long dip but below the hold height drops the hand. */
    @Test public void longDipBelowHoldDropsTheHand() {
        final int from = 8, to = 15, n = 24;
        GestureEngine.State[] st = new GestureEngine.State[n];
        int[] em = new int[n];
        tiltWithBox(n, i -> (i >= from && i <= to) ? 0.10 : 0.22, st, em);
        assertEquals("a dip under the hold height past the grace drops to IDLE",
                GestureEngine.State.IDLE, st[to]);
    }
}
