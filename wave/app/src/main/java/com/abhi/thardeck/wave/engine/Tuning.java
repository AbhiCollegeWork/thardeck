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

    // ---- deliberate entry --------------------------------------------------
    /** A hand counts as entering when it appears after being out of view at
     *  least this long. */
    public volatile double entryAbsentMs = 800;
    /** Commands may fire only within this long of an entry... */
    public volatile double entryWindowMs = 3000;
    /** ...extended to at least this long after each command fired. After the
     *  window the engine is dormant until the hand leaves again. */
    public volatile double entryExtendMs = 1500;

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

    // ---- vertical stroke (volume) ------------------------------------------
    /** Flips which way is volume up. A switch, not a slider, in case up and
     *  down need swapping in the car. */
    public volatile boolean strokeInvert = false;
    /** Once armed, strokes keep tracking the hand down to this box height:
     *  the hand shrinks and half leaves the frame at the bottom of a stroke. */
    public volatile double strokeMinBox = 0.08;
    /** Buffer of hand centres a stroke is measured over. */
    public volatile double strokeWindowMs = 500;
    /** Vertical travel of the hand centre that makes a stroke. */
    public volatile double strokeMinTravel = 0.08;
    /** Vertical travel must exceed this multiple of the horizontal. */
    public volatile double strokeVerticalRatio = 1.5;
    /** Quiet period after a stroke before the next can fire. */
    public volatile double strokeRefractoryMs = 300;
    /** A stroke does not fire while the hand box is bigger than this: that is
     *  a hand reaching to touch the screen. */
    public volatile double strokeMaxBox = 0.55;

    // ---- swipe (next, previous) ---------------------------------------------
    /** Horizontal travel of the hand centre needed for a swipe. */
    public volatile double swipeMinDx = 0.14;
    /** ...within this time. */
    public volatile double swipeWindowMs = 450;
    /** Vertical travel must stay under this fraction of the horizontal. */
    public volatile double swipeMaxDyRatio = 0.5;
    /** Quiet period after a swipe. */
    public volatile double swipeCooldownMs = 600;
    /** A swipe is measured only from points where the hand had already been
     *  in view, box above minBoxHeightHold, for this long. */
    public volatile double swipeMinPresentMs = 150;

    // ---- open palm hold (play, pause) -----------------------------------------
    /** How long an open palm must be held still. */
    public volatile double palmHoldMs = 800;
    /** The palm must be this close: box height at least this. A hand on the
     *  wheel reads smaller. */
    public volatile double palmMinBox = 0.30;
    /** The palm must face the camera: knuckle width (5 to 17) across the hand
     *  axis, over the wrist to middle-knuckle length, at least this. An
     *  edge-on or tilted-back hand reads narrower. */
    public volatile double palmMinWidth = 0.45;
    /** Hand centre travel allowed during the hold. A palm that moves more is
     *  a stroke or a swipe, never a hold. */
    public volatile double palmMaxTravel = 0.04;
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
        new Param("entryAbsentMs", "Entry: out of view first for ms", 200, 3000, 50),
        new Param("entryWindowMs", "Entry: listening window ms", 500, 10000, 100),
        new Param("entryExtendMs", "Entry: extend after a command ms", 0, 5000, 100),
        new Param("minBoxHeight", "Arm: min hand height", 0.10, 0.60, 0.01),
        new Param("minBoxHeightHold", "Arm: hold while above height", 0.05, 0.60, 0.01),
        new Param("armMs", "Arm: present for ms", 0, 600, 10),
        new Param("lostGraceMs", "Arm: dropout grace ms", 0, 1000, 10),
        new Param("strokeMinBox", "Stroke: track down to height", 0.03, 0.30, 0.01),
        new Param("strokeWindowMs", "Stroke: window ms", 200, 1500, 50),
        new Param("strokeMinTravel", "Stroke: min vertical travel", 0.02, 0.40, 0.01),
        new Param("strokeVerticalRatio", "Stroke: vertical over horizontal", 1.0, 4.0, 0.1),
        new Param("strokeRefractoryMs", "Stroke: quiet after a stroke ms", 0, 1500, 50),
        new Param("strokeMaxBox", "Stroke: max hand height", 0.20, 1.00, 0.01),
        new Param("swipeMinDx", "Swipe: min travel", 0.10, 0.70, 0.01),
        new Param("swipeWindowMs", "Swipe: window ms", 150, 1000, 10),
        new Param("swipeMaxDyRatio", "Swipe: max vertical ratio", 0.1, 1.0, 0.05),
        new Param("swipeCooldownMs", "Swipe: cooldown ms", 0, 2000, 50),
        new Param("swipeMinPresentMs", "Swipe: in view first for ms", 0, 1000, 10),
        new Param("palmHoldMs", "Palm: hold ms", 200, 2000, 10),
        new Param("palmMinBox", "Palm: min hand height", 0.10, 0.80, 0.01),
        new Param("palmMinWidth", "Palm: min width (flat to camera)", 0.10, 1.00, 0.01),
        new Param("palmMaxTravel", "Palm: max travel", 0.01, 0.20, 0.005),
        new Param("palmReleaseMs", "Palm: re-arm after not palm ms", 50, 2000, 50),
        new Param("palmUnlatchAbsentMs", "Palm: re-arm after hand gone ms", 300, 5000, 100),
        new Param("motionMinDiff", "Gate: motion to wake", 0.5, 30, 0.5),
        new Param("darkLuma", "Light: night below luma", 0, 255, 1),
    };

    public double get(String key) {
        switch (key) {
            case "entryAbsentMs": return entryAbsentMs;
            case "entryWindowMs": return entryWindowMs;
            case "entryExtendMs": return entryExtendMs;
            case "strokeMaxBox": return strokeMaxBox;
            case "swipeMinPresentMs": return swipeMinPresentMs;
            case "palmMinBox": return palmMinBox;
            case "palmMinWidth": return palmMinWidth;
            case "minBoxHeight": return minBoxHeight;
            case "minBoxHeightHold": return minBoxHeightHold;
            case "armMs": return armMs;
            case "lostGraceMs": return lostGraceMs;
            case "strokeMinBox": return strokeMinBox;
            case "strokeWindowMs": return strokeWindowMs;
            case "strokeMinTravel": return strokeMinTravel;
            case "strokeVerticalRatio": return strokeVerticalRatio;
            case "strokeRefractoryMs": return strokeRefractoryMs;
            case "swipeMinDx": return swipeMinDx;
            case "swipeWindowMs": return swipeWindowMs;
            case "swipeMaxDyRatio": return swipeMaxDyRatio;
            case "swipeCooldownMs": return swipeCooldownMs;
            case "palmHoldMs": return palmHoldMs;
            case "palmMaxTravel": return palmMaxTravel;
            case "palmReleaseMs": return palmReleaseMs;
            case "palmUnlatchAbsentMs": return palmUnlatchAbsentMs;
            case "motionMinDiff": return motionMinDiff;
            case "darkLuma": return darkLuma;
            default: throw new IllegalArgumentException("unknown tuning key " + key);
        }
    }

    public void set(String key, double v) {
        switch (key) {
            case "entryAbsentMs": entryAbsentMs = v; break;
            case "entryWindowMs": entryWindowMs = v; break;
            case "entryExtendMs": entryExtendMs = v; break;
            case "strokeMaxBox": strokeMaxBox = v; break;
            case "swipeMinPresentMs": swipeMinPresentMs = v; break;
            case "palmMinBox": palmMinBox = v; break;
            case "palmMinWidth": palmMinWidth = v; break;
            case "minBoxHeight": minBoxHeight = v; break;
            case "minBoxHeightHold": minBoxHeightHold = v; break;
            case "armMs": armMs = v; break;
            case "lostGraceMs": lostGraceMs = v; break;
            case "strokeMinBox": strokeMinBox = v; break;
            case "strokeWindowMs": strokeWindowMs = v; break;
            case "strokeMinTravel": strokeMinTravel = v; break;
            case "strokeVerticalRatio": strokeVerticalRatio = v; break;
            case "strokeRefractoryMs": strokeRefractoryMs = v; break;
            case "swipeMinDx": swipeMinDx = v; break;
            case "swipeWindowMs": swipeWindowMs = v; break;
            case "swipeMaxDyRatio": swipeMaxDyRatio = v; break;
            case "swipeCooldownMs": swipeCooldownMs = v; break;
            case "palmHoldMs": palmHoldMs = v; break;
            case "palmMaxTravel": palmMaxTravel = v; break;
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

    /** Restores every parameter, and the stroke direction, to the default. */
    public void reset() {
        Tuning d = new Tuning();
        for (Param p : PARAMS) set(p.key, d.get(p.key));
        strokeInvert = d.strokeInvert;
    }
}
