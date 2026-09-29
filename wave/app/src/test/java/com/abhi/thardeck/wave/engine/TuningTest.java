package com.abhi.thardeck.wave.engine;

import static org.junit.Assert.assertEquals;

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

    @Test public void motionGateDefault() {
        assertEquals(6.0, new Tuning().motionMinDiff, 1e-9);
    }
}
