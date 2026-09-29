package com.abhi.thardeck.wave.engine;

/**
 * Every threshold the gesture engine uses, in one place, with the defaults from
 * the design. Pure Java so the engine and its tests stay free of Android.
 *
 * The app screen edits these live (the service and the screen share one
 * instance), and persists them by key, so a calibration session in the car can
 * move a threshold without a rebuild.
 *
 * Distances are in normalised frame units (0..1 of the analysed frame), times
 * in milliseconds, angles in degrees.
 */
public final class Tuning {

    // ---- arming ------------------------------------------------------------
    /** Hand bounding box height, as a fraction of the frame, needed to count.
     *  Keeps a hand resting on the wheel from triggering anything. */
    public volatile double minBoxHeight = 0.28;
    /** A hand must be present and big enough this long before it counts. */
    public volatile double armMs = 150;
    /** A tracking dropout shorter than this does not reset the recognisers. */
    public volatile double lostGraceMs = 150;

    // ---- rotate (volume) -----------------------------------------------------
    /** Ring buffer length for the index fingertip. */
    public volatile double rotWindowMs = 700;
    /** Minimum points in the buffer before rotation is judged. */
    public volatile double rotMinPoints = 5;
    /** Mean distance of the fingertip from the buffer centre. */
    public volatile double rotMinRadius = 0.05;
    /** Minor over major axis of the point cloud (1 is a circle, 0 a line).
     *  This is what separates circling from a swipe. */
    public volatile double rotMinAspect = 0.2;
    /** Accumulated angle per volume step. */
    public volatile double rotStepDeg = 50;
    /** Rate cap on volume steps. */
    public volatile double rotMaxStepsPerSec = 8;
    /** Rotation releases after this long without angular movement. */
    public volatile double rotReleaseMs = 400;
    /** Per-frame angle below this is jitter, not rotation. */
    public volatile double rotMinDeltaDeg = 2;
    /** Per-frame angle above this is a jump through the centre, ignored. */
    public volatile double rotMaxDeltaDeg = 90;

    // ---- swipe (next, previous) ---------------------------------------------
    /** Horizontal travel of the hand centre needed for a swipe. */
    public volatile double swipeMinDx = 0.30;
    /** ...within this time. */
    public volatile double swipeWindowMs = 450;
    /** Vertical travel must stay under this fraction of the horizontal. */
    public volatile double swipeMaxDyRatio = 0.5;
    /** Quiet period after a swipe. */
    public volatile double swipeCooldownMs = 600;

    // ---- fist hold (play, pause) --------------------------------------------
    /** How long a closed fist must be held. */
    public volatile double fistHoldMs = 500;
    /** The fist must stay this still (hand centre travel) while held. */
    public volatile double fistMaxTravel = 0.08;

    // ---- presence gate (camera pipeline, before the landmarker) ---------------
    /** Mean absolute luma difference (0..255) between successive idle samples
     *  of a 40x30 grid that counts as motion and wakes the landmarker. */
    public volatile double motionMinDiff = 6;

    // ---- keyed access for the settings screen and persistence ----------------

    /** One adjustable parameter: key, label, slider range and step. */
    public static final class Param {
        public final String key, label;
        public final double min, max, step;
        Param(String key, String label, double min, double max, double step) {
            this.key = key; this.label = label; this.min = min; this.max = max; this.step = step;
        }
    }

    public static final Param[] PARAMS = {
        new Param("minBoxHeight", "Arm: min hand height", 0.10, 0.60, 0.01),
        new Param("armMs", "Arm: present for ms", 0, 600, 10),
        new Param("lostGraceMs", "Arm: dropout grace ms", 0, 500, 10),
        new Param("rotWindowMs", "Rotate: window ms", 300, 1500, 50),
        new Param("rotMinPoints", "Rotate: min points", 3, 15, 1),
        new Param("rotMinRadius", "Rotate: min radius", 0.01, 0.20, 0.005),
        new Param("rotMinAspect", "Rotate: min roundness", 0.05, 0.80, 0.01),
        new Param("rotStepDeg", "Rotate: degrees per step", 20, 120, 5),
        new Param("rotMaxStepsPerSec", "Rotate: max steps per s", 2, 15, 1),
        new Param("rotReleaseMs", "Rotate: release ms", 100, 1500, 50),
        new Param("rotMinDeltaDeg", "Rotate: jitter floor deg", 0, 10, 0.5),
        new Param("rotMaxDeltaDeg", "Rotate: jump ceiling deg", 30, 170, 5),
        new Param("swipeMinDx", "Swipe: min travel", 0.10, 0.70, 0.01),
        new Param("swipeWindowMs", "Swipe: window ms", 150, 1000, 10),
        new Param("swipeMaxDyRatio", "Swipe: max vertical ratio", 0.1, 1.0, 0.05),
        new Param("swipeCooldownMs", "Swipe: cooldown ms", 0, 2000, 50),
        new Param("fistHoldMs", "Fist: hold ms", 150, 1500, 10),
        new Param("fistMaxTravel", "Fist: max travel", 0.02, 0.30, 0.01),
        new Param("motionMinDiff", "Gate: motion to wake", 0.5, 30, 0.5),
    };

    public double get(String key) {
        switch (key) {
            case "minBoxHeight": return minBoxHeight;
            case "armMs": return armMs;
            case "lostGraceMs": return lostGraceMs;
            case "rotWindowMs": return rotWindowMs;
            case "rotMinPoints": return rotMinPoints;
            case "rotMinRadius": return rotMinRadius;
            case "rotMinAspect": return rotMinAspect;
            case "rotStepDeg": return rotStepDeg;
            case "rotMaxStepsPerSec": return rotMaxStepsPerSec;
            case "rotReleaseMs": return rotReleaseMs;
            case "rotMinDeltaDeg": return rotMinDeltaDeg;
            case "rotMaxDeltaDeg": return rotMaxDeltaDeg;
            case "swipeMinDx": return swipeMinDx;
            case "swipeWindowMs": return swipeWindowMs;
            case "swipeMaxDyRatio": return swipeMaxDyRatio;
            case "swipeCooldownMs": return swipeCooldownMs;
            case "fistHoldMs": return fistHoldMs;
            case "fistMaxTravel": return fistMaxTravel;
            case "motionMinDiff": return motionMinDiff;
            default: throw new IllegalArgumentException("unknown tuning key " + key);
        }
    }

    public void set(String key, double v) {
        switch (key) {
            case "minBoxHeight": minBoxHeight = v; break;
            case "armMs": armMs = v; break;
            case "lostGraceMs": lostGraceMs = v; break;
            case "rotWindowMs": rotWindowMs = v; break;
            case "rotMinPoints": rotMinPoints = v; break;
            case "rotMinRadius": rotMinRadius = v; break;
            case "rotMinAspect": rotMinAspect = v; break;
            case "rotStepDeg": rotStepDeg = v; break;
            case "rotMaxStepsPerSec": rotMaxStepsPerSec = v; break;
            case "rotReleaseMs": rotReleaseMs = v; break;
            case "rotMinDeltaDeg": rotMinDeltaDeg = v; break;
            case "rotMaxDeltaDeg": rotMaxDeltaDeg = v; break;
            case "swipeMinDx": swipeMinDx = v; break;
            case "swipeWindowMs": swipeWindowMs = v; break;
            case "swipeMaxDyRatio": swipeMaxDyRatio = v; break;
            case "swipeCooldownMs": swipeCooldownMs = v; break;
            case "fistHoldMs": fistHoldMs = v; break;
            case "fistMaxTravel": fistMaxTravel = v; break;
            case "motionMinDiff": motionMinDiff = v; break;
            default: throw new IllegalArgumentException("unknown tuning key " + key);
        }
    }

    /** Restores every parameter to the design default. */
    public void reset() {
        Tuning d = new Tuning();
        for (Param p : PARAMS) set(p.key, d.get(p.key));
    }
}
