package com.abhi.thardeck.wave.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

/**
 * Synthetic landmark sequences through the engine. Coordinates are in the
 * driver's frame: +x is the driver's right, +y is down. Frames arrive every
 * 66 ms, which is the 15 fps ceiling the app analyses at with a hand present.
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

    /**
     * An open hand, fingers up, centred on (cx, cy), total height s from the
     * wrist to the middle fingertip.
     */
    static float[][] openHand(double cx, double cy, double s) {
        double[][] p = {
            {0.00, 0.50},                                               // 0 wrist
            {-0.15, 0.40}, {-0.25, 0.30}, {-0.32, 0.20}, {-0.38, 0.10}, // thumb
            {-0.12, 0.05}, {-0.13, -0.15}, {-0.14, -0.28}, {-0.15, -0.40}, // index
            {-0.02, 0.05}, {-0.02, -0.17}, {-0.02, -0.33}, {-0.02, -0.50}, // middle
            {0.08, 0.05}, {0.09, -0.14}, {0.10, -0.27}, {0.11, -0.38},     // ring
            {0.17, 0.08}, {0.19, -0.08}, {0.20, -0.17}, {0.21, -0.26},     // pinky
        };
        return place(p, cx, cy, s);
    }

    /** A closed fist: tips curled back below the PIP joints, near the palm. */
    static float[][] fist(double cx, double cy, double s) {
        double[][] p = {
            {0.00, 0.50},
            {-0.15, 0.40}, {-0.22, 0.30}, {-0.20, 0.20}, {-0.12, 0.15},
            {-0.12, 0.05}, {-0.13, -0.12}, {-0.13, 0.00}, {-0.12, 0.08},
            {-0.02, 0.05}, {-0.02, -0.13}, {-0.02, 0.00}, {-0.02, 0.08},
            {0.08, 0.05}, {0.09, -0.11}, {0.09, 0.00}, {0.08, 0.08},
            {0.17, 0.08}, {0.18, -0.06}, {0.18, 0.03}, {0.17, 0.10},
        };
        return place(p, cx, cy, s);
    }

    static float[][] place(double[][] p, double cx, double cy, double s) {
        float[] x = new float[21], y = new float[21];
        for (int i = 0; i < 21; i++) {
            x[i] = (float) (cx + p[i][0] * s);
            y[i] = (float) (cy + p[i][1] * s);
        }
        return new float[][]{x, y};
    }

    void feed(float[][] hand) {
        emitted.addAll(engine.onFrame(HandFrame.of(t, hand[0], hand[1])));
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

    /**
     * Hand held still for a moment (arming), then the index fingertip traces a
     * 20 point circle of radius 0.08 while the rest of the hand stays put.
     * Angles grow clockwise on screen because +y is down.
     */
    void circle(boolean clockwise, double s) {
        double cx = 0.5, cy = 0.55;
        double tcx = cx - 0.10 * s, tcy = cy - 0.55 * s, r = 0.08;
        for (int i = 0; i < 4; i++) feed(withTip(openHand(cx, cy, s), tcx + r, tcy)); // arm
        for (int i = 0; i < 20; i++) {
            double a = Math.toRadians((clockwise ? 1 : -1) * 18.0 * i);
            feed(withTip(openHand(cx, cy, s), tcx + r * Math.cos(a), tcy + r * Math.sin(a)));
        }
    }

    static float[][] withTip(float[][] h, double x, double y) {
        h[0][8] = (float) x;
        h[1][8] = (float) y;
        return h;
    }

    // ---- the five cases from the spec ------------------------------------------------

    @Test public void clockwiseCircleEmitsVolumeUpOnly() {
        circle(true, 0.5);
        System.out.println("clockwise emitted " + emitted);
        assertTrue("expected VOL_UP steps, got " + emitted, count(Cmd.VOL_UP) >= 2);
        assertEquals("only VOL_UP expected, got " + emitted, count(Cmd.VOL_UP), emitted.size());
    }

    @Test public void anticlockwiseCircleEmitsVolumeDownOnly() {
        circle(false, 0.5);
        System.out.println("anticlockwise emitted " + emitted);
        assertTrue("expected VOL_DOWN steps, got " + emitted, count(Cmd.VOL_DOWN) >= 2);
        assertEquals("only VOL_DOWN expected, got " + emitted, count(Cmd.VOL_DOWN), emitted.size());
    }

    @Test public void fastRightwardSweepEmitsNext() {
        for (int i = 0; i < 4; i++) feed(openHand(0.25, 0.5, 0.5));
        for (int i = 1; i <= 6; i++) feed(openHand(0.25 + 0.09 * i, 0.5 + 0.005 * i, 0.5));
        for (int i = 0; i < 10; i++) feed(openHand(0.79, 0.53, 0.5)); // comes to rest
        System.out.println("sweep emitted " + emitted);
        assertEquals("expected exactly one NEXT, got " + emitted, 1, count(Cmd.NEXT));
        assertEquals("expected nothing but NEXT, got " + emitted, 1, emitted.size());
    }

    @Test public void heldFistEmitsPlayPauseExactlyOnce() {
        for (int i = 0; i < 30; i++) feed(fist(0.5, 0.5, 0.6)); // about two seconds
        System.out.println("fist emitted " + emitted);
        assertEquals("expected exactly one PLAY_PAUSE, got " + emitted, 1, count(Cmd.PLAY_PAUSE));
        assertEquals("expected nothing but PLAY_PAUSE, got " + emitted, 1, emitted.size());
    }

    /** Hand far from the camera: circle, sweep and fist, all at a size whose
     *  bounding box stays under the 0.28 floor even with the fingertip out. */
    void smallHandRoutine() {
        double s = 0.17; // box at most 1.05 * s + 0.08, about 0.26 of the frame
        circle(true, s);
        for (int i = 0; i < 10; i++) feedEmpty();
        for (int i = 0; i < 4; i++) feed(openHand(0.25, 0.5, s));
        for (int i = 1; i <= 6; i++) feed(openHand(0.25 + 0.09 * i, 0.5, s));
        for (int i = 0; i < 10; i++) feedEmpty();
        for (int i = 0; i < 30; i++) feed(fist(0.5, 0.5, s));
    }

    @Test public void tooSmallHandEmitsNothing() {
        smallHandRoutine();
        System.out.println("small hand emitted " + emitted);
        assertEquals("a far hand must emit nothing, got " + emitted, 0, emitted.size());
    }

    /** Control for the case above: the same motions with the size gate lowered
     *  do fire, so it is the gate, not the motion, that kept them quiet. */
    @Test public void sameSmallMotionsFireWhenGateLowered() {
        tuning.minBoxHeight = 0.05;
        smallHandRoutine();
        System.out.println("small hand, gate lowered, emitted " + emitted);
        assertTrue("circle should fire with the gate lowered, got " + emitted, count(Cmd.VOL_UP) >= 1);
        assertEquals("sweep should fire with the gate lowered, got " + emitted, 1, count(Cmd.NEXT));
        assertEquals("fist should fire with the gate lowered, got " + emitted, 1, count(Cmd.PLAY_PAUSE));
    }

    // ---- extra coverage -----------------------------------------------------------

    @Test public void fastLeftwardSweepEmitsPrev() {
        for (int i = 0; i < 4; i++) feed(openHand(0.75, 0.5, 0.5));
        for (int i = 1; i <= 6; i++) feed(openHand(0.75 - 0.09 * i, 0.5, 0.5));
        assertEquals("expected exactly one PREV, got " + emitted, 1, count(Cmd.PREV));
        assertEquals(1, emitted.size());
    }

    @Test public void fistMustOpenBeforeFiringAgain() {
        for (int i = 0; i < 20; i++) feed(fist(0.5, 0.5, 0.6));
        for (int i = 0; i < 5; i++) feed(openHand(0.5, 0.5, 0.6));
        for (int i = 0; i < 20; i++) feed(fist(0.5, 0.5, 0.6));
        assertEquals("fist, open, fist is two toggles, got " + emitted, 2, count(Cmd.PLAY_PAUSE));
    }

    @Test public void handLeavingResetsToIdle() {
        for (int i = 0; i < 6; i++) feed(openHand(0.5, 0.5, 0.5));
        assertEquals(GestureEngine.State.READY, engine.state());
        for (int i = 0; i < 6; i++) feedEmpty();
        assertEquals(GestureEngine.State.IDLE, engine.state());
        assertEquals(0, emitted.size());
    }
}
