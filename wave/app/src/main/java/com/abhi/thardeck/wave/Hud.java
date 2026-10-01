package com.abhi.thardeck.wave;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import com.abhi.thardeck.wave.engine.Cmd;

/**
 * The driver's feedback, at the top centre of the screen, drawn over the
 * receiver app in an overlay window that never takes touch or focus.
 *
 * It is only attached while it has something to show, so there is no
 * persistent chrome over the projection. In order of priority:
 *   a command shows for 900 ms: an icon and a word for next, previous and
 *   play or pause, a round bubble with a plus or minus for a volume step
 *   a palm hold in progress shows a ring filling over the hold time, so the
 *   driver sees play or pause coming and can move the hand to cancel it
 *   while the engine is listening after a hand entered, a small dot
 * and nothing at all while the engine is dormant or no hand is there.
 *
 * The window alpha is 0.8, the ceiling Android 12 and later allow for an
 * overlay that touches pass through; above it the system would block touches
 * to the receiver under the pill.
 */
final class Hud {
    static final long FLASH_MS = 900;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WindowManager wm;
    private HudView view;
    private boolean attached;

    // what there is to show, main thread only
    private long flashUntil = 0;
    private Cmd flashCmd;
    private long listenUntil = 0;
    private float palmProgress = 0;
    // the preview's own state, so live engine frames cannot cut it short
    private long previewUntil = 0;
    private float previewPalm = 0;

    private final Runnable render = new Runnable() { @Override public void run() { render(); } };

    Hud(Context c) {
        ctx = c.getApplicationContext();
        wm = ctx.getSystemService(WindowManager.class);
    }

    /** A command fired. Any thread. */
    void command(final Cmd c) {
        main.post(new Runnable() { @Override public void run() {
            flashCmd = c;
            flashUntil = SystemClock.uptimeMillis() + FLASH_MS;
            Wave.log("hud: " + describe(c));
            render();
        }});
    }

    /** Engine state each analysed frame: listening until (uptime ms) and
     *  palm hold progress 0..1. Any thread; cheap when nothing changed. */
    void engineState(final long listeningUntil, final float palm) {
        if (listeningUntil == lastListen && Math.abs(palm - lastPalm) < 0.04f) return;
        lastListen = listeningUntil;
        lastPalm = palm;
        main.post(new Runnable() { @Override public void run() {
            listenUntil = listeningUntil;
            palmProgress = palm;
            render();
        }});
    }
    private volatile long lastListen = Long.MIN_VALUE;
    private volatile float lastPalm = -1;

    /**
     * Shows each HUD state in turn, for checking it on screen without a hand:
     * the listening dot for 1.5 s, a palm ring filling over 0.8 s, then the
     * play or pause flash. Display only; no command is made or sent.
     */
    void preview() {
        Wave.log("hud: preview (display only, nothing sent)");
        main.post(new Runnable() { @Override public void run() {
            previewUntil = SystemClock.uptimeMillis() + 2400;
            previewPalm = 0;
            render();
        }});
        for (int i = 1; i <= 8; i++) {
            final float p = i / 8f;
            main.postDelayed(new Runnable() { @Override public void run() {
                previewPalm = p;
                render();
            }}, 1500 + i * 100L);
        }
        main.postDelayed(new Runnable() { @Override public void run() {
            previewPalm = 0;
            previewUntil = 0;
            flashCmd = Cmd.PLAY_PAUSE;
            flashUntil = SystemClock.uptimeMillis() + FLASH_MS;
            render();
        }}, 2400);
    }

    void release() {
        main.post(new Runnable() { @Override public void run() {
            main.removeCallbacks(render);
            flashUntil = 0;
            listenUntil = 0;
            palmProgress = 0;
            previewUntil = 0;
            detach();
        }});
    }

    static String describe(Cmd c) {
        switch (c) {
            case NEXT: return "Next";
            case PREV: return "Previous";
            // The tablet cannot see the phone's player state, so the word
            // names the toggle rather than guessing which way it went.
            case PLAY_PAUSE: return "Play/Pause";
            case VOL_UP: return "Volume +";
            case VOL_DOWN: return "Volume -";
            default: return c.name();
        }
    }

    // ---- what to show now -------------------------------------------------------

    /** Main thread. Picks the highest priority thing to show, and wakes up
     *  again when that changes by the clock. */
    private void render() {
        main.removeCallbacks(render);
        long now = SystemClock.uptimeMillis();
        HudView v = ensureView();
        long next = Long.MAX_VALUE;
        if (now < flashUntil && flashCmd != null) {
            v.show(HudView.FLASH, flashCmd, 0);
            next = flashUntil;
        } else if (now < previewUntil) {
            v.show(previewPalm > 0 ? HudView.PALM : HudView.DOT, null, previewPalm);
            next = previewUntil;
        } else if (palmProgress > 0 && now < listenUntil) {
            v.show(HudView.PALM, null, palmProgress);
            next = listenUntil;
        } else if (now < listenUntil) {
            v.show(HudView.DOT, null, 0);
            next = listenUntil;
        } else {
            detach();
            return;
        }
        attach();
        if (next != Long.MAX_VALUE) main.postAtTime(render, next + 1);
    }

    // ---- window ----------------------------------------------------------------

    private HudView ensureView() {
        if (view == null) view = new HudView(ctx);
        return view;
    }

    private void attach() {
        if (attached) { view.invalidate(); return; }
        if (!Settings.canDrawOverlays(ctx)) {
            Wave.log("hud: overlay permission missing, not shown");
            return;
        }
        float d = ctx.getResources().getDisplayMetrics().density;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                (int) (230 * d), (int) (64 * d),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = (int) (14 * d);
        lp.alpha = 0.8f;
        lp.setTitle("ThardeckWaveHud");
        try {
            wm.addView(view, lp);
            attached = true;
        } catch (Throwable t) {
            Wave.log("hud: could not attach: " + t);
        }
    }

    private void detach() {
        if (!attached) return;
        try { wm.removeView(view); } catch (Throwable ignored) {}
        attached = false;
    }

    // ---- drawing ---------------------------------------------------------------

    static final class HudView extends View {
        static final int DOT = 1, PALM = 2, FLASH = 3;

        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path path = new Path();
        private final float d;

        private int mode;
        private Cmd cmd;
        private float progress;

        HudView(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            bg.setColor(Color.argb(235, 16, 20, 26));
            fg.setColor(Color.WHITE);
            fg.setStyle(Paint.Style.FILL);
            ring.setColor(Color.rgb(79, 195, 247));
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(4 * d);
            ring.setStrokeCap(Paint.Cap.ROUND);
            track.setColor(Color.argb(90, 255, 255, 255));
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(4 * d);
            dot.setColor(Color.rgb(79, 195, 247));
            text.setColor(Color.WHITE);
            text.setTextSize(22 * d);
            text.setFakeBoldText(true);
        }

        void show(int m, Cmd c, float p) {
            if (m == mode && c == cmd && Math.abs(p - progress) < 0.01f) return;
            mode = m; cmd = c; progress = p;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            if (mode == DOT) {
                // Listening: a small dot, a ring of dark behind it for contrast.
                c.drawCircle(cx, 10 * d, 8 * d, bg);
                c.drawCircle(cx, 10 * d, 5 * d, dot);
                return;
            }
            if (mode == PALM) {
                // A palm hold coming: a ring filling over the hold, with the
                // play or pause mark in the middle.
                float rad = h / 2f;
                c.drawCircle(cx, cy, rad, bg);
                float rr = rad - 8 * d;
                r.set(cx - rr, cy - rr, cx + rr, cy + rr);
                c.drawOval(r, track);
                c.drawArc(r, -90f, 360f * Math.max(0f, Math.min(1f, progress)), false, ring);
                float u = rr * 0.32f;
                path.reset();
                tri(cx - 1.6f * u, cy, u, true);
                c.drawPath(path, fg);
                c.drawRect(cx + 0.3f * u, cy - u, cx + 0.75f * u, cy + u, fg);
                c.drawRect(cx + 1.15f * u, cy - u, cx + 1.6f * u, cy + u, fg);
                return;
            }
            if (mode != FLASH || cmd == null) return;
            if (cmd == Cmd.VOL_UP || cmd == Cmd.VOL_DOWN) {
                // A round bubble, centred, with a ring and the sign.
                float rad = h / 2f;
                c.drawCircle(cx, cy, rad, bg);
                c.drawCircle(cx, cy, rad - 8 * d, ring);
                float s = 8 * d;
                c.drawRect(cx - s, cy - 1.5f * d, cx + s, cy + 1.5f * d, fg);
                if (cmd == Cmd.VOL_UP) c.drawRect(cx - 1.5f * d, cy - s, cx + 1.5f * d, cy + s, fg);
                return;
            }
            r.set(0, 0, w, h);
            c.drawRoundRect(r, h / 2f, h / 2f, bg);
            float ix = h * 0.55f, u = h * 0.16f;
            drawIcon(c, cmd, ix, cy, u);
            String label = describe(cmd);
            float tw = text.measureText(label);
            float left = h * 1.05f;
            float x = left + Math.max(0, (w - left - h * 0.4f - tw) / 2f);
            c.drawText(label, x, cy - (text.descent() + text.ascent()) / 2f, text);
        }

        private void drawIcon(Canvas c, Cmd which, float cx, float cy, float u) {
            path.reset();
            switch (which) {
                case NEXT:
                    tri(cx - 2 * u, cy, u, true);
                    tri(cx - 0.4f * u, cy, u, true);
                    c.drawPath(path, fg);
                    c.drawRect(cx + 1.3f * u, cy - u, cx + 1.8f * u, cy + u, fg);
                    break;
                case PREV:
                    tri(cx + 2 * u, cy, u, false);
                    tri(cx + 0.4f * u, cy, u, false);
                    c.drawPath(path, fg);
                    c.drawRect(cx - 1.8f * u, cy - u, cx - 1.3f * u, cy + u, fg);
                    break;
                case PLAY_PAUSE:
                    tri(cx - 1.9f * u, cy, u, true);
                    c.drawPath(path, fg);
                    c.drawRect(cx + 0.2f * u, cy - u, cx + 0.7f * u, cy + u, fg);
                    c.drawRect(cx + 1.2f * u, cy - u, cx + 1.7f * u, cy + u, fg);
                    break;
                default:
                    break;
            }
        }

        /** A triangle of height 2u pointing right (or left) from x. */
        private void tri(float x, float cy, float u, boolean right) {
            float tip = right ? x + 1.6f * u : x - 1.6f * u;
            path.moveTo(x, cy - u);
            path.lineTo(tip, cy);
            path.lineTo(x, cy + u);
            path.close();
        }
    }
}
