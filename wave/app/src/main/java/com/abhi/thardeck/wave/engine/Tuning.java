package com.abhi.thardeck.wave.engine;

/**
 * Every threshold the gesture engine and the camera pipeline use, in one
 * place. Pure Java so the engine and its tests stay free of Android.
 *
 * The app screen edits these live (the service and the screen share one
 * instance), and persists them by key, so a calibration session in the car can
 * move a threshold without a rebuild.
 *
 * Distances are in normalised frame units (0..1 of the analysed frame), times
 * in milliseconds, angles in degrees, luma on the 0..255 scale.
 */
public final class Tuning {

    // ---- arming ------------------------------------------------------------
    /** Hand bounding box height, as a fraction of the frame, needed to arm.
     *  Keeps a hand resting on the wheel from triggering anything. */
    public volatile double minBoxHeight = 0.18;
    /** Once armed, the hand stays armed until its box drops below this. The
     *  gap to minBoxHeight is hysteresis: a real hand's box height wobbles as
     *  it moves, and one gate at a single value flapped on and off. */
    public volatile double minBoxHeightHold = 0.13;
    /** A hand must be present and big enough this long before it counts. */
    public volatile double armMs = 100;
    /** A tracking dropout shorter than this does not reset the recognisers. */
    public volatile double lostGraceMs = 400;

    // ---- hand tilt (volume) ---------------------------------------------------
    /** Flips which way of tilting is volume up. The sign is confirmed in the
     *  car; a switch, not a slider. */
    public volatile boolean tiltInvert = false;
    /** Ring buffer length for the tilt angle and hand centre. */
    public volatile double tiltWindowMs = 500;
    /** The hand centre must travel less than this over the buffer, so a
     *  swipe is never read as a tilt. */
    public volatile double tiltMaxTravel = 0.12;
    /** Per-frame tilt change below this is jitter. */
    public volatile double tiltMinDeltaDeg = 1;
    /** Per-frame tilt change above this is a tracking jump, ignored. */
    public volatile double tiltMaxDeltaDeg = 20;
    /** Accumulated tilt per volume step. */
    public volatile double tiltStepDeg = 25;
    /** Rate cap on volume steps. */
    public volatile double tiltMaxStepsPerSec = 8;
    /** Tilting releases after this long without movement. */
    public volatile double tiltReleaseMs = 400;

    // ---- swipe (next, previous) ---------------------------------------------
    /** Horizontal travel of the hand centre needed for a swipe. */
    public volatile double swipeMinDx = 0.14;
    /** ...within this time. */
    public volatile double swipeWindowMs = 450;
    /** Vertical travel must stay under this fraction of the horizontal. */
    public volatile double swipeMaxDyRatio = 0.6;
    /** Quiet period after a swipe. */
    public volatile double swipeCooldownMs = 600;

    // ---- open palm hold (play, pause) -----------------------------------------
    /** How long an open palm must be held still. */
    public volatile double palmHoldMs = 600;
    /** Hand centre travel allowed during the hold. */
    public volatile double palmMaxTravel = 0.04;
    /** Tilt allowed during the hold. */
    public volatile double palmMaxTiltDeg = 8;
    /** After a palm fires, it re-arms once the hand has not been an open palm
     *  for this long... */
    public volatile double palmReleaseMs = 300;
    /** ...or once no hand at all has been seen for this long. A size dropout
     *  or a brief loss of tracking re-arms nothing. */
    public volatile double palmUnlatchAbsentMs = 1500;

    // ---- camera pipeline --------------------------------------------------------
    /** Mean absolute luma difference (0..255) between successive idle samples
     *  of a 40x30 grid that counts as motion and wakes the landmarker. In night
     *  mode it is scaled down with the scene brightness. */
    public volatile double motionMinDiff = 3;
    /** Mean luma of the 40x30 sample below which night mode starts; it ends
     *  above this plus 15. */
    public volatile double darkLuma = 50;

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
        new Param("minBoxHeightHold", "Arm: hold while above height", 0.05, 0.60, 0.01),
        new Param("armMs", "Arm: present for ms", 0, 600, 10),
        new Param("lostGraceMs", "Arm: dropout grace ms", 0, 1000, 10),
        new Param("tiltWindowMs", "Tilt: window ms", 200, 1500, 50),
        new Param("tiltMaxTravel", "Tilt: max hand travel", 0.02, 0.40, 0.01),
        new Param("tiltMinDeltaDeg", "Tilt: jitter floor deg", 0, 10, 0.5),
        new Param("tiltMaxDeltaDeg", "Tilt: jump ceiling deg", 5, 90, 1),
        new Param("tiltStepDeg", "Tilt: degrees per step", 10, 90, 1),
        new Param("tiltMaxStepsPerSec", "Tilt: max steps per s", 2, 15, 1),
        new Param("tiltReleaseMs", "Tilt: release ms", 100, 1500, 50),
        new Param("swipeMinDx", "Swipe: min travel", 0.10, 0.70, 0.01),
        new Param("swipeWindowMs", "Swipe: window ms", 150, 1000, 10),
        new Param("swipeMaxDyRatio", "Swipe: max vertical ratio", 0.1, 1.0, 0.05),
        new Param("swipeCooldownMs", "Swipe: cooldown ms", 0, 2000, 50),
        new Param("palmHoldMs", "Palm: hold ms", 200, 2000, 10),
        new Param("palmMaxTravel", "Palm: max travel", 0.01, 0.20, 0.005),
        new Param("palmMaxTiltDeg", "Palm: max tilt deg", 1, 30, 0.5),
        new Param("palmReleaseMs", "Palm: re-arm after not palm ms", 50, 2000, 50),
        new Param("palmUnlatchAbsentMs", "Palm: re-arm after hand gone ms", 300, 5000, 100),
        new Param("motionMinDiff", "Gate: motion to wake", 0.5, 30, 0.5),
        new Param("darkLuma", "Light: night below luma", 0, 255, 1),
    };

    public double get(String key) {
        switch (key) {
            case "minBoxHeight": return minBoxHeight;
            case "minBoxHeightHold": return minBoxHeightHold;
            case "armMs": return armMs;
            case "lostGraceMs": return lostGraceMs;
            case "tiltWindowMs": return tiltWindowMs;
            case "tiltMaxTravel": return tiltMaxTravel;
            case "tiltMinDeltaDeg": return tiltMinDeltaDeg;
            case "tiltMaxDeltaDeg": return tiltMaxDeltaDeg;
            case "tiltStepDeg": return tiltStepDeg;
            case "tiltMaxStepsPerSec": return tiltMaxStepsPerSec;
            case "tiltReleaseMs": return tiltReleaseMs;
            case "swipeMinDx": return swipeMinDx;
            case "swipeWindowMs": return swipeWindowMs;
            case "swipeMaxDyRatio": return swipeMaxDyRatio;
            case "swipeCooldownMs": return swipeCooldownMs;
            case "palmHoldMs": return palmHoldMs;
            case "palmMaxTravel": return palmMaxTravel;
            case "palmMaxTiltDeg": return palmMaxTiltDeg;
            case "palmReleaseMs": return palmReleaseMs;
            case "palmUnlatchAbsentMs": return palmUnlatchAbsentMs;
            case "motionMinDiff": return motionMinDiff;
            case "darkLuma": return darkLuma;
            default: throw new IllegalArgumentException("unknown tuning key " + key);
        }
    }

    public void set(String key, double v) {
        switch (key) {
            case "minBoxHeight": minBoxHeight = v; break;
            case "minBoxHeightHold": minBoxHeightHold = v; break;
            case "armMs": armMs = v; break;
            case "lostGraceMs": lostGraceMs = v; break;
            case "tiltWindowMs": tiltWindowMs = v; break;
            case "tiltMaxTravel": tiltMaxTravel = v; break;
            case "tiltMinDeltaDeg": tiltMinDeltaDeg = v; break;
            case "tiltMaxDeltaDeg": tiltMaxDeltaDeg = v; break;
            case "tiltStepDeg": tiltStepDeg = v; break;
            case "tiltMaxStepsPerSec": tiltMaxStepsPerSec = v; break;
            case "tiltReleaseMs": tiltReleaseMs = v; break;
            case "swipeMinDx": swipeMinDx = v; break;
            case "swipeWindowMs": swipeWindowMs = v; break;
            case "swipeMaxDyRatio": swipeMaxDyRatio = v; break;
            case "swipeCooldownMs": swipeCooldownMs = v; break;
            case "palmHoldMs": palmHoldMs = v; break;
            case "palmMaxTravel": palmMaxTravel = v; break;
            case "palmMaxTiltDeg": palmMaxTiltDeg = v; break;
            case "palmReleaseMs": palmReleaseMs = v; break;
            case "palmUnlatchAbsentMs": palmUnlatchAbsentMs = v; break;
            case "motionMinDiff": motionMinDiff = v; break;
            case "darkLuma": darkLuma = v; break;
            default: throw new IllegalArgumentException("unknown tuning key " + key);
        }
    }

    /** True for a key this build knows; saved values for other keys are stale. */
    public static boolean isKey(String key) {
        for (Param p : PARAMS) if (p.key.equals(key)) return true;
        return false;
    }

    /** Restores every parameter, and the tilt direction, to the default. */
    public void reset() {
        Tuning d = new Tuning();
        for (Param p : PARAMS) set(p.key, d.get(p.key));
        tiltInvert = d.tiltInvert;
    }
}
