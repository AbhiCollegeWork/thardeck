package com.abhi.thardeck.wave.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * The night and day decision on synthetic luma sequences, with and without
 * the exposure metadata. The reference is one frame at 15 fps (66.7 ms) at
 * ISO 3200; at that exposure the scene number equals the frame luma.
 */
public class LightModeTest {

    static final double REF_EXP = 1000.0 / 15, REF_ISO = 3200;
    static final double NONE = Double.NaN;
    static final long DT = 200; // the idle sampling rate, 5 fps

    Tuning tu;
    LightMode lm;
    long t;

    @Before public void setUp() {
        tu = new Tuning();
        lm = new LightMode(tu, REF_EXP, REF_ISO);
        t = 1000;
    }

    /** n frames at this luma and exposure; returns true if any changed mode. */
    boolean frames(int n, double luma, double expMs, double iso) {
        boolean changed = false;
        for (int i = 0; i < n; i++) {
            changed |= lm.update(t, luma, expMs, iso);
            t += DT;
        }
        return changed;
    }

    @Test public void sceneEqualsLumaAtTheReferenceExposure() {
        assertEquals(40, lm.sceneLuma(40, REF_EXP, REF_ISO), 1e-6);
        assertEquals(200, lm.sceneLuma(200, REF_EXP, REF_ISO), 1e-6);
        assertTrue(Double.isNaN(lm.sceneLuma(40, NONE, NONE)));
        assertTrue(Double.isNaN(new LightMode(tu, 0, 0).sceneLuma(40, REF_EXP, REF_ISO)));
    }

    /**
     * The feedback loop itself, with the dwell and the re-entry limit off:
     * entering night quadruples the exposure and the frame luma rises with
     * it, but the scene number does not, so night holds. Only a scene that
     * really brightens ends it.
     */
    @Test public void compensationDoesNotEndNight() {
        tu.nightMinDwellMs = 0;
        tu.nightReentryMs = 0;
        frames(1, 21, REF_EXP, REF_ISO);
        assertTrue("dark scene enters night", lm.night());
        double brighter = 255 * Math.pow(Math.pow(21 / 255.0, 2.2) * 4, 1 / 2.2); // about 39
        frames(20, brighter, REF_EXP * 2, REF_ISO * 2);                          // +2 EV
        assertTrue("compensated frame keeps night, scene " + lm.scene(), lm.night());
        assertEquals("scene still reads the dark scene", 21, lm.scene(), 0.5);
        assertTrue("a really brighter scene ends it", frames(1, 120, REF_EXP, REF_ISO));
        assertFalse(lm.night());
    }

    /** The drive log case: luma 21 enters night, the next frame reads 215.
     *  With the defaults it must not leave night, with or without metadata. */
    @Test public void jumpTo215TheFrameAfterEnteringDoesNotExit() {
        frames(1, 21, REF_EXP, REF_ISO);
        assertTrue(lm.night());
        // Next frame with the night exposure: a longer shutter (the [8, 30]
        // range) at full ISO and +2 EV.
        assertFalse("no exit straight after entering", frames(1, 215, 125, REF_ISO));
        assertFalse("none for the rest of the 30 s dwell", frames(140, 215, 125, REF_ISO));
        assertTrue("still night", lm.night());
    }

    @Test public void jumpTo215WithoutMetadataDoesNotExit() {
        frames(1, 21, NONE, NONE);
        assertTrue(lm.night());
        assertFalse(frames(1, 215, NONE, NONE));
        assertFalse(frames(140, 215, NONE, NONE));
        assertTrue("still night inside the dwell", lm.night());
        assertTrue("after the dwell, 215 is over the exit luma", frames(10, 215, NONE, NONE));
        assertFalse(lm.night());
    }

    /** The 10 Oct dawn case without metadata: in at about 40, the compensated
     *  frame then reads about 68. That is under darkLumaExit, so no exit. */
    @Test public void dawnOscillationWithoutMetadataHolds() {
        tu.nightMinDwellMs = 0;
        frames(1, 40, NONE, NONE);
        assertTrue(lm.night());
        assertFalse("68 is under the 170 exit", frames(900, 68, NONE, NONE)); // three minutes
        assertTrue(lm.night());
        assertTrue(frames(1, 180, NONE, NONE));
        assertFalse(lm.night());
    }

    /** Never back into night within 60 s of leaving it. */
    @Test public void noReentryWithinAMinute() {
        tu.nightMinDwellMs = 0;
        frames(1, 21, REF_EXP, REF_ISO);
        frames(1, 120, REF_EXP, REF_ISO);
        assertFalse("left night", lm.night());
        assertFalse("dark again 10 s later: stays day", frames(50, 21, REF_EXP, REF_ISO));
        assertFalse(lm.night());
        frames(250, 21, REF_EXP, REF_ISO); // to past 60 s after leaving
        assertTrue("then night again", lm.night());
    }

    /** Each mode is held at least the dwell. */
    @Test public void eachModeHeldForTheDwell() {
        tu.nightReentryMs = 0;
        frames(1, 21, REF_EXP, REF_ISO);
        assertTrue(lm.night());
        frames(140, 120, REF_EXP, REF_ISO); // 28 s of bright scene
        assertTrue("held inside 30 s", lm.night());
        frames(15, 120, REF_EXP, REF_ISO);  // past 30 s
        assertFalse("then day", lm.night());
    }
}
