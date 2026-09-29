package com.abhi.thardeck.wave.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

/**
 * Synthetic landmark sequences through the engine. Coordinates are in the
 * driver's frame: +x is the driver's right, +y is down, so a hand moving up
 * the screen has falling y. Frames arrive every 66 ms, which is the 15 fps
 * ceiling the app analyses at with a hand present.
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

    /** Index and middle out, ring and pinky curled: not an open palm. */
    static double[][] twoFingerShape() {
        double[][] p = new double[21][];
        for (int i = 0; i < 21; i++) p[i] = (i >= 13 ? FIST[i] : OPEN[i]).clone();
        return p;
    }

    static float[][] openHand(double cx, double cy, double s) { return hand(OPEN, cx, cy, s); }
    static float[][] fist(double cx, double cy, double s) { return hand(FIST, cx, cy, s); }
    static float[][] twoFinger(double cx, double cy, double s) { return hand(twoFingerShape(), cx, cy, s); }

    /** Places a shape scaled by s with the hand centre (mean of landmarks 0,
     *  5, 9, 13, 17) exactly at (cx, cy). */
    static float[][] hand(double[][] p, double cx, double cy, double s) {
        int[] c = {0, 5, 9, 13, 17};
        double mx = 0, my = 0;
        for (int i : c) { mx += p[i][0]; my += p[i][1]; }
        mx /= c.length; my /= c.length;
        float[] x = new float[21], y = new float[21];
        for (int i = 0; i < 21; i++) {
            x[i] = (float) (cx + (p[i][0] - mx) * s);
            y[i] = (float) (cy + (p[i][1] - my) * s);
        }
        return new float[][]{x, y};
    }

    void feed(float[][] hand) {
        emitted.addAll(engine.onFrame(HandFrame.of(t, hand[0], hand[1])));
        t += DT;
    }

    /** A frame with an exact bounding box height. */
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

    int volume() { return count(Cmd.VOL_UP) + count(Cmd.VOL_DOWN); }

    /** Open hand at (0.5, y), n frames still. */
    void still(double y, int n) {
        for (int i = 0; i < n; i++) feed(openHand(0.5, y, 0.5));
    }

    /** Open hand moving vertically from y0 by total over n frames (negative
     *  total is up). Returns the final y. */
    double stroke(double y0, double total, int n) {
        for (int i = 1; i <= n; i++) feed(openHand(0.5, y0 + total * i / n, 0.5));
        return y0 + total;
    }

    // ---- strokes -------------------------------------------------------------------

    @Test public void upwardStrokeEmitsOneVolumeUp() {
        still(0.6, 3);
        stroke(0.6, -0.15, 4);
        still(0.45, 6);
        System.out.println("up stroke emitted " + emitted);
        assertEquals("exactly one VOL_UP, got " + emitted, 1, count(Cmd.VOL_UP));
        assertEquals("nothing else, got " + emitted, 1, emitted.size());
    }

    @Test public void downwardStrokeEmitsOneVolumeDown() {
        still(0.4, 3);
        stroke(0.4, 0.15, 4);
        still(0.55, 6);
        System.out.println("down stroke emitted " + emitted);
        assertEquals("exactly one VOL_DOWN, got " + emitted, 1, count(Cmd.VOL_DOWN));
        assertEquals("nothing else, got " + emitted, 1, emitted.size());
    }

    /** A pump: up, a 350 ms pause at the top, then down. */
    @Test public void pumpUpThenDownEmitsUpThenDown() {
        still(0.6, 3);
        double y = stroke(0.6, -0.15, 4);
        still(y, 5); // about 350 ms at the top
        stroke(y, 0.15, 4);
        System.out.println("pump emitted " + emitted);
        assertEquals("VOL_UP then VOL_DOWN, got " + emitted, 2, emitted.size());
        assertEquals(Cmd.VOL_UP, emitted.get(0));
        assertEquals(Cmd.VOL_DOWN, emitted.get(1));
    }

    /** Several steps up: flick up, drop the hand out of view, flick up again.
     *  The return out of view must not count as a down stroke. */
    @Test public void twoUpStrokesWithTheHandLostBetweenEmitTwoUps() {
        still(0.6, 3);
        stroke(0.6, -0.15, 4);
        for (int i = 0; i < 3; i++) feedEmpty(); // hand drops out of view
        still(0.6, 1);                            // back in at the bottom
        stroke(0.6, -0.15, 4);
        System.out.println("two ups emitted " + emitted);
        assertEquals("two VOL_UP, got " + emitted, 2, count(Cmd.VOL_UP));
        assertEquals("nothing else, got " + emitted, 2, emitted.size());
    }

    /** 0.05 of drift over 500 ms is not a stroke. A two-finger hand, so the
     *  palm hold is out of the picture. */
    @Test public void slowDriftEmitsNothing() {
        for (int i = 0; i < 3; i++) feed(twoFinger(0.5, 0.5, 0.5));
        for (int i = 1; i <= 8; i++) feed(twoFinger(0.5, 0.5 - 0.05 * i / 8, 0.5));
        assertEquals("slow drift must emit nothing, got " + emitted, 0, emitted.size());
    }

    /** A diagonal move, more sideways than up or down, is a swipe, not a stroke. */
    @Test public void diagonalMoveIsASwipeNotAStroke() {
        still(0.5, 3);
        for (int i = 1; i <= 4; i++) feed(openHand(0.5 + 0.05 * i, 0.5 + 0.025 * i, 0.5));
        System.out.println("diagonal emitted " + emitted);
        assertEquals("one NEXT, got " + emitted, 1, count(Cmd.NEXT));
        assertEquals("no stroke, got " + emitted, 0, volume());
    }

    @Test public void strokeInvertFlipsDirection() {
        tuning.strokeInvert = true;
        still(0.6, 3);
        stroke(0.6, -0.15, 4);
        assertEquals("inverted up is VOL_DOWN, got " + emitted, 1, count(Cmd.VOL_DOWN));
        assertEquals(1, emitted.size());
    }

    // ---- size floor while tracking ----------------------------------------------------

    /** Armed at 0.22, the hand then reads 0.10 (under the hold height, over
     *  the stroke floor): strokes still track. */
    @Test public void armedHandKeepsTrackingDownToTheStrokeFloor() {
        for (int i = 0; i < 3; i++) feedBox(openHand(0.5, 0.6, 0.5), 0.22);
        for (int i = 1; i <= 4; i++) feedBox(openHand(0.5, 0.6 - 0.0375 * i, 0.5), 0.10);
        assertEquals("armed hand at 0.10 strokes, got " + emitted, 1, count(Cmd.VOL_UP));
    }

    /** The same motion at 0.10 from the start never arms, so nothing fires. */
    @Test public void neverArmedHandAtStrokeFloorEmitsNothing() {
        for (int i = 0; i < 3; i++) feedBox(openHand(0.5, 0.6, 0.5), 0.10);
        for (int i = 1; i <= 4; i++) feedBox(openHand(0.5, 0.6 - 0.0375 * i, 0.5), 0.10);
        assertEquals("never armed at 0.10 emits nothing, got " + emitted, 0, emitted.size());
    }

    /** Control: armed, then under the floor (0.06): no stroke, and the hand
     *  drops to IDLE once past the grace. */
    @Test public void armedHandUnderTheFloorDoesNotStroke() {
        for (int i = 0; i < 3; i++) feedBox(openHand(0.5, 0.6, 0.5), 0.22);
        for (int i = 1; i <= 8; i++) feedBox(openHand(0.5, 0.6 - 0.02 * i, 0.5), 0.06);
        assertEquals("under the floor nothing fires, got " + emitted, 0, emitted.size());
        assertEquals(GestureEngine.State.IDLE, engine.state());
    }

    /** Between the hold height and the arming height (0.16) an armed hand
     *  still swipes; under the hold height (0.10) it strokes but does not. */
    @Test public void swipesWantTheHoldHeight() {
        for (int i = 0; i < 3; i++) feedBox(openHand(0.25, 0.5, 0.5), 0.22);
        for (int i = 1; i <= 4; i++) feedBox(openHand(0.25 + 0.05 * i, 0.5, 0.5), 0.16);
        assertEquals("armed at 0.16 swipes, got " + emitted, 1, count(Cmd.NEXT));
        for (int i = 0; i < 10; i++) feedEmpty();
        emitted.clear();
        for (int i = 0; i < 3; i++) feedBox(openHand(0.25, 0.5, 0.5), 0.22);
        for (int i = 1; i <= 4; i++) feedBox(openHand(0.25 + 0.05 * i, 0.5, 0.5), 0.10);
        assertEquals("armed at 0.10 does not swipe, got " + emitted, 0, emitted.size());
    }

    // ---- open palm hold ----------------------------------------------------------------

    @Test public void stillPalmPlaysOnceThenNeedsCloseAndReopen() {
        still(0.5, 11); // 0..660 ms, still
        assertEquals("a still palm for 700 ms fires once, got " + emitted, 1, count(Cmd.PLAY_PAUSE));
        still(0.5, 30); // keeps holding, 2 s
        assertEquals("holding on must not fire again, got " + emitted, 1, count(Cmd.PLAY_PAUSE));
        for (int i = 0; i < 2; i++) feed(fist(0.5, 0.5, 0.5)); // closes for 132 ms only
        still(0.5, 15);
        assertEquals("a blink shorter than 300 ms does not re-arm, got " + emitted, 1, count(Cmd.PLAY_PAUSE));
        for (int i = 0; i < 6; i++) feed(fist(0.5, 0.5, 0.5)); // closes for 330 ms
        still(0.5, 11);                                         // reopens, still 700 ms
        assertEquals("close then reopen fires again, got " + emitted, 2, count(Cmd.PLAY_PAUSE));
        assertEquals("nothing but PLAY_PAUSE, got " + emitted, 2, emitted.size());
    }

    /** A palm pumping up and down is strokes, never a hold. */
    @Test public void movingPalmGivesStrokesOnly() {
        still(0.6, 3);
        double y = 0.6;
        for (int k = 0; k < 3; k++) {
            y = stroke(y, -0.15, 4);
            still(y, 5);
            y = stroke(y, 0.15, 4);
            still(y, 5);
        }
        System.out.println("pumping palm emitted " + emitted);
        assertEquals("never PLAY_PAUSE, got " + emitted, 0, count(Cmd.PLAY_PAUSE));
        assertEquals("three up strokes, got " + emitted, 3, count(Cmd.VOL_UP));
        assertEquals("three down strokes, got " + emitted, 3, count(Cmd.VOL_DOWN));
    }

    /** A palm that drifts more than allowed keeps restarting the hold. */
    @Test public void palmThatWandersNeverPlays() {
        for (int i = 0; i < 40; i++) feed(openHand(0.3 + 0.01 * i, 0.5, 0.5));
        assertEquals("a drifting palm must not fire, got " + emitted, 0, count(Cmd.PLAY_PAUSE));
    }

    // ---- palm latch survives dropouts ---------------------------------------------------

    /** Size dropout (box 0.06, under the floor) in the middle of the hold:
     *  fires once, not twice. */
    @Test public void palmSizeDropoutMidHoldDoesNotDoubleFire() {
        still(0.5, 5);
        for (int i = 0; i < 3; i++) feed(openHand(0.5, 0.5, 0.06));
        still(0.5, 30);
        assertEquals("one hold, one PLAY_PAUSE, got " + emitted, 1, count(Cmd.PLAY_PAUSE));
    }

    /** Same, with the dropout long enough (660 ms) to pass the grace and reset. */
    @Test public void palmLongSizeDropoutMidHoldDoesNotDoubleFire() {
        still(0.5, 5);
        for (int i = 0; i < 10; i++) feed(openHand(0.5, 0.5, 0.06));
        still(0.5, 30);
        assertEquals("one hold, one PLAY_PAUSE, got " + emitted, 1, count(Cmd.PLAY_PAUSE));
    }

    /** Dropout after the palm fired, still open throughout: the latch holds. */
    @Test public void palmLatchSurvivesSizeDropoutAfterFiring() {
        still(0.5, 11);
        assertEquals(1, count(Cmd.PLAY_PAUSE));
        for (int i = 0; i < 10; i++) feed(openHand(0.5, 0.5, 0.06));
        assertEquals(GestureEngine.State.IDLE, engine.state());
        still(0.5, 20);
        assertEquals("still-open palm after a dropout must not fire, got " + emitted,
                1, count(Cmd.PLAY_PAUSE));
    }

    /** No hand at all for longer than palmUnlatchAbsentMs re-arms the palm. */
    @Test public void palmLatchClearsAfterHandGone() {
        still(0.5, 11);
        for (int i = 0; i < 25; i++) feedEmpty(); // 1650 ms
        still(0.5, 11);
        assertEquals("palm re-arms after the hand was gone, got " + emitted, 2, count(Cmd.PLAY_PAUSE));
    }

    /** A shorter absence (990 ms) resets the engine but keeps the latch. */
    @Test public void palmLatchKeptAfterShortAbsence() {
        still(0.5, 11);
        for (int i = 0; i < 15; i++) feedEmpty();
        assertEquals(GestureEngine.State.IDLE, engine.state());
        still(0.5, 20);
        assertEquals("short absence must not re-arm the palm, got " + emitted, 1, count(Cmd.PLAY_PAUSE));
    }

    // ---- swipes ------------------------------------------------------------------------

    @Test public void fastRightwardSweepEmitsNextOnly() {
        for (int i = 0; i < 3; i++) feed(openHand(0.25, 0.5, 0.5));
        for (int i = 1; i <= 6; i++) feed(openHand(0.25 + 0.09 * i, 0.5 + 0.005 * i, 0.5));
        for (int i = 0; i < 4; i++) feedEmpty(); // hand leaves, as it does after a swipe
        System.out.println("sweep emitted " + emitted);
        assertEquals("expected exactly one NEXT, got " + emitted, 1, count(Cmd.NEXT));
        assertEquals("no stroke or anything else, got " + emitted, 1, emitted.size());
    }

    /** The owner's measured swipes travel 0.10 to 0.20 of the frame: 0.16 in
     *  about 260 ms, with some vertical drift, fires; 0.10 does not. */
    @Test public void measuredSizeSwipeFiresAndShortOneDoesNot() {
        for (int i = 0; i < 3; i++) feed(openHand(0.40, 0.5, 0.5));
        for (int i = 1; i <= 4; i++) feed(openHand(0.40 + 0.04 * i, 0.5 + 0.02 * i, 0.5));
        assertEquals("0.16 sideways with 0.08 vertical is a swipe, got " + emitted, 1, count(Cmd.NEXT));
        assertEquals("and not a stroke, got " + emitted, 0, volume());
        for (int i = 0; i < 10; i++) feedEmpty();
        emitted.clear();
        for (int i = 0; i < 3; i++) feed(openHand(0.60, 0.5, 0.5));
        for (int i = 1; i <= 4; i++) feed(openHand(0.60 - 0.025 * i, 0.5, 0.5));
        assertEquals("0.10 sideways is not a swipe, got " + emitted, 0, emitted.size());
    }

    @Test public void fastLeftwardSweepEmitsPrev() {
        for (int i = 0; i < 3; i++) feed(openHand(0.75, 0.5, 0.5));
        for (int i = 1; i <= 6; i++) feed(openHand(0.75 - 0.09 * i, 0.5, 0.5));
        assertEquals("expected exactly one PREV, got " + emitted, 1, count(Cmd.PREV));
        assertEquals(1, emitted.size());
    }

    // ---- arming ------------------------------------------------------------------------

    /** Hand far from the camera: stroke, sweep and still palm, at a size whose
     *  bounding box (0.15) stays under the 0.18 arming height. */
    void smallHandRoutine() {
        double s = 0.15;
        for (int i = 0; i < 3; i++) feed(openHand(0.5, 0.6, s));
        for (int i = 1; i <= 4; i++) feed(openHand(0.5, 0.6 - 0.0375 * i, s));
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
        assertEquals("stroke should fire with the gate lowered, got " + emitted, 1, count(Cmd.VOL_UP));
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
}
