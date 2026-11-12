package com.durmuseminalbayrak.kamera;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Range;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Kamera: Android'in kendi kamera sistemiyle (Camera2) çalışan basit, internetsiz kamera.
 * - Dokunarak odak: dokunulan nokta sensörün yönüne göre doğru koordinata çevrilir, odak kilitlenir.
 * - Gerçek fotoğraf çekimi (tam fotoğraf yolu), JPEG yönü telefonun tutuluşuna göre.
 * - Titreyen el için: en sakin anı bekler (en fazla ~0,35 sn), seri çekip en net kareyi kaydeder.
 * - Kısa pozlama: pozlama süresini sınırlar (titremede bulanıklığı azaltır).
 * - Fotoğraflar Galeri'de DCIM/Kamera klasörüne kaydedilir.
 */
public class MainActivity extends Activity implements SensorEventListener {

    private static final int REQ_CAMERA = 1;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread bgThread;
    private Handler bg;

    private SharedPreferences prefs;
    private CameraManager cm;
    private SensorManager sm;

    // Kamera durumu
    private String camId;
    private CameraCharacteristics ch;
    private CameraDevice dev;
    private CameraCaptureSession sess;
    private CaptureRequest.Builder pb;
    private ImageReader reader;
    private Surface previewSurface;
    private Size previewSize, jpegSize;
    private Rect active;
    private int sensorOri = 90;
    private int maxAfRegions, maxAeRegions;
    private boolean hasAfAuto, hasAfCont, hasTorch;
    private Range<Integer> fastFps;

    private volatile int afState = -1;
    private volatile Float focusDiopter, lastIsoF;
    private volatile Long lastExp;
    private volatile boolean afLocked = false;
    private MeteringRectangle[] lockRegions;
    private volatile float tapX = -1, tapY = -1;
    private long tapTime = 0;

    // Ayarlar
    private boolean torch = false, shortExp = true, maxRes = false;
    private int burst = 3;

    // Çekim
    private volatile boolean capturing = false;
    private final List<byte[]> shots = new ArrayList<>();
    private int expected = 0, failed = 0;
    private volatile int deviceOri = 0;
    private OrientationEventListener orient;
    private Uri lastUri;
    private long flashUntil = 0;

    // Sensörler
    private volatile float gyro = 0;
    private final float[] ghist = new float[150];
    private int gCount = 0, gIdx = 0;
    private volatile boolean hasGyro = false, hasAcc = false;
    private volatile float ax = 0, ay = 9.8f, az = 0;

    // Arayüz
    private TextureView tv;
    private Overlay ov;
    private ImageView thumb;
    private TextView bFlash, bBurst, bExp, bRes, afChip, info;
    private int screenW, topH, pvH;

    // ================== YAŞAM DÖNGÜSÜ ==================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prefs = getSharedPreferences("kamera", MODE_PRIVATE);
        burst = prefs.getInt("burst", 3);
        shortExp = prefs.getBoolean("shortExp", true);
        maxRes = prefs.getBoolean("maxRes", false);
        cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        buildUi();
        orient = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int o) {
                if (o == ORIENTATION_UNKNOWN) return;
                deviceOri = ((o + 45) / 90 * 90) % 360;
            }
        };
        tv.setSurfaceTextureListener(stl);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBg();
        if (orient.canDetectOrientation()) orient.enable();
        Sensor g = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if (g != null) { hasGyro = true; sm.registerListener(this, g, SensorManager.SENSOR_DELAY_GAME); }
        Sensor a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (a != null) { hasAcc = true; sm.registerListener(this, a, SensorManager.SENSOR_DELAY_UI); }
        if (tv.isAvailable()) openCamera();
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(tick);
        closeCamera();
        stopBg();
        orient.disable();
        sm.unregisterListener(this);
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_CAMERA) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            if (tv.isAvailable()) openCamera();
        } else {
            info.setText("Kamera izni verilmedi. Ayarlar → Uygulamalar → Kamera → İzinler'den izin verin.");
        }
    }

    private void startBg() {
        bgThread = new HandlerThread("kamera");
        bgThread.start();
        bg = new Handler(bgThread.getLooper());
    }

    private void stopBg() {
        if (bgThread == null) return;
        bgThread.quitSafely();
        try { bgThread.join(); } catch (InterruptedException ignored) { }
        bgThread = null;
        bg = null;
    }

    private final TextureView.SurfaceTextureListener stl = new TextureView.SurfaceTextureListener() {
        @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { openCamera(); }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { }
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture s) { }
    };

    // ================== ARAYÜZ ==================
    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView chip(String t) {
        TextView c = new TextView(this);
        c.setText(t);
        c.setTextColor(Color.WHITE);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        c.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(18));
        d.setColor(0x33FFFFFF);
        c.setBackground(d);
        c.setClickable(true);
        return c;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        screenW = getResources().getDisplayMetrics().widthPixels;
        topH = dp(56);
        pvH = screenW * 4 / 3;

        tv = new TextureView(this);
        root.addView(tv, previewLp());
        ov = new Overlay(this);
        root.addView(ov, previewLp());

        // Üst çubuk
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(6), 0, dp(6), 0);
        bFlash = chip("");
        bBurst = chip("");
        bExp = chip("");
        bRes = chip("");
        for (TextView c : new TextView[]{bFlash, bBurst, bExp, bRes}) {
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            l.setMargins(dp(4), 0, dp(4), 0);
            top.addView(c, l);
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(top);
        root.addView(hs, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, topH, Gravity.TOP));

        // Odak kilidi rozeti
        afChip = chip("Odak kilitli ✕");
        ((GradientDrawable) afChip.getBackground()).setColor(0xE6FFD54A);
        afChip.setTextColor(Color.BLACK);
        afChip.setVisibility(View.GONE);
        FrameLayout.LayoutParams al = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        al.topMargin = topH + dp(10);
        root.addView(afChip, al);

        // Bilgi satırı (önizlemenin altında)
        info = new TextView(this);
        info.setTextColor(0xCCFFFFFF);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        info.setPadding(dp(12), dp(4), dp(12), dp(4));
        FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        il.topMargin = topH + pvH;
        root.addView(info, il);

        // Alt çubuk: son fotoğraf + deklanşör
        FrameLayout bottom = new FrameLayout(this);
        thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable tb = new GradientDrawable();
        tb.setCornerRadius(dp(10));
        tb.setColor(0xFF222222);
        tb.setStroke(dp(1), 0x66FFFFFF);
        thumb.setBackground(tb);
        thumb.setClipToOutline(true);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER_VERTICAL | Gravity.START);
        tl.leftMargin = dp(24);
        bottom.addView(thumb, tl);
        ShutterView shutter = new ShutterView(this);
        bottom.addView(shutter, new FrameLayout.LayoutParams(dp(84), dp(84), Gravity.CENTER));
        root.addView(bottom, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(150), Gravity.BOTTOM));

        setContentView(root);
        refreshChips();

        bFlash.setOnClickListener(v -> {
            if (!hasTorch) { toast("Bu kamerada fener yok"); return; }
            torch = !torch;
            refreshChips();
            updatePreview();
        });
        bBurst.setOnClickListener(v -> {
            burst = burst == 1 ? 3 : burst == 3 ? 5 : 1;
            prefs.edit().putInt("burst", burst).apply();
            refreshChips();
        });
        bExp.setOnClickListener(v -> {
            shortExp = !shortExp;
            prefs.edit().putBoolean("shortExp", shortExp).apply();
            refreshChips();
            updatePreview();
            if (shortExp && fastFps == null) toast("Bu kamera kısa pozlama sınırını desteklemiyor");
        });
        bRes.setOnClickListener(v -> {
            maxRes = !maxRes;
            prefs.edit().putBoolean("maxRes", maxRes).apply();
            refreshChips();
            if (bg != null) { closeCamera(); openCamera(); }
        });
        afChip.setOnClickListener(v -> unlockAf());
        shutter.setOnClickListener(v -> capture());
        thumb.setOnClickListener(v -> {
            if (lastUri == null) return;
            try {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(lastUri, "image/jpeg");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(i);
            } catch (Exception e) { toast("Galeri açılamadı"); }
        });
    }

    private FrameLayout.LayoutParams previewLp() {
        FrameLayout.LayoutParams l = new FrameLayout.LayoutParams(screenW, pvH, Gravity.TOP);
        l.topMargin = topH;
        return l;
    }

    private void refreshChips() {
        bFlash.setText(torch ? "Fener: açık" : "Fener: kapalı");
        bBurst.setText(burst == 1 ? "Seri: kapalı" : "Seri: " + burst + " kare");
        bExp.setText(shortExp ? "Kısa poz: açık" : "Kısa poz: kapalı");
        bRes.setText(maxRes ? "Çözünürlük: maks" : "Çözünürlük: 12 MP");
    }

    private void toast(final String m) {
        ui.post(() -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show());
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            ov.invalidate();
            StringBuilder s = new StringBuilder();
            Float d = focusDiopter;
            if (d != null) s.append("Odak: ").append(d <= 0.05f ? "uzak" : String.format(Locale.US, "%.2f m", 1f / d));
            Long e = lastExp;
            if (e != null && e > 0) s.append("  ·  ").append(e >= 1_000_000_000L ? String.format(Locale.US, "%.1f sn", e / 1e9) : "1/" + Math.round(1e9 / e) + " sn");
            Float iso = lastIsoF;
            if (iso != null) s.append("  ·  ISO ").append(Math.round(iso));
            if (jpegSize != null) s.append("  ·  ").append(jpegSize.getWidth()).append("×").append(jpegSize.getHeight());
            if (dev != null) info.setText(s.toString());
            ui.postDelayed(this, 120);
        }
    };

    // ================== KAMERA ==================
    private static boolean contains(int[] a, int v) {
        if (a == null) return false;
        for (int x : a) if (x == v) return true;
        return false;
    }

    private static boolean is43(Size s) {
        return Math.abs((double) s.getWidth() / s.getHeight() - 4.0 / 3.0) < 0.02;
    }

    private static Size pickJpeg(Size[] sizes, boolean max) {
        Size[] s = sizes.clone();
        Arrays.sort(s, (a, b) -> Long.compare((long) b.getWidth() * b.getHeight(), (long) a.getWidth() * a.getHeight()));
        if (max) return s[0];
        for (Size z : s) if ((long) z.getWidth() * z.getHeight() <= 13_000_000L && is43(z)) return z;
        for (Size z : s) if ((long) z.getWidth() * z.getHeight() <= 13_000_000L) return z;
        return s[0];
    }

    private static Size pickPreview(Size[] sizes, Size jpeg) {
        double r = (double) jpeg.getWidth() / jpeg.getHeight();
        Size best = null;
        for (Size z : sizes) {
            if (z.getWidth() > 1920 || z.getHeight() > 1440) continue;
            if (Math.abs((double) z.getWidth() / z.getHeight() - r) > 0.02) continue;
            if (best == null || (long) z.getWidth() * z.getHeight() > (long) best.getWidth() * best.getHeight()) best = z;
        }
        if (best != null) return best;
        for (Size z : sizes) {
            if (z.getWidth() > 1920 || z.getHeight() > 1440) continue;
            if (best == null || (long) z.getWidth() * z.getHeight() > (long) best.getWidth() * best.getHeight()) best = z;
        }
        return best != null ? best : sizes[0];
    }

    private static Range<Integer> pickFastFps(Range<Integer>[] ranges) {
        // En yüksek alt sınırlı aralık (≤30): pozlama süresini 1/alt_sınır ile sınırlar
        if (ranges == null) return null;
        Range<Integer> best = null;
        for (Range<Integer> r : ranges) {
            int lo = r.getLower();
            if (lo < 24 || lo > 30) continue;
            if (best == null || lo > best.getLower() || (lo == best.getLower() && r.getUpper() < best.getUpper())) best = r;
        }
        return best;
    }

    private void openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        if (dev != null || bg == null) return;
        try {
            camId = null;
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                Integer f = c.get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == CameraCharacteristics.LENS_FACING_BACK) { camId = id; ch = c; break; }
            }
            if (camId == null) { toast("Arka kamera bulunamadı"); return; }
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOri = so == null ? 90 : so;
            Integer ma = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
            maxAfRegions = ma == null ? 0 : ma;
            Integer me = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
            maxAeRegions = me == null ? 0 : me;
            int[] afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            hasAfAuto = contains(afModes, CameraMetadata.CONTROL_AF_MODE_AUTO);
            hasAfCont = contains(afModes, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            Boolean fl = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            hasTorch = fl != null && fl;
            fastFps = pickFastFps(ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES));
            jpegSize = pickJpeg(map.getOutputSizes(ImageFormat.JPEG), maxRes);
            previewSize = pickPreview(map.getOutputSizes(SurfaceTexture.class), jpegSize);

            // Önizleme oranına göre görünümü ayarla (portre: genişlik × uzun/kısa)
            final int newH = Math.round(screenW * (float) previewSize.getWidth() / previewSize.getHeight());
            ui.post(() -> {
                if (newH != pvH) {
                    pvH = newH;
                    tv.setLayoutParams(previewLp());
                    ov.setLayoutParams(previewLp());
                    FrameLayout.LayoutParams il = (FrameLayout.LayoutParams) info.getLayoutParams();
                    il.topMargin = topH + pvH;
                    info.setLayoutParams(il);
                }
            });

            reader = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 7);
            reader.setOnImageAvailableListener(onJpeg, bg);
            cm.openCamera(camId, devCb, bg);
        } catch (SecurityException e) {
            toast("Kamera izni yok");
        } catch (Exception e) {
            toast("Kamera açılamadı: " + e.getMessage());
        }
    }

    private void closeCamera() {
        try { if (sess != null) sess.close(); } catch (Exception ignored) { }
        sess = null;
        try { if (dev != null) dev.close(); } catch (Exception ignored) { }
        dev = null;
        try { if (reader != null) reader.close(); } catch (Exception ignored) { }
        reader = null;
        if (previewSurface != null) { previewSurface.release(); previewSurface = null; }
        afLocked = false;
        capturing = false;
        ui.post(() -> afChip.setVisibility(View.GONE));
    }

    private final CameraDevice.StateCallback devCb = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice d) { dev = d; startPreview(); }
        @Override public void onDisconnected(CameraDevice d) { d.close(); dev = null; }
        @Override public void onError(CameraDevice d, int error) { d.close(); dev = null; toast("Kamera hatası: " + error); }
    };

    /** Önizleme ve çekim isteklerine ortak ayarlar */
    private void applyCommon(CaptureRequest.Builder b) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
        if (afLocked && hasAfAuto) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO);
            if (lockRegions != null && maxAfRegions > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, lockRegions);
            if (lockRegions != null && maxAeRegions > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, lockRegions);
        } else if (hasAfCont) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        } else if (hasAfAuto) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO);
        }
        b.set(CaptureRequest.FLASH_MODE, torch ? CameraMetadata.FLASH_MODE_TORCH : CameraMetadata.FLASH_MODE_OFF);
        if (shortExp && fastFps != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fastFps);
    }

    private void startPreview() {
        try {
            SurfaceTexture st = tv.getSurfaceTexture();
            if (st == null || dev == null) return;
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            previewSurface = new Surface(st);
            pb = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            pb.addTarget(previewSurface);
            applyCommon(pb);
            dev.createCaptureSession(Arrays.asList(previewSurface, reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    if (dev == null) return;
                    sess = s;
                    repeat();
                }
                @Override
                public void onConfigureFailed(CameraCaptureSession s) { toast("Kamera oturumu kurulamadı"); }
            }, bg);
        } catch (Exception e) {
            toast("Önizleme başlatılamadı: " + e.getMessage());
        }
    }

    private void repeat() {
        try { if (sess != null) sess.setRepeatingRequest(pb.build(), previewCb, bg); }
        catch (Exception e) { toast("Önizleme hatası: " + e.getMessage()); }
    }

    /** Önizleme isteğini sıfırdan kurup uygular (eski odak bölgeleri kalmaz) */
    private void updatePreview() {
        if (bg == null) return;
        bg.post(() -> {
            if (dev == null || sess == null) return;
            try {
                pb = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                pb.addTarget(previewSurface);
                applyCommon(pb);
                repeat();
            } catch (Exception e) { toast("Ayar uygulanamadı"); }
        });
    }

    private final CameraCaptureSession.CaptureCallback previewCb = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
            Integer a = res.get(CaptureResult.CONTROL_AF_STATE);
            if (a != null) afState = a;
            focusDiopter = res.get(CaptureResult.LENS_FOCUS_DISTANCE);
            lastExp = res.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Integer iso = res.get(CaptureResult.SENSOR_SENSITIVITY);
            lastIsoF = iso == null ? null : (float) iso;
        }
    };

    // ================== DOKUNARAK ODAK ==================
    /** Ekrandaki (portre) noktayı sensör koordinatına çevirir: sensör telefona göre döndürülmüş durur. */
    private float[] toSensor(float nx, float ny) {
        switch (sensorOri) {
            case 90: return new float[]{ny, 1f - nx};
            case 180: return new float[]{1f - nx, 1f - ny};
            case 270: return new float[]{1f - ny, nx};
            default: return new float[]{nx, ny};
        }
    }

    private void tapFocus(float nx, float ny) {
        if (sess == null || active == null || capturing || bg == null) return;
        tapX = nx;
        tapY = ny;
        tapTime = SystemClock.uptimeMillis();
        if (!hasAfAuto || (maxAfRegions == 0 && maxAeRegions == 0)) { toast("Bu kamera dokunarak odaklamayı desteklemiyor"); return; }
        float[] s = toSensor(nx, ny);
        int half = Math.round(Math.min(active.width(), active.height()) * 0.07f);
        int cx = active.left + Math.round(s[0] * active.width());
        int cy = active.top + Math.round(s[1] * active.height());
        cx = Math.max(active.left + half, Math.min(active.right - half, cx));
        cy = Math.max(active.top + half, Math.min(active.bottom - half, cy));
        lockRegions = new MeteringRectangle[]{new MeteringRectangle(new Rect(cx - half, cy - half, cx + half, cy + half), MeteringRectangle.METERING_WEIGHT_MAX - 1)};
        afLocked = true;
        ui.post(() -> afChip.setVisibility(View.VISIBLE));
        bg.post(() -> {
            if (sess == null || dev == null) return;
            try {
                // Önceki odak işlemini iptal et
                pb.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
                sess.capture(pb.build(), null, bg);
                // Yeni bölgeyle tek seferlik odak başlat; odak bu noktada kilitli kalır
                pb = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                pb.addTarget(previewSurface);
                applyCommon(pb);
                pb.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
                sess.setRepeatingRequest(pb.build(), previewCb, bg);
                pb.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
                sess.capture(pb.build(), previewCb, bg);
                pb.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            } catch (Exception e) { toast("Odaklanamadı: " + e.getMessage()); }
        });
    }

    private void unlockAf() {
        afLocked = false;
        lockRegions = null;
        tapX = -1;
        afChip.setVisibility(View.GONE);
        if (bg == null) return;
        bg.post(() -> {
            if (sess == null || dev == null) return;
            try {
                pb.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
                sess.capture(pb.build(), null, bg);
            } catch (Exception ignored) { }
        });
        updatePreview();
    }

    // ================== ÇEKİM ==================
    private float steadyTarget() {
        if (gCount < 20) return 4f;
        float[] a = Arrays.copyOf(ghist, gCount);
        Arrays.sort(a);
        return Math.max(1.5f, Math.min(12f, a[(int) (a.length * 0.25f)]));
    }

    private void capture() {
        if (sess == null || capturing || dev == null) return;
        capturing = true;
        new Thread(() -> {
            // 1) En sakin anı bekle (en fazla 350 ms)
            if (hasGyro) {
                float t = steadyTarget();
                long t0 = SystemClock.uptimeMillis();
                while (SystemClock.uptimeMillis() - t0 < 350 && gyro > t) SystemClock.sleep(5);
            }
            // 2) Sürekli odak o an arıyorsa kısa bekle (en fazla 500 ms)
            if (!afLocked) {
                long t0 = SystemClock.uptimeMillis();
                while (SystemClock.uptimeMillis() - t0 < 500 && (afState == CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN || afState == CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN)) SystemClock.sleep(15);
            }
            shoot();
        }, "cekim").start();
    }

    private int jpegOrientation() {
        return (sensorOri + deviceOri + 360) % 360;
    }

    private void shoot() {
        try {
            int n = Math.max(1, burst);
            synchronized (shots) { shots.clear(); expected = n; failed = 0; }
            List<CaptureRequest> reqs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                CaptureRequest.Builder sb = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                sb.addTarget(reader.getSurface());
                applyCommon(sb);
                sb.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation());
                sb.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
                reqs.add(sb.build());
            }
            flashUntil = SystemClock.uptimeMillis() + 120;
            if (n == 1) sess.capture(reqs.get(0), stillCb, bg);
            else sess.captureBurst(reqs, stillCb, bg);
        } catch (Exception e) {
            capturing = false;
            toast("Çekilemedi: " + e.getMessage());
        }
    }

    private final CameraCaptureSession.CaptureCallback stillCb = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure f) {
            List<byte[]> done = null;
            synchronized (shots) {
                failed++;
                if (shots.size() + failed >= expected) { done = new ArrayList<>(shots); shots.clear(); }
            }
            if (done != null) finishAsync(done);
        }
    };

    private final ImageReader.OnImageAvailableListener onJpeg = r -> {
        Image img = null;
        List<byte[]> done = null;
        try {
            img = r.acquireNextImage();
            if (img == null) return;
            ByteBuffer b = img.getPlanes()[0].getBuffer();
            byte[] d = new byte[b.remaining()];
            b.get(d);
            synchronized (shots) {
                shots.add(d);
                if (shots.size() + failed >= expected) { done = new ArrayList<>(shots); shots.clear(); }
            }
        } catch (Exception e) {
            toast("Görüntü alınamadı: " + e.getMessage());
        } finally {
            if (img != null) img.close();
        }
        if (done != null) finishAsync(done);
    };

    private void finishAsync(final List<byte[]> list) {
        new Thread(() -> {
            try {
                if (list.isEmpty()) { toast("Çekim başarısız"); return; }
                byte[] best = list.get(0);
                if (list.size() > 1) {
                    double bs = -1;
                    for (byte[] j : list) {
                        double s = sharpness(j);
                        if (s > bs) { bs = s; best = j; }
                    }
                }
                Uri u = save(best);
                if (u != null) {
                    lastUri = u;
                    final Bitmap t = thumbnail(best);
                    final int n = list.size();
                    ui.post(() -> {
                        if (t != null) thumb.setImageBitmap(t);
                        Toast.makeText(this, n > 1 ? "Kaydedildi · " + n + " kareden en neti" : "Kaydedildi", Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                toast("Kaydedilemedi: " + e.getMessage());
            } finally {
                capturing = false;
            }
        }, "kayit").start();
    }

    /** Merkezdeki bölgenin netliği (Laplace varyansı), küçültülmüş görüntüde */
    static double sharpness(byte[] jpg) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
        int s = 1;
        while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= 1000) s *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = s;
        Bitmap bm = BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o2);
        if (bm == null) return 0;
        int w = bm.getWidth(), h = bm.getHeight();
        int cw = w * 6 / 10, chh = h * 6 / 10;
        int[] px = new int[cw * chh];
        bm.getPixels(px, 0, cw, (w - cw) / 2, (h - chh) / 2, cw, chh);
        bm.recycle();
        int[] g = new int[px.length];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            g[i] = (((c >> 16) & 255) * 77 + ((c >> 8) & 255) * 150 + (c & 255) * 29) >> 8;
        }
        double sum = 0, sum2 = 0;
        long n = 0;
        for (int y = 1; y < chh - 1; y++) {
            for (int x = 1; x < cw - 1; x++) {
                int i = y * cw + x;
                int l = 4 * g[i] - g[i - 1] - g[i + 1] - g[i - cw] - g[i + cw];
                sum += l;
                sum2 += (double) l * l;
                n++;
            }
        }
        if (n == 0) return 0;
        double m = sum / n;
        return sum2 / n - m * m;
    }

    private Uri save(byte[] jpg) throws Exception {
        String name = "IMG_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Kamera");
        cv.put(MediaStore.Images.Media.IS_PENDING, 1);
        ContentResolver cr = getContentResolver();
        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new Exception("Galeri kaydı oluşturulamadı");
        try (OutputStream os = cr.openOutputStream(uri)) {
            if (os == null) throw new Exception("Dosya açılamadı");
            os.write(jpg);
        }
        cv.clear();
        cv.put(MediaStore.Images.Media.IS_PENDING, 0);
        cr.update(uri, cv, null, null);
        return uri;
    }

    private static Bitmap thumbnail(byte[] jpg) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= 240) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            Bitmap bm = BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o2);
            if (bm == null) return null;
            int rot = 0;
            ExifInterface ex = new ExifInterface(new ByteArrayInputStream(jpg));
            int or = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (or == ExifInterface.ORIENTATION_ROTATE_90) rot = 90;
            else if (or == ExifInterface.ORIENTATION_ROTATE_180) rot = 180;
            else if (or == ExifInterface.ORIENTATION_ROTATE_270) rot = 270;
            if (rot == 0) return bm;
            Matrix m = new Matrix();
            m.postRotate(rot);
            Bitmap r = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            if (r != bm) bm.recycle();
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    // ================== SENSÖRLER ==================
    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            float m = (float) Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) * 57.29578f;
            gyro = gyro * 0.75f + m * 0.25f;
            ghist[gIdx] = gyro;
            gIdx = (gIdx + 1) % ghist.length;
            if (gCount < ghist.length) gCount++;
        } else if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            ax += (e.values[0] - ax) * 0.25f;
            ay += (e.values[1] - ay) * 0.25f;
            az += (e.values[2] - az) * 0.25f;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    // ================== ÇİZİM KATMANI ==================
    private class Overlay extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Overlay(Context c) { super(c); }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_UP) {
                tapFocus(e.getX() / getWidth(), e.getY() / getHeight());
                performClick();
            }
            return true;
        }

        @Override
        public boolean performClick() { return super.performClick(); }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float d = getResources().getDisplayMetrics().density;
            // Ufuk çizgisi
            if (hasAcc) {
                float g = (float) Math.sqrt(ax * ax + ay * ay + az * az);
                if (g > 1 && Math.abs(az) / g < 0.8f) {
                    float th = (float) Math.toDegrees(Math.atan2(-ax, ay));
                    float dv = th - Math.round(th / 90f) * 90f;
                    boolean ok = Math.abs(dv) < 1f;
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2 * d);
                    p.setColor(ok ? 0xFF5DF58A : 0xBBFFFFFF);
                    c.save();
                    c.rotate(-dv, w / 2f, h / 2f);
                    c.drawLine(w * 0.2f, h / 2f, w * 0.8f, h / 2f, p);
                    c.restore();
                }
            }
            // Sakinlik göstergesi
            if (hasGyro) {
                boolean calm = gyro <= steadyTarget();
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x80000000);
                c.drawRoundRect(8 * d, 8 * d, 112 * d, 32 * d, 12 * d, 12 * d, p);
                p.setColor(calm ? 0xFF5DF58A : 0xFFFF5252);
                c.drawCircle(22 * d, 20 * d, 5 * d, p);
                p.setColor(Color.WHITE);
                p.setTextSize(12 * d);
                c.drawText(calm ? "Sakin an" : "Kıpırdıyor", 33 * d, 24 * d, p);
            }
            // Odak halkası
            if (tapX >= 0 && (afLocked || SystemClock.uptimeMillis() - tapTime < 1500)) {
                int st = afState;
                int col = st == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED ? 0xFF5DF58A
                        : st == CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED ? 0xFFFF5252 : 0xFFFFD54A;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2.5f * d);
                p.setColor(col);
                c.drawCircle(tapX * w, tapY * h, 34 * d, p);
            }
            // Çekim anı
            if (SystemClock.uptimeMillis() < flashUntil) {
                c.drawColor(0x66FFFFFF);
            }
        }
    }

    private class ShutterView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        ShutterView(Context c) {
            super(c);
            setClickable(true);
        }

        @Override
        protected void onDraw(Canvas c) {
            float r = Math.min(getWidth(), getHeight()) / 2f;
            float d = getResources().getDisplayMetrics().density;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(4 * d);
            p.setColor(Color.WHITE);
            c.drawCircle(getWidth() / 2f, getHeight() / 2f, r - 3 * d, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(capturing ? 0xFFBBBBBB : Color.WHITE);
            c.drawCircle(getWidth() / 2f, getHeight() / 2f, r - 10 * d, p);
            if (capturing) postInvalidateDelayed(80);
        }

        @Override
        public boolean performClick() {
            boolean r = super.performClick();
            invalidate();
            return r;
        }
    }
}
