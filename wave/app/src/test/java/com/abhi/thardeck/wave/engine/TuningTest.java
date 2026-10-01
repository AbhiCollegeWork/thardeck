package com.abhi.thardeck.wave.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Every slider key reads and writes its own field, and reset restores it. */
public class TuningTest {

    @Test public void everyParamRoundTripsAndResets() {
        Tuning t = new Tuning();
        Tuning defaults = new Tuning();
        for (Tuning.Param p : Tuning.PARAMS) {
            double v = p.min + p.step;
            t.set(p.key, v);
            assertEquals(p.key, v, t.get(p.key), 1e-9);
        }
        t.reset();
        for (Tuning.Param p : Tuning.PARAMS) {
            assertEquals(p.key, defaults.get(p.key), t.get(p.key), 1e-9);
        }
    }

    /** Every default sits on its own slider's range and step. */
    @Test public void defaultsLandOnSliderSteps() {
        Tuning t = new Tuning();
        for (Tuning.Param p : Tuning.PARAMS) {
            double v = t.get(p.key);
            assertTrue(p.key + " below slider", v >= p.min - 1e-9);
            assertTrue(p.key + " above slider", v <= p.max + 1e-9);
            double steps = (v - p.min) / p.step;
            assertEquals(p.key + " off the slider step", Math.round(steps), steps, 1e-6);
        }
    }

    @Test public void calibrationDefaults() {
        Tuning t = new Tuning();
        assertEquals(800, t.entryAbsentMs, 1e-9);
        assertEquals(3000, t.entryWindowMs, 1e-9);
        assertEquals(1500, t.entryExtendMs, 1e-9);
        assertEquals(0.18, t.minBoxHeight, 1e-9);
        assertEquals(0.13, t.minBoxHeightHold, 1e-9);
        assertEquals(100, t.armMs, 1e-9);
        assertEquals(400, t.lostGraceMs, 1e-9);
        assertFalse(t.strokeInvert);
        assertEquals(0.08, t.strokeMinBox, 1e-9);
        assertEquals(500, t.strokeWindowMs, 1e-9);
        assertEquals(0.08, t.strokeMinTravel, 1e-9);
        assertEquals(1.5, t.strokeVerticalRatio, 1e-9);
        assertEquals(300, t.strokeRefractoryMs, 1e-9);
        assertEquals(0.55, t.strokeMaxBox, 1e-9);
        assertEquals(0.14, t.swipeMinDx, 1e-9);
        assertEquals(450, t.swipeWindowMs, 1e-9);
        assertEquals(0.5, t.swipeMaxDyRatio, 1e-9);
        assertEquals(150, t.swipeMinPresentMs, 1e-9);
        assertEquals(600, t.swipeCooldownMs, 1e-9);
        assertEquals(800, t.palmHoldMs, 1e-9);
        assertEquals(0.30, t.palmMinBox, 1e-9);
        assertEquals(0.45, t.palmMinWidth, 1e-9);
        assertEquals(0.04, t.palmMaxTravel, 1e-9);
        assertEquals(300, t.palmReleaseMs, 1e-9);
        assertEquals(1500, t.palmUnlatchAbsentMs, 1e-9);
        assertEquals(3.0, t.motionMinDiff, 1e-9);
        assertEquals(50, t.darkLuma, 1e-9);
    }

    @Test public void resetRestoresStrokeDirection() {
        Tuning t = new Tuning();
        t.strokeInvert = true;
        t.reset();
        assertFalse(t.strokeInvert);
    }

    /** The circle, fist, roll and tilt recognisers, and the settled-start
     *  rule for swipes, are gone, keys and all. */
    @Test public void retiredKeysAreGone() {
        for (String k : new String[]{"rotWindowMs", "rotStepDeg", "rotMinAspect",
                "fistHoldMs", "fistMaxTravel", "fistUnlatchAbsentMs",
                "rollWindowMs", "rollStepDeg", "rollMaxTravel", "palmMaxRollDeg",
                "tiltWindowMs", "tiltStepDeg", "tiltMaxTravel", "tiltMinDeltaDeg",
                "tiltMaxDeltaDeg", "tiltMaxStepsPerSec", "tiltReleaseMs", "palmMaxTiltDeg",
                "swipeSettleMs", "swipeSettleTravel", "swipeSettleWindowMs"}) {
            assertFalse(k, Tuning.isKey(k));
        }
    }

    /** Hold sits right after arm in the slider list. */
    @Test public void holdSliderFollowsArmSlider() {
        int arm = -1, hold = -1;
        for (int i = 0; i < Tuning.PARAMS.length; i++) {
            if (Tuning.PARAMS[i].key.equals("minBoxHeight")) arm = i;
            if (Tuning.PARAMS[i].key.equals("minBoxHeightHold")) hold = i;
        }
        assertEquals(arm + 1, hold);
    }
}
