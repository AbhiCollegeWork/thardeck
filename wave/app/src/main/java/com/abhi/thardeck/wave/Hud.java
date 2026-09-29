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
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import com.abhi.thardeck.wave.engine.Cmd;

/**
 * The driver's feedback: a small pill at the top centre of the screen, drawn
 * over the receiver app in an overlay window that never takes touch or focus.
 *
 * It is only attached while it has something to show, so there is no
 * persistent chrome over the projection:
 *   next, previous and play or pause show an icon and a word for 900 ms
 *   a volume step shows a round bubble with a ring and a plus or minus
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

    private final Runnable hideRunnable = new Runnable() { @Override public void run() { detach(); } };

    Hud(Context c) {
        ctx = c.getApplicationContext();
        wm = ctx.getSystemService(WindowManager.class);
    }

    /** A command: icon and word, or the volume bubble for a step. */
    void command(final Cmd c) {
        main.post(new Runnable() { @Override public void run() {
            HudView v = ensureView();
            if (c == Cmd.VOL_UP || c == Cmd.VOL_DOWN) {
                v.showVolume(c == Cmd.VOL_UP);
            } else {
                v.showWord(c);
            }
            Wave.log("hud: " + describe(c));
            attach();
            main.removeCallbacks(hideRunnable);
            main.postDelayed(hideRunnable, FLASH_MS);
        }});
    }

    void release() {
        main.post(new Runnable() { @Override public void run() {
            main.removeCallbacks(hideRunnable);
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
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path path = new Path();
        private final float d;

        private boolean volumeMode;
        private boolean plus;
        private Cmd word;

        HudView(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            bg.setColor(Color.argb(235, 16, 20, 26));
            fg.setColor(Color.WHITE);
            fg.setStyle(Paint.Style.FILL);
            ring.setColor(Color.rgb(79, 195, 247));
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(4 * d);
            text.setColor(Color.WHITE);
            text.setTextSize(22 * d);
            text.setFakeBoldText(true);
        }

        void showVolume(boolean up) {
            volumeMode = true; plus = up;
            invalidate();
        }

        void showWord(Cmd c) {
            volumeMode = false; word = c;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (volumeMode) {
                // A round bubble, centred, with a ring and the sign.
                float rad = h / 2f;
                c.drawCircle(w / 2f, h / 2f, rad, bg);
                c.drawCircle(w / 2f, h / 2f, rad - 8 * d, ring);
                float s = 8 * d;
                c.drawRect(w / 2f - s, h / 2f - 1.5f * d, w / 2f + s, h / 2f + 1.5f * d, fg);
                if (plus) c.drawRect(w / 2f - 1.5f * d, h / 2f - s, w / 2f + 1.5f * d, h / 2f + s, fg);
                return;
            }
            if (word == null) return;
            r.set(0, 0, w, h);
            c.drawRoundRect(r, h / 2f, h / 2f, bg);
            float ix = h * 0.55f, iy = h / 2f, u = h * 0.16f;
            drawIcon(c, word, ix, iy, u);
            String label = describe(word);
            float tw = text.measureText(label);
            float left = h * 1.05f;
            float x = left + Math.max(0, (w - left - h * 0.4f - tw) / 2f);
            c.drawText(label, x, h / 2f - (text.descent() + text.ascent()) / 2f, text);
        }

        private void drawIcon(Canvas c, Cmd cmd, float cx, float cy, float u) {
            path.reset();
            switch (cmd) {
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
