package com.abhi.thardeck.wave.engine;

/**
 * Day or night, decided on the scene, not on the frame the camera made of it.
 *
 * The 9 Oct drive showed the old rule (frame luma under darkLuma) feeding
 * back on itself: night mode raises exposure compensation, the next frame is
 * bright, so night ends at once, the frame darkens, and round again, every
 * 1 to 2 s. Two measures stop that.
 *
 * Scene brightness. When the camera reports the exposure time and ISO it used
 * for a frame, the frame luma is converted back to the luma the same scene
 * would give at a fixed reference exposure (the day mode's longest exposure at
 * the highest ISO), undoing the camera's gamma first. Compensation changes the
 * exposure, not this number. Night starts when the scene reads under darkLuma
 * and ends when it reads over darkLuma + 15. At the reference exposure, which
 * is where auto exposure sits as it gets dark in day mode, the scene number
 * equals the frame luma, so darkLuma means what it always meant. Without the
 * metadata it falls back to the frame luma, and night then ends only when the
 * compensated frame reads over darkLumaExit.
 *
 * Timing. Each mode is held for at least nightMinDwellMs, and night is never
 * re-entered within nightReentryMs of leaving it, whichever path is in use.
 *
 * Pure Java, no Android, so the state machine is unit tested.
 */
public final class LightMode {

    /** Night ends when the scene reads this much over darkLuma. */
    public static final double EXIT_MARGIN = 15;
    /** Gamma of the camera's output, undone to compare exposures linearly. */
    static final double GAMMA = 2.2;

    private final Tuning tu;
    /** Exposure time (ms) times ISO the scene number is referred to; 0 when
     *  the device's limits are unknown, which forces the luma fallback. */
    private final double refProduct;

    private boolean night = false;
    private long changedAt = Long.MIN_VALUE / 4;
    private long leftNightAt = Long.MIN_VALUE / 4;
    private double lastScene = Double.NaN;
    private boolean lastUsedMetadata = false;

    /**
     * @param refExposureMs the day mode's longest exposure, ms
     * @param refIso        the highest ISO the sensor reports
     */
    public LightMode(Tuning tuning, double refExposureMs, double refIso) {
        this.tu = tuning;
        this.refProduct = refExposureMs > 0 && refIso > 0 ? refExposureMs * refIso : 0;
    }

    public boolean night() { return night; }
    /** The last scene number (or the frame luma on the fallback path). */
    public double scene() { return lastScene; }
    /** True if the last decision used the exposure metadata (path a). */
    public boolean usedMetadata() { return lastUsedMetadata; }

    /**
     * The luma this scene would give at the reference exposure.
     * NaN when the metadata or the reference is missing.
     */
    public double sceneLuma(double luma, double exposureMs, double iso) {
        if (refProduct <= 0 || !(exposureMs > 0) || !(iso > 0)) return Double.NaN;
        double linear = Math.pow(Math.max(0, luma) / 255.0, GAMMA) * refProduct / (exposureMs * iso);
        return 255.0 * Math.pow(linear, 1 / GAMMA);
    }

    /**
     * One sampled frame. exposureMs and iso are those the camera reported for
     * a recent frame, or NaN when it reports none.
     *
     * @return true when the mode changed on this frame.
     */
    public boolean update(long t, double luma, double exposureMs, double iso) {
        double scene = sceneLuma(luma, exposureMs, iso);
        boolean meta = !Double.isNaN(scene);
        lastUsedMetadata = meta;
        lastScene = meta ? scene : luma;
        if (t - changedAt < tu.nightMinDwellMs) return false;
        if (!night) {
            if (lastScene < tu.darkLuma && t - leftNightAt >= tu.nightReentryMs) {
                night = true;
                changedAt = t;
                return true;
            }
            return false;
        }
        boolean bright = meta ? scene > tu.darkLuma + EXIT_MARGIN : luma > tu.darkLumaExit;
        if (bright) {
            night = false;
            changedAt = t;
            leftNightAt = t;
            return true;
        }
        return false;
    }
}
