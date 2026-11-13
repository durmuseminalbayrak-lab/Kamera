package com.durmuseminalbayrak.uyku;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.Calendar;

/** Rapor grafikleri: uyku evreleri, nefes hızı, hareket/horlama/gürültü. */
final class ChartView extends View {
    static final int STAGES = 0, RATE = 1, ACTIVITY = 2;
    static final int C_WAKE = 0xFFFFB86B, C_REM = 0xFFB794F6, C_LIGHT = 0xFF7AA2FF, C_DEEP = 0xFF3D5AFE, C_NO = 0xFF2A3147,
            C_UNK = 0xFF46557A;

    private final int mode;
    private final Night n;
    private final SleepAnalyzer.Report r;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), tp = new Paint(Paint.ANTI_ALIAS_FLAG), gp = new Paint();
    private final float dp;

    ChartView(Context c, int mode, Night n, SleepAnalyzer.Report r) {
        super(c);
        this.mode = mode;
        this.n = n;
        this.r = r;
        dp = c.getResources().getDisplayMetrics().density;
        tp.setColor(MainActivity.SUB);
        tp.setTextSize(11 * dp);
        gp.setColor(0x22FFFFFF);
        gp.setStrokeWidth(dp);
    }

    @Override
    protected void onMeasure(int wm, int hm) {
        int w = MeasureSpec.getSize(wm);
        int h = (int) ((mode == STAGES ? 150 : mode == RATE ? 130 : 110) * dp);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        int E = n.epochs.size();
        if (E == 0) return;
        float L = 52 * dp, R = getWidth() - 6 * dp, T = 6 * dp, B = getHeight() - 22 * dp;
        float ew = (R - L) / E;
        axis(c, L, R, B, E);
        if (mode == STAGES) {
            String[] names = {"Uyanık", "REM", "Hafif", "Derin"};
            int[] cols = {C_WAKE, C_REM, C_LIGHT, C_DEEP};
            float rh = (B - T) / 4f;
            for (int k = 0; k < 4; k++) {
                float y = T + rh * k + rh / 2;
                c.drawText(names[k], 4 * dp, y + 4 * dp, tp);
                c.drawLine(L, y, R, y, gp);
            }
            for (int i = 0; i < E; i++) {
                int s = r.stage[i];
                float x0 = L + i * ew, x1 = x0 + ew + 0.6f;
                if (s == SleepAnalyzer.NODATA) {
                    p.setColor(C_NO);
                    c.drawRect(x0, T, x1, B, p);
                    continue;
                }
                int row = s == SleepAnalyzer.WAKE ? 0 : s == SleepAnalyzer.REM ? 1
                        : s == SleepAnalyzer.LIGHT || s == SleepAnalyzer.UNK ? 2 : 3;
                p.setColor(s == SleepAnalyzer.UNK ? C_UNK : cols[row]);
                float y0 = T + rh * row + rh * 0.15f;
                c.drawRect(x0, y0, x1, y0 + rh * 0.7f, p);
            }
        } else if (mode == RATE) {
            float lo = 99, hi = 0;
            for (Night.Epoch e : n.epochs) if (e.rate == e.rate) { lo = Math.min(lo, e.rate); hi = Math.max(hi, e.rate); }
            if (hi < lo) { c.drawText("Nefes ölçülemedi", L, (T + B) / 2, tp); return; }
            lo = (float) Math.floor(Math.max(4, lo - 1));
            hi = (float) Math.ceil(Math.min(45, hi + 1));
            if (hi - lo < 4) { float m = (hi + lo) / 2; lo = m - 2; hi = m + 2; }
            int step = hi - lo > 12 ? 4 : 2;
            for (int v = (int) Math.ceil(lo / step) * step; v <= hi; v += step) {
                float y = B - (v - lo) / (hi - lo) * (B - T);
                c.drawLine(L, y, R, y, gp);
                c.drawText(String.valueOf(v), 6 * dp, y + 4 * dp, tp);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * dp);
            p.setColor(C_LIGHT);
            Path path = new Path();
            boolean pen = false;
            for (int i = 0; i < E; i++) {
                Night.Epoch e = n.epochs.get(i);
                if (e.rate != e.rate) { pen = false; continue; }
                float x = L + (i + 0.5f) * ew, y = B - (e.rate - lo) / (hi - lo) * (B - T);
                if (!pen) { path.moveTo(x, y); pen = true; }
                else path.lineTo(x, y);
            }
            c.drawPath(path, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFF6B81);
            for (Night.Event v : r.pauses) {
                float x = L + v.t / Night.EPOCH_SEC * ew;
                Path tri = new Path();
                tri.moveTo(x, B - 9 * dp);
                tri.lineTo(x - 5 * dp, B);
                tri.lineTo(x + 5 * dp, B);
                tri.close();
                c.drawPath(tri, p);
            }
        } else {
            float mid = T + (B - T) * 0.62f;
            c.drawText("Hareket", 4 * dp, T + (mid - T) / 2 + 4 * dp, tp);
            c.drawText("Horlama", 4 * dp, mid + (B - mid) / 2 + 4 * dp, tp);
            c.drawLine(L, mid, R, mid, gp);
            for (int i = 0; i < E; i++) {
                Night.Epoch e = n.epochs.get(i);
                float x0 = L + i * ew, x1 = x0 + Math.max(ew, 1.2f * dp);
                if (e.move > 0) {
                    p.setColor(C_WAKE);
                    float hgt = Math.min(1f, 0.25f + e.move / 20f) * (mid - T - 2 * dp);
                    c.drawRect(x0, mid - hgt, x1, mid, p);
                }
                if (e.snoresReg >= 2 || e.snores >= 4) {
                    p.setColor(C_REM);
                    float hgt = Math.min(1f, e.snores / 10f) * (B - mid - 3 * dp);
                    c.drawRect(x0, B - Math.max(hgt, 3 * dp), x1, B, p);
                }
            }
            p.setColor(0xFFFFE066);
            for (Night.Event v : n.events) {
                if (v.type != Night.Event.NOISE) continue;
                float x = L + v.t / Night.EPOCH_SEC * ew;
                c.drawCircle(x, T + 4 * dp, 3 * dp, p);
            }
        }
    }

    /** Saat çizgileri ve etiketleri. */
    private void axis(Canvas c, float L, float R, float B, int E) {
        long t0 = n.startMillis, t1 = t0 + (long) (E * Night.EPOCH_SEC * 1000);
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(t0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.HOUR_OF_DAY, 1);
        int hours = (int) ((t1 - t0) / 3_600_000L) + 1;
        int every = hours > 9 ? 2 : 1;
        int k = 0;
        while (cal.getTimeInMillis() < t1) {
            float x = L + (cal.getTimeInMillis() - t0) / (float) (t1 - t0) * (R - L);
            c.drawLine(x, B, x, B + 4 * dp, tp);
            if (k++ % every == 0) {
                String s = String.format("%02d:00", cal.get(Calendar.HOUR_OF_DAY));
                c.drawText(s, x - tp.measureText(s) / 2, B + 16 * dp, tp);
            }
            cal.add(Calendar.HOUR_OF_DAY, 1);
        }
        c.drawLine(L, B, R, B, gp);
    }

}
