package com.abhi.thardeck.wave.engine;

/**
 * One analysed frame as the engine sees it.
 *
 * Coordinates are normalised to the driver's frame of reference: +x is the
 * driver's right, +y is down, both 0..1 of the analysed frame. The camera
 * adapter does the rotation and mirroring before building one of these; the
 * engine never sees raw camera coordinates.
 */
public final class HandFrame {
    public static final int N = 21;

    public final long t;
    public final boolean present;
    /** 21 MediaPipe hand landmarks, or null when no hand. */
    public final float[] x, y;
    /** Bounding box of the landmarks. */
    public final float boxMinX, boxMinY, boxMaxX, boxMaxY;

    private HandFrame(long t, boolean present, float[] x, float[] y,
                      float minX, float minY, float maxX, float maxY) {
        this.t = t; this.present = present; this.x = x; this.y = y;
        this.boxMinX = minX; this.boxMinY = minY; this.boxMaxX = maxX; this.boxMaxY = maxY;
    }

    public static HandFrame empty(long t) {
        return new HandFrame(t, false, null, null, 0, 0, 0, 0);
    }

    /** A hand, with its bounding box taken from the landmarks themselves. */
    public static HandFrame of(long t, float[] x, float[] y) {
        if (x == null || y == null || x.length < N || y.length < N) {
            throw new IllegalArgumentException("need 21 landmarks");
        }
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < N; i++) {
            minX = Math.min(minX, x[i]); maxX = Math.max(maxX, x[i]);
            minY = Math.min(minY, y[i]); maxY = Math.max(maxY, y[i]);
        }
        return new HandFrame(t, true, x, y, minX, minY, maxX, maxY);
    }

    public float boxHeight() { return present ? boxMaxY - boxMinY : 0f; }
}
