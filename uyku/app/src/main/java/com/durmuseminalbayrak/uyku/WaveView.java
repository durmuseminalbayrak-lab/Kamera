package com.durmuseminalbayrak.uyku;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Son 30 sn'lik nefes dalgası. */
final class WaveView extends View {
    private volatile float[] w;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int color = MainActivity.ACC;

    WaveView(Context c) {
        super(c);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(MainActivity.dpf(c, 2.5f));
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        base.setColor(0x33FFFFFF);
        base.setStrokeWidth(MainActivity.dpf(c, 1));
    }

    void set(float[] a, int col) {
        w = a;
        color = col;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int W = getWidth(), H = getHeight();
        float mid = H / 2f;
        c.drawLine(0, mid, W, mid, base);
        float[] a = w;
        if (a == null || a.length < 2) return;
        line.setColor(color);
        path.reset();
        float amp = H * 0.28f;
        for (int i = 0; i < a.length; i++) {
            float x = W * i / (float) (a.length - 1);
            float y = mid - Math.max(-1.7f, Math.min(1.7f, a[i])) * amp;
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        c.drawPath(path, line);
    }
}
