package com.durmuseminalbayrak.kamera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;

/** Belge düzenleyici: köşe ayarı (büyüteçli), perspektif düzeltme, filtreler, döndürme, JPG / PDF. */
final class DocEditor extends FrameLayout {

    interface Listener {
        void onDocJpeg(byte[] jpg);
        void onDocPdfPage(byte[] jpg, int w, int h);
        void onDocMessage(String msg);
    }

    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final EditView ev;
    private final TextView head, busyTv;
    private final LinearLayout chips, rowC, rowR;
    private final TextView[] chipViews = new TextView[4];
    private static final String[] FILTERS = {ImageTools.F_ENH, ImageTools.F_BW, ImageTools.F_GRAY, ImageTools.F_ORIG};
    private static final String[] FILTER_NAMES = {"Net renk", "Siyah-beyaz", "Gri", "Orijinal"};

    private Bitmap src, out;
    private int sw, sh;
    private float[][] quad, autoQuad;
    private int[] warped;
    private int ww, wh;
    private String filter = ImageTools.F_ENH;
    private boolean corners = true;
    private volatile boolean working = false;

    DocEditor(Context c, Listener l) {
        super(c);
        listener = l;
        setBackgroundColor(Color.BLACK);
        setClickable(true);
        setVisibility(GONE);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        head = new TextView(c);
        head.setTextColor(0xDDFFFFFF);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        head.setGravity(Gravity.CENTER);
        head.setPadding(dp(12), dp(14), dp(12), dp(8));
        col.addView(head, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        ev = new EditView(c);
        col.addView(ev, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        chips = row(c);
        for (int i = 0; i < 4; i++) {
            final String f = FILTERS[i];
            chipViews[i] = button(c, FILTER_NAMES[i], false);
            chipViews[i].setOnClickListener(v -> setFilter(f));
            chips.addView(chipViews[i], lp());
        }
        col.addView(chips);

        rowC = row(c);
        TextView bAuto = button(c, "Otomatik", false), bFull = button(c, "Tam kare", false), bCancel = button(c, "İptal", false), bNext = button(c, "Devam →", true);
        bAuto.setOnClickListener(v -> { if (autoQuad != null) { quad = copy(autoQuad); ev.invalidate(); } else listener.onDocMessage("Kenarlar otomatik bulunamadı"); });
        bFull.setOnClickListener(v -> { quad = new float[][]{{0, 0}, {sw, 0}, {sw, sh}, {0, sh}}; ev.invalidate(); });
        bCancel.setOnClickListener(v -> close());
        bNext.setOnClickListener(v -> next());
        for (TextView t : new TextView[]{bAuto, bFull, bCancel, bNext}) rowC.addView(t, lp());
        col.addView(rowC);

        rowR = row(c);
        TextView bRot = button(c, "⟳ Döndür", false), bBack = button(c, "← Köşeler", false), bJpg = button(c, "JPG kaydet", false), bPdf = button(c, "PDF'e ekle", true);
        bRot.setOnClickListener(v -> rotate());
        bBack.setOnClickListener(v -> { if (working) return; corners = true; updateUi(); });
        bJpg.setOnClickListener(v -> export(false));
        bPdf.setOnClickListener(v -> export(true));
        for (TextView t : new TextView[]{bRot, bBack, bJpg, bPdf}) rowR.addView(t, lp());
        col.addView(rowR);
        View spacer = new View(c);
        col.addView(spacer, new LinearLayout.LayoutParams(1, dp(18)));
        addView(col, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        busyTv = new TextView(c);
        busyTv.setTextColor(Color.WHITE);
        busyTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        busyTv.setPadding(dp(20), dp(12), dp(20), dp(12));
        GradientDrawable bd = new GradientDrawable();
        bd.setColor(0xCC000000);
        bd.setCornerRadius(dp(14));
        busyTv.setBackground(bd);
        busyTv.setVisibility(GONE);
        addView(busyTv, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private LinearLayout row(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER);
        r.setPadding(dp(6), dp(5), dp(6), dp(5));
        return r;
    }

    private LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        l.setMargins(dp(3), 0, dp(3), 0);
        return l;
    }

    private TextView button(Context c, String t, boolean primary) {
        TextView b = new TextView(c);
        b.setText(t);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setPadding(dp(4), dp(11), dp(4), dp(11));
        b.setTextColor(primary ? Color.BLACK : Color.WHITE);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(20));
        d.setColor(primary ? 0xFFFFD54A : 0x30FFFFFF);
        b.setBackground(d);
        b.setClickable(true);
        return b;
    }

    boolean isOpen() { return getVisibility() == VISIBLE; }

    /** Dik (döndürülmüş) kaynak görüntüyle açılır */
    void open(Bitmap upright) {
        src = upright;
        sw = src.getWidth();
        sh = src.getHeight();
        autoQuad = detect(src);
        quad = autoQuad != null ? copy(autoQuad) : full(0.04f);
        corners = true;
        filter = ImageTools.F_ENH;
        warped = null;
        out = null;
        setVisibility(VISIBLE);
        updateUi();
        if (autoQuad == null) listener.onDocMessage("Kenarlar bulunamadı, köşeleri elle ayarlayın");
    }

    void close() {
        setVisibility(GONE);
        busyTv.setVisibility(GONE);
        src = null;
        out = null;
        warped = null;
    }

    private float[][] full(float m) {
        return new float[][]{{sw * m, sh * m}, {sw * (1 - m), sh * m}, {sw * (1 - m), sh * (1 - m)}, {sw * m, sh * (1 - m)}};
    }

    private static float[][] copy(float[][] q) {
        float[][] r = new float[4][];
        for (int i = 0; i < 4; i++) r[i] = q[i].clone();
        return r;
    }

    private float[][] detect(Bitmap b) {
        try {
            int w = 480, h = Math.max(1, Math.round(480f * b.getHeight() / b.getWidth()));
            Bitmap s = Bitmap.createScaledBitmap(b, w, h, true);
            int[] px = new int[w * h];
            s.getPixels(px, 0, w, 0, 0, w, h);
            if (s != b) s.recycle();
            float[] q = ImageTools.detectDoc(ImageTools.gray(px), w, h);
            if (q == null) return null;
            float[][] r = new float[4][2];
            for (int k = 0; k < 4; k++) { r[k][0] = q[k * 2] * sw; r[k][1] = q[k * 2 + 1] * sh; }
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    private void updateUi() {
        head.setText(corners ? "Köşeleri belgenin kenarlarına sürükleyin" : "Filtre seçin ve kaydedin");
        rowC.setVisibility(corners ? VISIBLE : GONE);
        rowR.setVisibility(corners ? GONE : VISIBLE);
        chips.setVisibility(corners ? GONE : VISIBLE);
        for (int i = 0; i < 4; i++) {
            boolean sel = FILTERS[i].equals(filter);
            ((GradientDrawable) chipViews[i].getBackground()).setColor(sel ? 0xFFFFD54A : 0x30FFFFFF);
            chipViews[i].setTextColor(sel ? Color.BLACK : Color.WHITE);
        }
        ev.invalidate();
    }

    private void busy(String t) {
        working = t != null;
        busyTv.setText(t == null ? "" : t);
        busyTv.setVisibility(t == null ? GONE : VISIBLE);
    }

    private void next() {
        if (working || src == null) return;
        busy("Düzeltiliyor…");
        final float[][] q = ImageTools.orderQuad(copy(quad));
        final Bitmap s = src;
        new Thread(() -> {
            try {
                int[] px = new int[sw * sh];
                s.getPixels(px, 0, sw, 0, 0, sw, sh);
                int[] o = new int[2];
                int[] wp = ImageTools.warp(px, sw, sh, q, 3200, o);
                warped = wp;
                ww = o[0];
                wh = o[1];
                final Bitmap r = render();
                ui.post(() -> { out = r; corners = false; busy(null); updateUi(); });
            } catch (Throwable t) {
                ui.post(() -> { busy(null); listener.onDocMessage("Düzeltilemedi: " + t.getMessage()); });
            }
        }, "belge").start();
    }

    private Bitmap render() {
        int[] c = warped.clone();
        ImageTools.filter(c, ww, wh, filter);
        return Bitmap.createBitmap(c, ww, wh, Bitmap.Config.ARGB_8888);
    }

    private void setFilter(final String f) {
        if (working || warped == null) return;
        filter = f;
        updateUi();
        busy("Uygulanıyor…");
        new Thread(() -> {
            final Bitmap r = render();
            ui.post(() -> { out = r; busy(null); updateUi(); });
        }, "filtre").start();
    }

    private void rotate() {
        if (working || warped == null) return;
        busy("Döndürülüyor…");
        new Thread(() -> {
            warped = ImageTools.rot90(warped, ww, wh);
            int t = ww; ww = wh; wh = t;
            final Bitmap r = render();
            ui.post(() -> { out = r; busy(null); updateUi(); });
        }, "dondur").start();
    }

    private void export(final boolean pdf) {
        if (working || out == null) return;
        busy("Kaydediliyor…");
        final Bitmap b = out;
        new Thread(() -> {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, pdf ? 85 : 92, bo);
            final byte[] j = bo.toByteArray();
            ui.post(() -> {
                busy(null);
                if (pdf) listener.onDocPdfPage(j, b.getWidth(), b.getHeight());
                else listener.onDocJpeg(j);
                close();
            });
        }, "kaydet").start();
    }

    /** Görüntü + köşe tutamaçları + büyüteç */
    private final class EditView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private float k = 1, ox = 0, oy = 0;
        private int drag = -1;
        private float offX, offY;

        EditView(Context c) { super(c); }

        @Override
        protected void onDraw(Canvas c) {
            Bitmap img = corners ? src : out;
            if (img == null) return;
            float d = getResources().getDisplayMetrics().density;
            float pad = (corners ? 28 : 8) * d;
            int W = getWidth(), H = getHeight();
            k = Math.min((W - 2 * pad) / img.getWidth(), (H - 2 * pad) / img.getHeight());
            ox = (W - img.getWidth() * k) / 2;
            oy = (H - img.getHeight() * k) / 2;
            c.drawBitmap(img, null, new RectF(ox, oy, ox + img.getWidth() * k, oy + img.getHeight() * k), p);
            if (!corners || quad == null) return;
            Path path = new Path();
            for (int i = 0; i < 4; i++) {
                float x = ox + quad[i][0] * k, y = oy + quad[i][1] * k;
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            path.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x265DF58A);
            c.drawPath(path, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.5f * d);
            p.setColor(0xFF5DF58A);
            c.drawPath(path, p);
            for (int i = 0; i < 4; i++) {
                float x = ox + quad[i][0] * k, y = oy + quad[i][1] * k;
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x59FFD54A);
                c.drawCircle(x, y, (i == drag ? 18 : 13) * d, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2 * d);
                p.setColor(0xFFFFD54A);
                c.drawCircle(x, y, (i == drag ? 18 : 13) * d, p);
            }
            if (drag >= 0) {
                float R = 60 * d, zoom = 2.5f;
                float px = ox + quad[drag][0] * k;
                float mx = px < W / 2f ? W - R - 12 * d : R + 12 * d, my = R + 12 * d;
                float sz = 2 * R / (k * zoom);
                Rect srcR = new Rect(Math.round(quad[drag][0] - sz / 2), Math.round(quad[drag][1] - sz / 2), Math.round(quad[drag][0] + sz / 2), Math.round(quad[drag][1] + sz / 2));
                Path circle = new Path();
                circle.addCircle(mx, my, R, Path.Direction.CW);
                c.save();
                c.clipPath(circle);
                c.drawColor(Color.BLACK);
                c.drawBitmap(src, srcR, new RectF(mx - R, my - R, mx + R, my + R), p);
                c.restore();
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2 * d);
                p.setColor(Color.WHITE);
                c.drawCircle(mx, my, R, p);
                p.setColor(0xFF5DF58A);
                c.drawLine(mx - 10 * d, my, mx + 10 * d, my, p);
                c.drawLine(mx, my - 10 * d, mx, my + 10 * d, p);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!corners || quad == null || src == null) return true;
            float ix = (e.getX() - ox) / k, iy = (e.getY() - oy) / k;
            float d = getResources().getDisplayMetrics().density;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    int bi = -1;
                    float bd = Float.MAX_VALUE;
                    for (int i = 0; i < 4; i++) {
                        float dd = (float) Math.hypot(quad[i][0] - ix, quad[i][1] - iy) * k / d;
                        if (dd < bd) { bd = dd; bi = i; }
                    }
                    if (bd < 48) {
                        drag = bi;
                        offX = quad[bi][0] - ix;
                        offY = quad[bi][1] - iy;
                        invalidate();
                    }
                    return true;
                }
                case MotionEvent.ACTION_MOVE:
                    if (drag >= 0) {
                        quad[drag][0] = ImageTools.clampf(ix + offX, 0, sw);
                        quad[drag][1] = ImageTools.clampf(iy + offY, 0, sh);
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    drag = -1;
                    invalidate();
                    performClick();
                    return true;
                default:
                    return true;
            }
        }

        @Override
        public boolean performClick() { return super.performClick(); }
    }
}
