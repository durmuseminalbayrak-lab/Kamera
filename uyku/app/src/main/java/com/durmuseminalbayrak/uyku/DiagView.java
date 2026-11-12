package com.durmuseminalbayrak.uyku;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * Test ekranı tanı çizimi: (1) alınan spektrum 15–23 kHz, (2) yankı/gecikme profili (sabit ve
 * hareketli enerji), (3) seçilen hücrenin karmaşık yörüngesi (nefes = yay/çember).
 */
final class DiagView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), tp = new Paint(Paint.ANTI_ALIAS_FLAG), gp = new Paint();
    private final float dp;
    private Sonar so;

    DiagView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        tp.setColor(MainActivity.SUB);
        tp.setTextSize(10 * dp);
        gp.setColor(0x22FFFFFF);
        gp.setStrokeWidth(dp);
    }

    void set(Sonar s) {
        so = s;
        invalidate();
    }

    @Override
    protected void onMeasure(int wm, int hm) {
        setMeasuredDimension(MeasureSpec.getSize(wm), (int) (300 * dp));
    }

    @Override
    protected void onDraw(Canvas c) {
        Sonar s = so;
        if (s == null) return;
        float W = getWidth(), H = getHeight();
        float h1 = H * 0.36f, h2 = H * 0.36f;
        spectrum(c, s, 0, 0, W, h1);
        profile(c, s, 0, h1 + 4 * dp, W * 0.62f, h2 + H * 0.28f - 8 * dp);
        traj(c, s, W * 0.65f, h1 + 4 * dp, W, h2 + H * 0.28f - 8 * dp);
    }

    private void frame(Canvas c, float l, float t, float r, float b, String title) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp);
        p.setColor(0x33FFFFFF);
        c.drawRect(l, t, r, b, p);
        p.setStyle(Paint.Style.FILL);
        c.drawText(title, l + 4 * dp, t + 12 * dp, tp);
    }

    private void spectrum(Canvas c, Sonar s, float l, float t, float r, float b) {
        frame(c, l, t, r, b, "Alınan ses 15–23 kHz (mavi bant = gönderilen)");
        float[] sp = s.specDb;
        float top = t + 16 * dp, bot = b - 14 * dp;
        float f0 = 15000, f1 = 23000;
        float bx0 = l + ((Sonar.K0 - Sonar.L) * (float) Sonar.FRAME_HZ - f0) / (f1 - f0) * (r - l);
        float bx1 = l + ((Sonar.K0 + Sonar.L) * (float) Sonar.FRAME_HZ - f0) / (f1 - f0) * (r - l);
        p.setColor(0x223D5AFE);
        c.drawRect(bx0, top, bx1, bot, p);
        for (int k = 16; k <= 22; k += 2) {
            float x = l + (k * 1000 - f0) / (f1 - f0) * (r - l);
            c.drawLine(x, top, x, bot, gp);
            c.drawText(k + "k", x - 6 * dp, b - 3 * dp, tp);
        }
        if (sp == null) return;
        float lo = -130, hi = -10;
        Path path = new Path();
        for (int i = 0; i < sp.length; i++) {
            float x = l + i / (float) (sp.length - 1) * (r - l);
            float v = Math.max(lo, Math.min(hi, sp[i]));
            float y = bot - (v - lo) / (hi - lo) * (bot - top);
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f * dp);
        p.setColor(MainActivity.ACC);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void profile(Canvas c, Sonar s, float l, float t, float r, float b) {
        frame(c, l, t, r, b, "Yankılar: gri sabit, turuncu hareketli");
        float[] st = s.profStatDb, dy = s.profDynDb;
        int d0 = s.diagD0, best = s.bestTap;
        float top = t + 16 * dp, bot = b - 14 * dp;
        int cnt = 36;   // ~2 m
        for (int cm = 0; cm <= 200; cm += 50) {
            float x = l + (cm / 5.72f) / cnt * (r - l);
            c.drawLine(x, top, x, bot, gp);
            c.drawText(cm + "cm", x + 2 * dp, b - 3 * dp, tp);
        }
        if (st == null || dy == null || d0 < 0) return;
        float lo = -100, hi = 0;
        float bw = (r - l) / cnt;
        for (int k = 0; k < cnt; k++) {
            int d = (d0 + k) % Sonar.M;
            float x = l + k * bw;
            float ys = bot - (Math.max(lo, Math.min(hi, st[d])) - lo) / (hi - lo) * (bot - top);
            p.setColor(0x668C96B0);
            c.drawRect(x + bw * 0.1f, ys, x + bw * 0.5f, bot, p);
            float yd = bot - (Math.max(lo, Math.min(hi, dy[d])) - lo) / (hi - lo) * (bot - top);
            p.setColor(d == best ? MainActivity.GOOD : MainActivity.WARN);
            c.drawRect(x + bw * 0.5f, yd, x + bw * 0.9f, bot, p);
        }
    }

    private void traj(Canvas c, Sonar s, float l, float t, float r, float b) {
        frame(c, l, t, r, b, "Seçili yankı");
        float[] x = s.trajX, y = s.trajY;
        if (x == null || y == null || x.length < 3) {
            c.drawText("yok", (l + r) / 2 - 8 * dp, (t + b) / 2, tp);
            return;
        }
        float mx = 1e-20f;
        for (int i = 0; i < x.length; i++) mx = Math.max(mx, Math.max(Math.abs(x[i]), Math.abs(y[i])));
        float cx = (l + r) / 2, cy = (t + 16 * dp + b) / 2, rad = Math.min(r - l, b - t - 16 * dp) * 0.45f;
        c.drawLine(cx - rad, cy, cx + rad, cy, gp);
        c.drawLine(cx, cy - rad, cx, cy + rad, gp);
        Path path = new Path();
        for (int i = 0; i < x.length; i++) {
            float px = cx + x[i] / mx * rad, py = cy - y[i] / mx * rad;
            if (i == 0) path.moveTo(px, py);
            else path.lineTo(px, py);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.2f * dp);
        p.setColor(MainActivity.GOOD);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
    }
}
