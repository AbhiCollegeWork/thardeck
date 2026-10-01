package com.abhi.thardeck.wave;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.view.Display;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.CameraState;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.lifecycle.Observer;

import com.abhi.thardeck.wave.engine.Cmd;
import com.abhi.thardeck.wave.engine.GestureEngine;
import com.abhi.thardeck.wave.engine.HandFrame;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.core.ErrorListener;
import com.google.mediapipe.tasks.core.OutputHandler;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Camera to landmarks to engine.
 *
 *   CameraX ImageAnalysis, front lens, 320x240, keep-only-latest
 *     -> throttle: at most 15 fps with a hand in the last two seconds, else 5
 *     -> presence gate: a 40x30 luma difference; with no hand recently and
 *        nothing moving, the frame stops here and the landmarker stays idle
 *     -> low light: the same sample's mean luma switches night mode (more
 *        exposure, a lower fps floor, a gamma lift, a scaled motion gate)
 *     -> rotate upright and mirror into the driver's frame (one place, below)
 *     -> MediaPipe HandLandmarker, LIVE_STREAM, one hand, GPU else CPU
 *     -> GestureEngine -> listener (sender and HUD)
 *
 * The throttle is the main lever for not costing the receiver app frames, so
 * it is applied before any conversion work: a skipped frame is closed at once.
 * The camera is also asked for a frame rate range that tops out at 15 where
 * the device offers one, so the sensor does not produce frames we would drop.
 *
 * Any camera or landmarker failure releases everything and retries after two
 * seconds, doubling up to thirty.
 */
final class Pipeline implements LifecycleOwner {

    interface Listener {
        void onCommand(Cmd c);
        /** Every analysed frame: listening window end (uptime ms) and palm
         *  hold progress 0..1, for the HUD. */
        void onEngineState(long listeningUntil, float palmProgress);
    }

    static final long HAND_INTERVAL_MS = 66;   // about 15 fps, never more
    static final long IDLE_INTERVAL_MS = 200;  // 5 fps
    static final long HAND_RECENT_MS = 2000;
    static final long STATS_MS = 5000;
    static final long WATCHDOG_MS = 10_000;
    static final long BACKOFF_MIN_MS = 2000, BACKOFF_MAX_MS = 30_000;

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LifecycleRegistry lifecycle = new LifecycleRegistry(this);
    private final GestureEngine engine;
    private final com.abhi.thardeck.wave.engine.Tuning tuning;

    private ExecutorService exec;
    private ProcessCameraProvider provider;
    private Camera camera;
    private ImageAnalysis analysis;
    private int boundRotation = -1;

    /**
     * The receiver app turns the screen to landscape when projection starts, so
     * the display rotation is not fixed. CameraX reports the rotation relative
     * to the target rotation it was given, so keep that in step; the next frame
     * then logs its new transform.
     */
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) {}
        @Override public void onDisplayRemoved(int id) {}
        @Override public void onDisplayChanged(int id) {
            if (id != Display.DEFAULT_DISPLAY || analysis == null) return;
            int rot = displayRotation();
            if (rot == boundRotation) return;
            Wave.log("display: rotation " + (boundRotation * 90) + " -> " + (rot * 90)
                    + ", updating camera target rotation");
            boundRotation = rot;
            analysis.setTargetRotation(rot);
        }
    };
    private volatile HandLandmarker landmarker;
    private boolean forceCpu = false;
    private volatile boolean gotResult = false;

    private boolean wanted = false;
    private long backoff = BACKOFF_MIN_MS;
    private volatile int generation = 0;

    // throttle, touched on the analysis thread and the result thread
    private volatile long lastSubmitSensorMs = Long.MIN_VALUE / 4;
    private volatile long lastHandMs = Long.MIN_VALUE / 4;
    private volatile boolean inFlight = false;
    private volatile long inFlightSince = 0;
    private volatile long lastFrameMs = 0;
    private volatile int lastRotation = -1;
    private volatile boolean sizeLogged = false;

    // stats, read and reset on the main thread every five seconds
    private volatile int camFrames, analysed, withHand;
    private volatile long inferSum;

    private CameraState.Type lastCamState;

    Pipeline(Context c, Listener l) {
        ctx = c.getApplicationContext();
        listener = l;
        tuning = Wave.tuning(ctx);
        engine = new GestureEngine(tuning);
        engine.setLog(new GestureEngine.Log() {
            @Override public void log(String line) { Wave.log(line); }
        });
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
    }

    @NonNull @Override public Lifecycle getLifecycle() { return lifecycle; }

    /** CameraX opens the camera while this owner is resumed, closes it below. */
    private void lifecycleUp() {
        if (lifecycle.getCurrentState() == Lifecycle.State.CREATED) {
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START);
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME);
        }
    }

    private void lifecycleDown() {
        if (lifecycle.getCurrentState() == Lifecycle.State.RESUMED) {
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE);
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP);
        }
    }

    // ---- control ---------------------------------------------------------------

    /** Main thread. */
    void start() {
        wanted = true;
        backoff = BACKOFF_MIN_MS;
        try {
            ctx.getSystemService(DisplayManager.class).registerDisplayListener(displayListener, main);
        } catch (Throwable t) { Wave.log("display: listener failed: " + t); }
        main.removeCallbacks(stats);
        main.postDelayed(stats, STATS_MS);
        open();
    }

    /** Main thread. Releases camera and model; the service keeps running. */
    void stop() {
        wanted = false;
        main.removeCallbacks(retry);
        main.removeCallbacks(stats);
        try {
            ctx.getSystemService(DisplayManager.class).unregisterDisplayListener(displayListener);
        } catch (Throwable ignored) {}
        release();
        Wave.cameraFps = 0; Wave.analysedFps = 0; Wave.inferMs = 0; Wave.handPresent = false;
    }

    private final Runnable retry = new Runnable() { @Override public void run() {
        if (wanted) open();
    }};

    /** Release everything and try again with backoff. Any thread. */
    void fail(final String why) {
        main.post(new Runnable() { @Override public void run() {
            if (!wanted) return;
            release();
            Wave.log("pipeline: " + why + ", retry in " + (backoff / 1000) + " s");
            Wave.serviceState = "retrying: " + why;
            main.removeCallbacks(retry);
            main.postDelayed(retry, backoff);
            backoff = Math.min(backoff * 2, BACKOFF_MAX_MS);
        }});
    }

    // ---- open ------------------------------------------------------------------

    private void open() {
        final int gen = ++generation;
        exec = Executors.newSingleThreadExecutor();
        lastFrameMs = SystemClock.uptimeMillis();
        Wave.serviceState = "starting";
        firstRunPending = true;
        gateOn = true;
        havePrevLuma = false;
        appliedRange = null;
        appliedEv = null;
        // The model loads off the main thread; the camera binds once it is ready.
        exec.execute(new Runnable() { @Override public void run() {
            HandLandmarker lm = createLandmarker();
            if (lm == null) { fail("landmarker did not initialise"); return; }
            if (gen != generation) { lm.close(); return; } // released meanwhile
            landmarker = lm;
            main.post(new Runnable() { @Override public void run() {
                if (gen != generation || !wanted) return;
                bindCamera(gen);
            }});
        }});
    }

    private HandLandmarker createLandmarker() {
        boolean cpuPref = "cpu".equals(Wave.delegatePref(ctx));
        if (cpuPref) Wave.log("landmarker: CPU delegate chosen in settings");
        if (!forceCpu && !cpuPref) {
            try {
                HandLandmarker lm = build(Delegate.GPU);
                Wave.delegate = "GPU";
                Wave.log("landmarker: initialised, delegate GPU");
                return lm;
            } catch (Throwable t) {
                Wave.log("landmarker: GPU delegate failed (" + t.getMessage() + "), falling back to CPU");
            }
        }
        try {
            HandLandmarker lm = build(Delegate.CPU);
            Wave.delegate = "CPU";
            Wave.log("landmarker: initialised, delegate CPU");
            return lm;
        } catch (Throwable t) {
            Wave.log("landmarker: CPU init failed: " + t);
            return null;
        }
    }

    private HandLandmarker build(Delegate d) {
        gotResult = false;
        BaseOptions base = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .setDelegate(d)
                .build();
        HandLandmarker.HandLandmarkerOptions opts = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener(new OutputHandler.ResultListener<HandLandmarkerResult, MPImage>() {
                    @Override public void run(HandLandmarkerResult r, MPImage in) { onResult(r); }
                })
                .setErrorListener(new ErrorListener() {
                    @Override public void onError(RuntimeException e) { onLandmarkerError(e); }
                })
                .build();
        return HandLandmarker.createFromOptions(ctx, opts);
    }

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private void bindCamera(final int gen) {
        final ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(ctx);
        f.addListener(new Runnable() { @Override public void run() {
            if (gen != generation || !wanted) return;
            try {
                provider = f.get();
                int displayRot = displayRotation();
                ResolutionSelector rs = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(new ResolutionStrategy(new Size(320, 240),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build();
                ImageAnalysis.Builder b = new ImageAnalysis.Builder()
                        .setResolutionSelector(rs)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetRotation(displayRot);
                Range<Integer>[] ranges = aeRanges();
                Range<Integer> fps = pickFpsRange(ranges);
                dayRange = fps;
                nightRange = pickNightRange(ranges, fps);
                Camera2Interop.Extender<ImageAnalysis> ext = new Camera2Interop.Extender<>(b);
                if (fps != null) {
                    ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
                }
                // Reports what the camera actually applied, not what was asked.
                ext.setSessionCaptureCallback(appliedWatcher);
                analysis = b.build();
                boundRotation = displayRot;
                analysis.setAnalyzer(exec, new ImageAnalysis.Analyzer() {
                    @Override public void analyze(@NonNull ImageProxy image) { onFrame(image); }
                });
                provider.unbindAll();
                lifecycleUp();
                camera = provider.bindToLifecycle(Pipeline.this,
                        CameraSelector.DEFAULT_FRONT_CAMERA, analysis);
                lastCamState = null;
                camera.getCameraInfo().getCameraState().observe(Pipeline.this, camObserver);
                Wave.log("camera: bound front lens, sensor orientation "
                        + camera.getCameraInfo().getSensorRotationDegrees(Surface.ROTATION_0)
                        + ", display rotation " + (displayRot * 90)
                        + ", fps range " + (fps == null ? "default" : fps.toString()));
                androidx.camera.core.ExposureState es = camera.getCameraInfo().getExposureState();
                Wave.log("camera: exposure compensation "
                        + (es.isExposureCompensationSupported()
                            ? "range " + es.getExposureCompensationRange() + " index, step "
                                + es.getExposureCompensationStep() + " EV"
                            : "not supported")
                        + "; AE fps ranges " + java.util.Arrays.toString(ranges)
                        + "; night range " + (nightRange == null ? "none lower than day" : nightRange));
                if (night) applyLight(); // a rebind while dark keeps night settings
                Wave.serviceState = "running";
                lastFrameMs = SystemClock.uptimeMillis();
            } catch (Throwable t) {
                fail("camera bind failed: " + t.getMessage());
            }
        }}, ctx.getMainExecutor());
    }

    private final Observer<CameraState> camObserver = new Observer<CameraState>() {
        @Override public void onChanged(CameraState s) {
            CameraState.StateError err = s.getError();
            if (s.getType() != lastCamState || err != null) {
                Wave.log("camera: state " + s.getType()
                        + (err == null ? "" : ", error code " + err.getCode() + " " + err.getType()));
                lastCamState = s.getType();
                Wave.cameraInfo = s.getType().toString().toLowerCase(java.util.Locale.ROOT);
            }
            if (err != null && err.getType() == CameraState.ErrorType.CRITICAL) {
                fail("camera critical error " + err.getCode());
            }
        }
    };

    /** The front camera's available AE target fps ranges, or null. */
    @SuppressWarnings("unchecked")
    private Range<Integer>[] aeRanges() {
        try {
            CameraManager cm = ctx.getSystemService(CameraManager.class);
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_FRONT) continue;
                return ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            }
        } catch (Throwable t) {
            Wave.log("camera: could not read fps ranges: " + t.getMessage());
        }
        return null;
    }

    /** Day: the lowest-ceiling AE range that still reaches 15 fps. */
    private static Range<Integer> pickFpsRange(Range<Integer>[] ranges) {
        if (ranges == null) return null;
        Range<Integer> best = null;
        for (Range<Integer> r : ranges) {
            if (r.getUpper() < 15) continue;
            if (best == null || r.getUpper() < best.getUpper()
                    || (r.getUpper().equals(best.getUpper()) && r.getLower() < best.getLower())) {
                best = r;
            }
        }
        return best;
    }

    /**
     * Night: [5, 15] if offered; otherwise the range with the lowest floor
     * (then the lowest ceiling), as long as its floor is below the day range's,
     * so auto exposure may lengthen the shutter. Null if nothing is lower.
     */
    private static Range<Integer> pickNightRange(Range<Integer>[] ranges, Range<Integer> day) {
        if (ranges == null) return null;
        Range<Integer> best = null;
        for (Range<Integer> r : ranges) {
            if (r.getLower() == 5 && r.getUpper() == 15) return r;
            if (best == null || r.getLower() < best.getLower()
                    || (r.getLower().equals(best.getLower()) && r.getUpper() < best.getUpper())) {
                best = r;
            }
        }
        if (best != null && day != null && best.getLower() >= day.getLower()) return null;
        return best;
    }

    // What the camera actually applied, logged whenever it changes.
    private volatile Range<Integer> appliedRange;
    private volatile Integer appliedEv;

    private final android.hardware.camera2.CameraCaptureSession.CaptureCallback appliedWatcher =
            new android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(@NonNull android.hardware.camera2.CameraCaptureSession s,
                @NonNull CaptureRequest req, @NonNull android.hardware.camera2.TotalCaptureResult res) {
            Range<Integer> r = res.get(android.hardware.camera2.CaptureResult.CONTROL_AE_TARGET_FPS_RANGE);
            Integer ev = res.get(android.hardware.camera2.CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION);
            Long exp = res.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME);
            if (exp != null) Wave.exposureMs = exp / 1_000_000f;
            boolean changed = (r != null && !r.equals(appliedRange))
                    || (ev != null && !ev.equals(appliedEv));
            if (!changed) return;
            appliedRange = r;
            appliedEv = ev;
            Wave.log("camera: applied AE range " + r + ", exposure compensation index " + ev
                    + ", exposure " + (exp == null ? "?" : String.format(java.util.Locale.ROOT,
                            "%.1f ms", exp / 1_000_000f)));
        }
    };

    private int displayRotation() {
        try {
            DisplayManager dm = ctx.getSystemService(DisplayManager.class);
            Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
            return d == null ? Surface.ROTATION_0 : d.getRotation();
        } catch (Throwable t) {
            return Surface.ROTATION_0;
        }
    }

    // ---- per frame -------------------------------------------------------------

    // ---- presence gate -----------------------------------------------------------

    static final int GATE_W = 40, GATE_H = 30;
    /** Luma grid of the previous gate sample; the only buffer the gate uses. */
    private final int[] prevLuma = new int[GATE_W * GATE_H];
    private boolean havePrevLuma = false;
    private volatile boolean gateOn = true;
    private volatile boolean firstRunPending = true;
    private volatile double motionSum, motionMax;
    private volatile int motionN;

    /**
     * Mean absolute difference (0..255) between this frame's luma, sampled on a
     * 40x30 grid straight from the Y plane, and the previous sample. Reads
     * the plane in place with absolute gets; allocates nothing.
     */
    private double motionDiff(ImageProxy image) {
        ImageProxy.PlaneProxy p = image.getPlanes()[0];
        java.nio.ByteBuffer b = p.getBuffer();
        int rs = p.getRowStride(), ps = p.getPixelStride();
        int w = image.getWidth(), h = image.getHeight();
        long sum = 0, sumLuma = 0;
        for (int gy = 0; gy < GATE_H; gy++) {
            int row = ((2 * gy + 1) * h / (2 * GATE_H)) * rs;
            for (int gx = 0; gx < GATE_W; gx++) {
                int v = b.get(row + ((2 * gx + 1) * w / (2 * GATE_W)) * ps) & 0xFF;
                int k = gy * GATE_W + gx;
                sum += Math.abs(v - prevLuma[k]);
                sumLuma += v;
                prevLuma[k] = v;
            }
        }
        lastLuma = (int) (sumLuma / (GATE_W * GATE_H));
        Wave.luma = lastLuma;
        if (!havePrevLuma) { havePrevLuma = true; return 0; }
        double d = sum / (double) (GATE_W * GATE_H);
        motionSum += d;
        motionN++;
        if (d > motionMax) motionMax = d;
        Wave.motion = (float) d;
        return d;
    }

    private void setGate(boolean on, String why) {
        Wave.gateOn = on;
        if (on == gateOn) return;
        gateOn = on;
        Wave.log("gate: " + why + ", landmarker " + (on ? "on" : "off"));
    }

    /** Analysis thread. */
    private void onFrame(ImageProxy image) {
        try {
            long now = SystemClock.uptimeMillis();
            lastFrameMs = now;
            camFrames++;
            HandLandmarker lm = landmarker;
            if (lm == null) return;

            if (!sizeLogged) {
                sizeLogged = true;
                Wave.log("camera: open, analysis stream " + image.getWidth() + "x" + image.getHeight());
                Wave.cameraInfo = "open " + image.getWidth() + "x" + image.getHeight();
            }

            // Throttle on the sensor clock, which is steadier than ours.
            long sensorMs = image.getImageInfo().getTimestamp() / 1_000_000L;
            long interval = (now - lastHandMs <= HAND_RECENT_MS) ? HAND_INTERVAL_MS : IDLE_INTERVAL_MS;
            if (sensorMs - lastSubmitSensorMs < interval) return;
            if (inFlight && now - inFlightSince < 1000) return;
            lastSubmitSensorMs = sensorMs;

            // Presence gate: the landmarker only runs when something moved, or a
            // hand was seen in the last two seconds (so tracking never drops out
            // mid-gesture), or on the first frame after a start (so a delegate
            // or model failure shows up at once, not at the first wave).
            double motion = motionDiff(image);
            updateLight(lastLuma);
            boolean handRecent = now - lastHandMs <= HAND_RECENT_MS;
            // In the dark, differences shrink with the signal, so the motion
            // threshold shrinks with the scene brightness too.
            double threshold = night
                    ? tuning.motionMinDiff * Math.max(0.35, lastLuma / 120.0)
                    : tuning.motionMinDiff;
            boolean moving = motion > threshold;
            boolean first = firstRunPending;
            boolean on = first || handRecent || moving || Wave.calibrating;
            setGate(on, first ? "first frame" : handRecent ? "hand" : moving ? "motion"
                    : Wave.calibrating ? "calibrating" : "still");
            if (!on) {
                if (Wave.snapRequested) {
                    Wave.snapRequested = false;
                    snap(toDriverFrame(image));
                }
                return;
            }
            firstRunPending = false;

            Bitmap frame = toDriverFrame(image);
            if (Wave.snapRequested) {
                Wave.snapRequested = false;
                snap(frame);
            }
            if (Wave.calibrating) Wave.calibFrame = frame;

            MPImage mp = new BitmapImageBuilder(frame).build();
            inFlight = true;
            inFlightSince = now;
            lm.detectAsync(mp, now);
        } catch (Throwable t) {
            inFlight = false;
            fail("analysis failed: " + t);
        } finally {
            image.close();
        }
    }

    /**
     * The one place raw camera pixels become the driver's frame.
     *
     * CameraX reports how far the buffer must turn clockwise to be upright for
     * the display's current rotation (the front sensor is mounted at 270). The
     * front image is what the camera sees looking at the driver, which is the
     * driver's own view mirrored, so after turning it upright it is flipped
     * left to right. In the result +x is the driver's right and +y is down,
     * which is what the engine expects.
     */
    private Bitmap toDriverFrame(ImageProxy image) {
        int rot = image.getImageInfo().getRotationDegrees();
        boolean lifted = night;
        Bitmap src = lifted ? liftedBitmap(image) : image.toBitmap();
        Matrix m = new Matrix();
        m.postRotate(rot);
        m.postScale(-1f, 1f);
        int sw = src.getWidth(), sh = src.getHeight();
        Bitmap out = Bitmap.createBitmap(src, 0, 0, sw, sh, m, false);
        if (out != src && !lifted) src.recycle(); // the lifted source is reused
        if (rot != lastRotation) {
            lastRotation = rot;
            String t = "rotate " + rot + " clockwise then mirror horizontally, "
                    + sw + "x" + sh + " -> " + out.getWidth() + "x" + out.getHeight();
            Wave.transform = t;
            Wave.log("transform: " + t + " (driver frame, +x is the driver's right)");
        }
        return out;
    }

    // ---- low light ------------------------------------------------------------------

    static final int DARK_HYSTERESIS = 15;
    /** Gamma 0.5 lift for the Y plane in night mode, precomputed. */
    private static final int[] GAMMA_LUT = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            GAMMA_LUT[i] = (int) Math.round(255.0 * Math.pow(i / 255.0, 0.5));
        }
    }
    private volatile boolean night = false;
    private volatile int lastLuma = 0;
    private int[] liftArgb;
    private Bitmap liftBitmap;
    private Range<Integer> dayRange, nightRange;

    /** Analysis thread. Night starts under darkLuma and ends above it plus 15. */
    private void updateLight(int luma) {
        boolean was = night;
        if (!night && luma < tuning.darkLuma) night = true;
        else if (night && luma > tuning.darkLuma + DARK_HYSTERESIS) night = false;
        Wave.night = night;
        if (night == was) return;
        Wave.log(night ? "light: night on, luma " + luma : "light: day, luma " + luma);
        main.post(new Runnable() { @Override public void run() { applyLight(); } });
    }

    /**
     * Main thread. Night: exposure compensation to the top of the device's
     * range and an AE range with a low floor, so the shutter may stay open
     * longer. Day: compensation 0 and the 15 fps range the camera was bound with.
     */
    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private void applyLight() {
        Camera cam = camera;
        if (cam == null) return;
        boolean n = night;
        try {
            androidx.camera.core.ExposureState es = cam.getCameraInfo().getExposureState();
            if (es.isExposureCompensationSupported()) {
                int idx = n ? es.getExposureCompensationRange().getUpper() : 0;
                cam.getCameraControl().setExposureCompensationIndex(idx);
                Wave.log("light: exposure compensation index " + idx);
            }
            Range<Integer> r = n ? nightRange : dayRange;
            if (r != null) {
                androidx.camera.camera2.interop.Camera2CameraControl.from(cam.getCameraControl())
                        .setCaptureRequestOptions(new androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, r)
                                .build());
                Wave.log("light: AE target fps range " + r);
            }
        } catch (Throwable t) {
            Wave.log("light: camera settings failed: " + t);
        }
    }

    /**
     * Night-mode conversion: the Y plane through the gamma LUT, then YUV to RGB
     * (BT.601 full range) into one reused pixel buffer and one reused bitmap.
     * The camera's own buffers are only read, never written.
     */
    private Bitmap liftedBitmap(ImageProxy image) {
        int w = image.getWidth(), h = image.getHeight();
        if (liftArgb == null || liftArgb.length != w * h) {
            liftArgb = new int[w * h];
            liftBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        ImageProxy.PlaneProxy[] pl = image.getPlanes();
        java.nio.ByteBuffer yb = pl[0].getBuffer(), ub = pl[1].getBuffer(), vb = pl[2].getBuffer();
        int yRs = pl[0].getRowStride(), yPs = pl[0].getPixelStride();
        int uRs = pl[1].getRowStride(), uPs = pl[1].getPixelStride();
        int vRs = pl[2].getRowStride(), vPs = pl[2].getPixelStride();
        int[] out = liftArgb;
        int i = 0;
        for (int y = 0; y < h; y++) {
            int yRow = y * yRs, uRow = (y >> 1) * uRs, vRow = (y >> 1) * vRs;
            for (int x = 0; x < w; x++) {
                int Y = GAMMA_LUT[yb.get(yRow + x * yPs) & 0xFF];
                int U = (ub.get(uRow + (x >> 1) * uPs) & 0xFF) - 128;
                int V = (vb.get(vRow + (x >> 1) * vPs) & 0xFF) - 128;
                int r = Y + ((1436 * V) >> 10);
                int g = Y - ((352 * U + 731 * V) >> 10);
                int b = Y + ((1815 * U) >> 10);
                r = r < 0 ? 0 : (r > 255 ? 255 : r);
                g = g < 0 ? 0 : (g > 255 ? 255 : g);
                b = b < 0 ? 0 : (b > 255 ? 255 : b);
                out[i++] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        liftBitmap.setPixels(out, 0, w, 0, 0, w, h);
        return liftBitmap;
    }

    private void snap(Bitmap frame) {
        File f = new File(ctx.getFilesDir(), "snap.png");
        try (FileOutputStream o = new FileOutputStream(f)) {
            frame.compress(Bitmap.CompressFormat.PNG, 100, o);
            Wave.log("snap: saved driver-frame image " + frame.getWidth() + "x" + frame.getHeight()
                    + " to files/snap.png");
        } catch (Throwable t) {
            Wave.log("snap: failed: " + t);
        }
    }

    /** MediaPipe result thread. */
    private void onResult(HandLandmarkerResult r) {
        long now = SystemClock.uptimeMillis();
        inFlight = false;
        gotResult = true;
        long ts = r.timestampMs();
        analysed++;
        inferSum += Math.max(0, now - ts);

        HandFrame f;
        List<List<NormalizedLandmark>> hands = r.landmarks();
        if (hands == null || hands.isEmpty() || hands.get(0).size() < HandFrame.N) {
            f = HandFrame.empty(ts);
            Wave.handPresent = false;
            Wave.calibLandmarks = null;
        } else {
            List<NormalizedLandmark> h = hands.get(0);
            float[] x = new float[HandFrame.N], y = new float[HandFrame.N];
            float[] xy = new float[HandFrame.N * 2];
            for (int i = 0; i < HandFrame.N; i++) {
                x[i] = h.get(i).x();
                y[i] = h.get(i).y();
                xy[2 * i] = x[i];
                xy[2 * i + 1] = y[i];
            }
            f = HandFrame.of(ts, x, y);
            withHand++;
            lastHandMs = now;
            Wave.handPresent = true;
            Wave.calibLandmarks = xy;
        }

        List<Cmd> cmds;
        long listenUntil;
        float palm;
        synchronized (engine) {
            cmds = engine.onFrame(f);
            Wave.engineLine = engine.debugLine();
            listenUntil = engine.listeningUntil();
            palm = (float) engine.palmProgress();
        }
        synchronized (trail) { updateTrail(f, now, cmds); }
        for (Cmd c : cmds) listener.onCommand(c);
        listener.onEngineState(listenUntil, palm);
    }

    // ---- calibrate view trail -------------------------------------------------------

    static final long TRAIL_MS = 500;
    /** {uptime ms, x, y} of the hand centre, result thread only. */
    private final java.util.ArrayDeque<float[]> trail = new java.util.ArrayDeque<>();

    /** Result thread, holding the trail lock. Keeps the last half second of
     *  hand centres for the calibrate view, and marks each stroke where it
     *  fired. */
    private void updateTrail(HandFrame f, long now, List<Cmd> cmds) {
        while (!trail.isEmpty() && now - trail.peekFirst()[0] > TRAIL_MS) trail.removeFirst();
        if (!f.present) {
            if (Wave.calibrating) Wave.calibTrail = flatten();
            return;
        }
        int[] idx = {0, 5, 9, 13, 17};
        float cx = 0, cy = 0;
        for (int i : idx) { cx += f.x[i]; cy += f.y[i]; }
        cx /= idx.length; cy /= idx.length;
        trail.addLast(new float[]{now, cx, cy});
        for (Cmd c : cmds) {
            if (c == Cmd.VOL_UP || c == Cmd.VOL_DOWN) {
                Wave.strokeMarkAt = now;
                Wave.strokeMark = new float[]{cx, cy, c == Cmd.VOL_UP ? 1 : -1};
            }
        }
        if (Wave.calibrating) Wave.calibTrail = flatten();
    }

    private float[] flatten() {
        float[] xy = new float[trail.size() * 2];
        int i = 0;
        for (float[] p : trail) { xy[i++] = p[1]; xy[i++] = p[2]; }
        return xy;
    }

    /** MediaPipe error thread. */
    private void onLandmarkerError(RuntimeException e) {
        inFlight = false;
        if ("GPU".equals(Wave.delegate) && !gotResult) {
            Wave.log("landmarker: GPU delegate failed on first frame (" + e.getMessage()
                    + "), switching to CPU");
            forceCpu = true;
        } else {
            Wave.log("landmarker: error " + e.getMessage());
        }
        fail("landmarker error");
    }

    // ---- stats and watchdog ------------------------------------------------------

    private final Runnable stats = new Runnable() { @Override public void run() {
        if (!wanted) return;
        int a = analysed, hnd = withHand, cf = camFrames, mn = motionN;
        long inf = inferSum;
        double mAvg = mn == 0 ? 0 : motionSum / mn, mMax = motionMax;
        analysed = 0; withHand = 0; camFrames = 0; inferSum = 0;
        motionSum = 0; motionMax = 0; motionN = 0;
        float secs = STATS_MS / 1000f;
        float fps = a / secs;
        int inferMs = a == 0 ? 0 : (int) (inf / a);
        int pct = a == 0 ? 0 : Math.round(100f * hnd / a);
        Wave.analysedFps = fps;
        Wave.cameraFps = cf / secs;
        Wave.inferMs = inferMs;
        Wave.handPct = pct;
        if (camera != null) {
            Wave.log(String.format(java.util.Locale.ROOT,
                    "stats: analysed=%d fps=%.1f infer_ms=%d hand=%d%% gate=%s motion=%.1f/%.1f light=%s luma=%d",
                    a, fps, inferMs, pct, gateOn ? "on" : "off", mAvg, mMax,
                    night ? "night" : "day", lastLuma));
            // Healthy means frames are flowing; with the gate off the
            // landmarker may legitimately analyse nothing.
            if (cf > 0) backoff = BACKOFF_MIN_MS;
            if (SystemClock.uptimeMillis() - lastFrameMs > WATCHDOG_MS) {
                fail("no camera frames for " + (WATCHDOG_MS / 1000) + " s");
            }
        }
        main.postDelayed(this, STATS_MS);
    }};

    // ---- release -----------------------------------------------------------------

    /** Main thread. */
    private void release() {
        generation++;
        try {
            if (camera != null) camera.getCameraInfo().getCameraState().removeObserver(camObserver);
        } catch (Throwable ignored) {}
        try { if (provider != null) provider.unbindAll(); } catch (Throwable ignored) {}
        camera = null;
        analysis = null;
        lifecycleDown();
        final HandLandmarker lm = landmarker;
        landmarker = null;
        final ExecutorService ex = exec;
        exec = null;
        if (ex != null) {
            // Close on the analysis thread, after any frame already in progress.
            ex.execute(new Runnable() { @Override public void run() {
                try { if (lm != null) lm.close(); } catch (Throwable ignored) {}
            }});
            ex.shutdown();
        } else if (lm != null) {
            try { lm.close(); } catch (Throwable ignored) {}
        }
        inFlight = false;
        sizeLogged = false;
        lastRotation = -1;
        synchronized (engine) { engine.reset(); }
        Wave.handPresent = false;
        Wave.cameraInfo = "closed";
        Wave.calibLandmarks = null;
        synchronized (trail) { trail.clear(); }
        Wave.calibTrail = null;
    }

    void destroy() {
        stop();
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
    }
}
