package com.abhi.thardeck.wave;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Calibration view: the frame the model actually saw, already in the driver's
 * frame (so it reads like a mirror), with the landmarks, the hand box, the
 * arming line for the size gate, and the engine's live state.
 *
 * It draws the analysed frames the service publishes rather than opening a
 * second camera stream, so calibrating costs nothing extra and shows exactly
 * what the engine works from.
 */
final class CalibView extends View {
    private static final int[][] BONES = {
        {0, 1}, {1, 2}, {2, 3}, {3, 4},
        {0, 5}, {5, 6}, {6, 7}, {7, 8},
        {5, 9}, {9, 10}, {10, 11}, {11, 12},
        {9, 13}, {13, 14}, {14, 15}, {15, 16},
        {13, 17}, {0, 17}, {17, 18}, {18, 19}, {19, 20},
    };

    private final Paint img = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bone = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint();
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private final float d;

    CalibView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        dot.setColor(Color.rgb(79, 195, 247));
        tip.setColor(Color.rgb(255, 202, 40));
        bone.setColor(Color.argb(200, 255, 255, 255));
        bone.setStrokeWidth(2 * d);
        box.setStyle(Paint.Style.STROKE);
        box.setStrokeWidth(2 * d);
        text.setColor(Color.WHITE);
        text.setTextSize(14 * d);
        shade.setColor(Color.argb(160, 0, 0, 0));
        trailPaint.setColor(Color.rgb(79, 195, 247));
        trailPaint.setStrokeWidth(3 * d);
        trailPaint.setStrokeCap(Paint.Cap.ROUND);
        markPaint.setStrokeWidth(5 * d);
        markPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, (int) (w * 0.75f));
    }

    @Override protected void onDraw(Canvas c) {
        c.drawColor(Color.BLACK);
        Bitmap b = Wave.calibFrame;
        float w = getWidth(), h = getHeight();
        if (b == null || b.isRecycled()) {
            c.drawText("No frames. Start the service; frames appear here while this view is open.",
                    8 * d, h / 2f, text);
            return;
        }
        float s = Math.min(w / b.getWidth(), h / b.getHeight());
        float bw = b.getWidth() * s, bh = b.getHeight() * s;
        float ox = (w - bw) / 2f, oy = (h - bh) / 2f;
        dst.set(ox, oy, ox + bw, oy + bh);
        c.drawBitmap(b, null, dst, img);

        float[] lm = Wave.calibLandmarks;
        if (lm != null && lm.length >= 42) {
            for (int[] e : BONES) {
                c.drawLine(ox + lm[2 * e[0]] * bw, oy + lm[2 * e[0] + 1] * bh,
                        ox + lm[2 * e[1]] * bw, oy + lm[2 * e[1] + 1] * bh, bone);
            }
            float minX = 1, minY = 1, maxX = 0, maxY = 0;
            for (int i = 0; i < 21; i++) {
                float x = lm[2 * i], y = lm[2 * i + 1];
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                c.drawCircle(ox + x * bw, oy + y * bh, (i == 8 ? 5 : 3) * d, i == 8 ? tip : dot);
            }
            // Green arms, amber holds an armed hand, red is too far away.
            float bh2 = maxY - minY;
            box.setColor(bh2 >= Wave.TUNING.minBoxHeight ? Color.rgb(102, 187, 106)
                    : bh2 >= Wave.TUNING.minBoxHeightHold ? Color.rgb(255, 202, 40)
                    : Color.rgb(239, 83, 80));
            c.drawRect(ox + minX * bw, oy + minY * bh, ox + maxX * bw, oy + maxY * bh, box);
        }

        // Hand centre over the last half second, and each stroke where it
        // fired: an arrow up or down, fading over a second.
        float[] tr = Wave.calibTrail;
        if (tr != null && tr.length >= 4) {
            for (int i = 2; i + 1 < tr.length; i += 2) {
                c.drawLine(ox + tr[i - 2] * bw, oy + tr[i - 1] * bh,
                        ox + tr[i] * bw, oy + tr[i + 1] * bh, trailPaint);
            }
        }
        float[] mk = Wave.strokeMark;
        long age = android.os.SystemClock.uptimeMillis() - Wave.strokeMarkAt;
        if (mk != null && age < 1000) {
            boolean up = mk[2] > 0;
            markPaint.setColor(up ? Color.rgb(102, 187, 106) : Color.rgb(255, 152, 0));
            markPaint.setAlpha((int) (255 * (1 - age / 1000f)));
            float mx = ox + mk[0] * bw, my = oy + mk[1] * bh, a = 18 * d, dir = up ? -1 : 1;
            c.drawLine(mx, my - dir * a, mx, my + dir * a, markPaint);
            c.drawLine(mx, my + dir * a, mx - a / 2, my + dir * a / 2, markPaint);
            c.drawLine(mx, my + dir * a, mx + a / 2, my + dir * a / 2, markPaint);
            c.drawText(up ? "Vol +" : "Vol -", mx + a * 0.7f, my, text);
        }

        // Size gate reference: a bar as tall as the minimum hand height.
        float gate = (float) Wave.TUNING.minBoxHeight * bh;
        box.setColor(Color.argb(160, 255, 255, 255));
        c.drawRect(ox + 6 * d, oy + bh - 6 * d - gate, ox + 12 * d, oy + bh - 6 * d, box);

        c.drawRect(ox, oy, ox + bw, oy + 24 * d, shade);
        c.drawText(Wave.engineLine, ox + 6 * d, oy + 17 * d, text);
        c.drawText("mirror view: your right is on the right   Light: "
                + (Wave.night ? "night" : "day") + " (luma " + Wave.luma + ")",
                ox + 18 * d, oy + bh - 8 * d, text);
    }
}
