package com.durmuseminalbayrak.kamera;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.graphics.Path;
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
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.util.SizeF;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.HybridBinarizer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Kamera — Android'in kendi kamera sistemiyle (Camera2) çalışan, internet izni olmayan kamera.
 * Modlar: OTO (ışığa göre kendi seçer), FOTO, BELGE, GECE, PRO, VİDEO, BARKOD.
 */
public class MainActivity extends Activity implements SensorEventListener, DocEditor.Listener {

    private static final int REQ_CAMERA = 1, REQ_AUDIO_REC = 2, REQ_AUDIO_VOICE = 3;
    static final int M_OTO = 0, M_FOTO = 1, M_BELGE = 2, M_GECE = 3, M_PRO = 4, M_VIDEO = 5, M_BARKOD = 6;
    private static final String[] MODE_NAMES = {"OTO", "FOTO", "BELGE", "GECE", "PRO", "VİDEO", "BARKOD"};
    private static final int JOB_NORMAL = 0, JOB_DOC = 1, JOB_NIGHT = 2;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread bgThread, anaThread;
    private Handler bg, anaH;

    private SharedPreferences prefs;
    private CameraManager cm;
    private SensorManager sm;

    // ---- Kamera ----
    private final List<String> camIds = new ArrayList<>();
    private int camIndex = 0;
    private String camId;
    private CameraCharacteristics ch;
    private CameraDevice dev;
    private CameraCaptureSession sess;
    private CaptureRequest.Builder pb;
    private ImageReader jpegReader, anaReader;
    private Surface previewSurface;
    private Size previewSize, jpegSize, anaSize, videoSize;
    private Rect active;
    private int sensorOri = 90;
    private boolean front;
    private int maxAfRegions, maxAeRegions;
    private boolean hasAfAuto, hasAfCont, hasAfContVideo, hasAfOff, hasTorch, manualSensor, hasStab;
    private Range<Integer> fastFps, fps30;
    private Range<Long> expRange;
    private Range<Integer> isoRange, evRange;
    private float minFocus = 0, evStep = 1f / 3f, hFov = 70f, maxDigitalZoom = 1f;
    private int[] awbModes;
    private Range<Float> zoomRange;
    private float zoom = 1f;

    private volatile int afState = -1;
    private volatile Float focusDiopter;
    private volatile Long lastExp;
    private volatile Integer lastIso;
    private volatile boolean afLocked = false;
    private MeteringRectangle[] lockRegions;
    private volatile float tapX = -1, tapY = -1;
    private long tapTime = 0;

    // ---- Ayarlar ----
    private boolean torch = false, shortExp, maxRes, steady, level, grid, hist, zebra, voice, docHint;
    private int burst, vres, timerSec = 0, mode;

    // ---- PRO ----
    private boolean proManualExp = false, proManualFocus = false;
    private long proExp = 0;
    private int proIso = 0, proEv = 0, proAwb = CameraMetadata.CONTROL_AWB_MODE_AUTO;
    private float proFocus = 0;

    // ---- Çekim ----
    private volatile boolean capturing = false;
    private final List<byte[]> shots = new ArrayList<>();
    private int expected = 0, failed = 0, job = JOB_NORMAL;
    private volatile int countdown = 0;
    private volatile String busyMsg = null;
    private volatile long flashUntil = 0;
    private volatile boolean autoNight = false;
    private volatile int captureToken = 0;
    private volatile boolean framesDone = false;
    private boolean pendingRecord = false;

    // ---- Canlı analiz ----
    private volatile int[] hist64;
    private volatile Bitmap zebraBmp;
    private volatile float[] docQuad;
    private volatile int docHits = 0;
    private volatile float[][] codeQuad;
    private volatile String lastCode;
    private long lastAna = 0, lastDocT = 0, lastScanT = 0, codeSeen = 0;
    private int zebraPhase = 0;
    private byte[] yBuf;
    private MultiFormatReader zx;

    // ---- Video ----
    private MediaRecorder rec;
    private Surface recSurface;
    private Uri recUri;
    private ParcelFileDescriptor recPfd;
    private volatile boolean recording = false;
    private long recStart = 0;
    private boolean askedAudio = false;

    // ---- Sesle çekim ----
    private volatile boolean voiceRun = false;

    // ---- PDF ----
    private final List<byte[]> pdfJpegs = new ArrayList<>();
    private final List<int[]> pdfSizes = new ArrayList<>();

    private Uri lastUri;
    private String lastMime = "image/jpeg";

    // ---- Sensörler ----
    private volatile float gyro = 0;
    private final float[] ghist = new float[150];
    private int gCount = 0, gIdx = 0;
    private volatile boolean hasGyro = false, hasAcc = false;
    private volatile float ax = 0, ay = 9.8f, az = 0;
    private boolean wasLevel = false;
    private volatile int deviceOri = 0;
    private OrientationEventListener orient;

    // ---- Arayüz ----
    private FrameLayout root;
    private TextureView tv;
    private Overlay ov;
    private ImageView thumb;
    private TextView bFlash, bTimer, bSet, bSwitch, afChip, docChip, info, busyTv, recLabel, pdfLabel, scanFmt, scanText, scanOpen;
    private ShutterView shutter;
    private LinearLayout zoomRow, proPanel, pdfBar, scanCard, setList;
    private ScrollView setPanel;
    private DocEditor docEd;
    private final TextView[] modeViews = new TextView[MODE_NAMES.length];
    private int screenW, topH, pvH;
    private ScaleGestureDetector scaleDet;
    private volatile boolean updatePending = false;

    // ================== YAŞAM DÖNGÜSÜ ==================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prefs = getSharedPreferences("kamera", MODE_PRIVATE);
        burst = prefs.getInt("burst", 3);
        shortExp = prefs.getBoolean("shortExp", true);
        maxRes = prefs.getBoolean("maxRes", false);
        steady = prefs.getBoolean("steady", true);
        level = prefs.getBoolean("level", true);
        grid = prefs.getBoolean("grid", false);
        hist = prefs.getBoolean("hist", false);
        zebra = prefs.getBoolean("zebra", false);
        voice = prefs.getBoolean("voice", false);
        docHint = prefs.getBoolean("docHint", true);
        vres = prefs.getInt("vres", 1);
        mode = prefs.getInt("mode", M_OTO);
        camIndex = prefs.getInt("cam", 0);
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
        bgThread = new HandlerThread("kamera");
        bgThread.start();
        bg = new Handler(bgThread.getLooper());
        anaThread = new HandlerThread("analiz");
        anaThread.start();
        anaH = new Handler(anaThread.getLooper());
        if (orient.canDetectOrientation()) orient.enable();
        Sensor g = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if (g != null) { hasGyro = true; sm.registerListener(this, g, SensorManager.SENSOR_DELAY_GAME); }
        Sensor a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (a != null) { hasAcc = true; sm.registerListener(this, a, SensorManager.SENSOR_DELAY_UI); }
        if (tv.isAvailable()) openCamera();
        if (voice && mode != M_VIDEO) startVoice();
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(tick);
        stopVoice();
        closeCamera();
        if (bgThread != null) { bgThread.quitSafely(); try { bgThread.join(); } catch (InterruptedException ignored) { } }
        if (anaThread != null) { anaThread.quitSafely(); try { anaThread.join(); } catch (InterruptedException ignored) { } }
        bgThread = anaThread = null;
        bg = anaH = null;
        orient.disable();
        sm.unregisterListener(this);
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (setPanel.getVisibility() == View.VISIBLE) { setPanel.setVisibility(View.GONE); return; }
        if (docEd.isOpen()) { docEd.close(); return; }
        super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_CAMERA) {
            if (ok) { if (tv.isAvailable()) openCamera(); }
            else info.setText("Kamera izni verilmedi. Ayarlar → Uygulamalar → Kamera → İzinler'den izin verin.");
        } else if (requestCode == REQ_AUDIO_REC) {
            if (dev != null && sess != null) startRecording(); else pendingRecord = true;
        } else if (requestCode == REQ_AUDIO_VOICE) {
            if (ok) startVoice();
            else { voice = false; prefs.edit().putBoolean("voice", false).apply(); toast("Mikrofon izni verilmedi"); buildSettings(); }
        }
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
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(18));
        d.setColor(0x33FFFFFF);
        c.setBackground(d);
        c.setClickable(true);
        return c;
    }

    private void setChipColor(TextView c, boolean on) {
        ((GradientDrawable) c.getBackground()).setColor(on ? 0xFFFFD54A : 0x33FFFFFF);
        c.setTextColor(on ? Color.BLACK : Color.WHITE);
    }

    private LinearLayout.LayoutParams wrapLp(int mh) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        l.setMargins(dp(mh), dp(2), dp(mh), dp(2));
        return l;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        screenW = getResources().getDisplayMetrics().widthPixels;
        topH = dp(56);
        pvH = screenW * 4 / 3;

        tv = new TextureView(this);
        root.addView(tv, previewLp());
        ov = new Overlay(this);
        root.addView(ov, previewLp());
        scaleDet = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                setZoom(zoom * d.getScaleFactor());
                return true;
            }
        });

        // Üst çubuk
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(6), 0, dp(6), 0);
        bFlash = chip("Fener: kapalı");
        bTimer = chip("⏱ kapalı");
        bSet = chip("⚙ Ayarlar");
        for (TextView c : new TextView[]{bFlash, bTimer, bSet}) top.addView(c, wrapLp(4));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(top);
        root.addView(hs, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, topH, Gravity.TOP));

        afChip = chip("Odak kilitli ✕");
        setChipColor(afChip, true);
        afChip.setVisibility(View.GONE);
        root.addView(afChip, topCenterLp(topH + dp(8)));

        docChip = chip("📄 Belge algılandı · Belge moduna geç");
        ((GradientDrawable) docChip.getBackground()).setColor(0xE65DF58A);
        docChip.setTextColor(Color.BLACK);
        docChip.setVisibility(View.GONE);
        root.addView(docChip, topCenterLp(topH + dp(52)));

        recLabel = chip("● 00:00");
        ((GradientDrawable) recLabel.getBackground()).setColor(0xE6D32F2F);
        recLabel.setVisibility(View.GONE);
        root.addView(recLabel, topCenterLp(topH + dp(8)));

        zoomRow = new LinearLayout(this);
        zoomRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(zoomRow, topCenterLp(topH + pvH - dp(52)));

        info = new TextView(this);
        info.setTextColor(0xCCFFFFFF);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        info.setPadding(dp(12), dp(4), dp(12), dp(4));
        FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        il.topMargin = topH + pvH;
        root.addView(info, il);

        // Alt yığın: PRO paneli, PDF çubuğu, barkod kartı, modlar, kontroller
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(0x99000000);

        proPanel = new LinearLayout(this);
        proPanel.setOrientation(LinearLayout.VERTICAL);
        proPanel.setPadding(dp(10), dp(6), dp(10), dp(2));
        proPanel.setVisibility(View.GONE);
        bottom.addView(proPanel);

        pdfBar = new LinearLayout(this);
        pdfBar.setOrientation(LinearLayout.HORIZONTAL);
        pdfBar.setGravity(Gravity.CENTER);
        pdfBar.setVisibility(View.GONE);
        pdfLabel = new TextView(this);
        pdfLabel.setTextColor(Color.WHITE);
        pdfLabel.setPadding(dp(4), 0, dp(8), 0);
        TextView pdfSave = chip("PDF kaydet"), pdfClear = chip("Temizle");
        setChipColor(pdfSave, true);
        pdfBar.addView(pdfLabel);
        pdfBar.addView(pdfSave, wrapLp(4));
        pdfBar.addView(pdfClear, wrapLp(4));
        bottom.addView(pdfBar);

        scanCard = new LinearLayout(this);
        scanCard.setOrientation(LinearLayout.VERTICAL);
        scanCard.setPadding(dp(14), dp(10), dp(14), dp(10));
        scanCard.setVisibility(View.GONE);
        scanFmt = new TextView(this);
        scanFmt.setTextColor(0xAAFFFFFF);
        scanFmt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        scanText = new TextView(this);
        scanText.setTextColor(Color.WHITE);
        scanText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        scanText.setTextIsSelectable(true);
        scanText.setMaxLines(6);
        LinearLayout scanBtns = new LinearLayout(this);
        TextView scCopy = chip("Kopyala"), scClose = chip("Kapat");
        scanOpen = chip("Bağlantıyı aç");
        scanBtns.addView(scCopy, wrapLp(4));
        scanBtns.addView(scanOpen, wrapLp(4));
        scanBtns.addView(scClose, wrapLp(4));
        scanCard.addView(scanFmt);
        scanCard.addView(scanText);
        scanCard.addView(scanBtns);
        bottom.addView(scanCard);

        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modes.setGravity(Gravity.CENTER);
        for (int i = 0; i < MODE_NAMES.length; i++) {
            final int m = i;
            TextView t = new TextView(this);
            t.setText(MODE_NAMES[i]);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            t.setPadding(dp(10), dp(8), dp(10), dp(8));
            t.setOnClickListener(v -> setMode(m));
            modeViews[i] = t;
            modes.addView(t);
        }
        HorizontalScrollView ms = new HorizontalScrollView(this);
        ms.setHorizontalScrollBarEnabled(false);
        ms.addView(modes);
        bottom.addView(ms, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout controls = new FrameLayout(this);
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
        controls.addView(thumb, tl);
        shutter = new ShutterView(this);
        controls.addView(shutter, new FrameLayout.LayoutParams(dp(80), dp(80), Gravity.CENTER));
        bSwitch = chip("⟲ Kamera");
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.END);
        sl.rightMargin = dp(16);
        controls.addView(bSwitch, sl);
        bottom.addView(controls, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(104)));
        root.addView(bottom, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        busyTv = new TextView(this);
        busyTv.setTextColor(Color.WHITE);
        busyTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        busyTv.setGravity(Gravity.CENTER);
        busyTv.setPadding(dp(18), dp(12), dp(18), dp(12));
        GradientDrawable bd = new GradientDrawable();
        bd.setColor(0xCC000000);
        bd.setCornerRadius(dp(14));
        busyTv.setBackground(bd);
        busyTv.setVisibility(View.GONE);
        root.addView(busyTv, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // Ayar paneli
        setPanel = new ScrollView(this);
        setPanel.setBackgroundColor(0xF2151515);
        setPanel.setVisibility(View.GONE);
        setList = new LinearLayout(this);
        setList.setOrientation(LinearLayout.VERTICAL);
        setList.setPadding(dp(16), dp(12), dp(16), dp(24));
        setPanel.addView(setList);
        root.addView(setPanel, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (int) (getResources().getDisplayMetrics().heightPixels * 0.72f), Gravity.BOTTOM));

        docEd = new DocEditor(this, this);
        root.addView(docEd, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);

        // Olaylar
        bFlash.setOnClickListener(v -> {
            if (!hasTorch) { toast("Bu kamerada fener yok"); return; }
            torch = !torch;
            bFlash.setText(torch ? "Fener: açık" : "Fener: kapalı");
            setChipColor(bFlash, torch);
            updatePreview();
        });
        bTimer.setOnClickListener(v -> {
            timerSec = timerSec == 0 ? 2 : timerSec == 2 ? 5 : timerSec == 5 ? 10 : 0;
            bTimer.setText(timerSec == 0 ? "⏱ kapalı" : "⏱ " + timerSec + " sn");
            setChipColor(bTimer, timerSec > 0);
        });
        bSet.setOnClickListener(v -> { buildSettings(); setPanel.setVisibility(setPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); });
        bSwitch.setOnClickListener(v -> switchCamera());
        afChip.setOnClickListener(v -> unlockAf());
        docChip.setOnClickListener(v -> setMode(M_BELGE));
        shutter.setOnClickListener(v -> onShutter());
        thumb.setOnClickListener(v -> openLast());
        pdfSave.setOnClickListener(v -> savePdf());
        pdfClear.setOnClickListener(v -> { pdfJpegs.clear(); pdfSizes.clear(); updatePdfBar(); toast("PDF sayfaları temizlendi"); });
        scCopy.setOnClickListener(v -> {
            ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cb != null && lastCode != null) { cb.setPrimaryClip(ClipData.newPlainText("kod", lastCode)); toast("Kopyalandı"); }
        });
        scanOpen.setOnClickListener(v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(lastCode))); } catch (Exception e) { toast("Açılamadı"); }
        });
        scClose.setOnClickListener(v -> scanCard.setVisibility(View.GONE));

        refreshModeUi();
    }

    private FrameLayout.LayoutParams previewLp() {
        FrameLayout.LayoutParams l = new FrameLayout.LayoutParams(screenW, pvH, Gravity.TOP);
        l.topMargin = topH;
        return l;
    }

    private FrameLayout.LayoutParams topCenterLp(int top) {
        FrameLayout.LayoutParams l = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        l.topMargin = top;
        return l;
    }

    private void relayout(int newH) {
        if (newH == pvH) return;
        pvH = newH;
        tv.setLayoutParams(previewLp());
        ov.setLayoutParams(previewLp());
        FrameLayout.LayoutParams il = (FrameLayout.LayoutParams) info.getLayoutParams();
        il.topMargin = topH + pvH;
        info.setLayoutParams(il);
        zoomRow.setLayoutParams(topCenterLp(topH + pvH - dp(52)));
    }

    private void refreshModeUi() {
        for (int i = 0; i < modeViews.length; i++) {
            boolean sel = i == mode;
            modeViews[i].setTextColor(sel ? 0xFFFFD54A : 0x99FFFFFF);
            modeViews[i].setTypeface(null, sel ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
        proPanel.setVisibility(mode == M_PRO ? View.VISIBLE : View.GONE);
        if (mode != M_BARKOD) scanCard.setVisibility(View.GONE);
        if (mode != M_OTO) docChip.setVisibility(View.GONE);
        updatePdfBar();
        shutter.invalidate();
    }

    private void updatePdfBar() {
        pdfLabel.setText("PDF: " + pdfJpegs.size() + " sayfa");
        pdfBar.setVisibility(mode == M_BELGE && !pdfJpegs.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void toast(final String m) {
        ui.post(() -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show());
    }

    private void vib(int ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) { }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            ov.invalidate();
            shutter.invalidate();
            StringBuilder s = new StringBuilder();
            Float d = focusDiopter;
            if (d != null) s.append("Odak ").append(d <= 0.05f ? "uzak" : String.format(Locale.US, "%.2f m", 1f / d));
            Long e = lastExp;
            if (e != null && e > 0) s.append("  ·  ").append(fmtExp(e));
            Integer iso = lastIso;
            if (iso != null) s.append("  ·  ISO ").append(iso);
            Size z = mode == M_VIDEO ? videoSize : jpegSize;
            if (z != null) s.append("  ·  ").append(z.getWidth()).append("×").append(z.getHeight());
            if (dev != null) info.setText(s.toString());
            String b = busyMsg;
            busyTv.setVisibility(b != null ? View.VISIBLE : View.GONE);
            if (b != null) busyTv.setText(b);
            if (recording) {
                long sec = (SystemClock.uptimeMillis() - recStart) / 1000;
                recLabel.setText(String.format(Locale.US, "● %02d:%02d", sec / 60, sec % 60));
            }
            recLabel.setVisibility(recording ? View.VISIBLE : View.GONE);
            docChip.setVisibility(mode == M_OTO && docHint && docHits >= 3 && !capturing ? View.VISIBLE : View.GONE);
            ui.postDelayed(this, 100);
        }
    };

    private static String fmtExp(long ns) {
        double s = ns / 1e9;
        return s >= 1 ? String.format(Locale.US, "%.1f sn", s) : "1/" + Math.round(1 / s) + " sn";
    }

    // ================== AYAR PANELİ ==================
    private interface BoolSet { void set(boolean v); }

    private void addSwitch(String key, String title, String sub, boolean val, final BoolSet setter) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        txt.addView(t);
        if (sub != null) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextColor(0x99FFFFFF);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            txt.addView(s);
        }
        row.addView(txt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = new Switch(this);
        sw.setChecked(val);
        sw.setOnCheckedChangeListener((b, v) -> { prefs.edit().putBoolean(key, v).apply(); setter.set(v); });
        row.addView(sw);
        setList.addView(row);
    }

    private interface IntSet { void set(int v); }

    private void addCycle(String title, final String[] labels, final int[] values, int current, final IntSet setter) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        int idx = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == current) idx = i;
        final int[] cur = {idx};
        final TextView c = chip(labels[idx]);
        c.setOnClickListener(v -> {
            cur[0] = (cur[0] + 1) % values.length;
            c.setText(labels[cur[0]]);
            setter.set(values[cur[0]]);
        });
        row.addView(c);
        setList.addView(row);
    }

    private void buildSettings() {
        setList.removeAllViews();
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView h = new TextView(this);
        h.setText("Ayarlar");
        h.setTextColor(Color.WHITE);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        head.addView(h, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = chip("Kapat");
        close.setOnClickListener(v -> setPanel.setVisibility(View.GONE));
        head.addView(close);
        setList.addView(head);

        addSwitch("steady", "Titreme kontrollü deklanşör", "Elinizin en sakin anında çeker", steady, v -> steady = v);
        addSwitch("shortExp", "Kısa pozlama", "Titremede bulanıklığı azaltır; karanlıkta kumlanma biraz artabilir", shortExp, v -> { shortExp = v; updatePreview(); });
        addSwitch("level", "Ufuk çizgisi / su terazisi", "Belge modunda telefon yatayken terazi çıkar", level, v -> level = v);
        addSwitch("grid", "Izgara", null, grid, v -> grid = v);
        addSwitch("hist", "Histogram", "Parlaklık dağılımı (sağ üst)", hist, v -> hist = v);
        addSwitch("zebra", "Zebra çizgileri", "Aşırı parlak (patlayan) yerleri gösterir", zebra, v -> { zebra = v; if (!v) zebraBmp = null; });
        addSwitch("voice", "Sesle çek (alkış)", "Mikrofonu dinler, ses kaydetmez", voice, v -> { voice = v; if (v) startVoice(); else stopVoice(); });
        addSwitch("docHint", "OTO'da belge önerisi", "Kadrajda belge görünce Belge modunu önerir", docHint, v -> docHint = v);
        addCycle("Seri çekim (en net kare)", new String[]{"Kapalı", "3 kare", "5 kare"}, new int[]{1, 3, 5}, burst, v -> { burst = v; prefs.edit().putInt("burst", v).apply(); });
        addCycle("Fotoğraf çözünürlüğü", new String[]{"12 MP", "Maksimum"}, new int[]{0, 1}, maxRes ? 1 : 0, v -> {
            maxRes = v == 1;
            prefs.edit().putBoolean("maxRes", maxRes).apply();
            reopen();
        });
        addCycle("Video çözünürlüğü", new String[]{"720p", "1080p", "4K"}, new int[]{0, 1, 2}, vres, v -> {
            vres = v;
            prefs.edit().putInt("vres", v).apply();
            if (mode == M_VIDEO) reopen();
        });
        TextView note = new TextView(this);
        note.setText("Bu uygulamanın internet izni yoktur; fotoğraflar yalnızca telefonunuzda kalır.\nFotoğraf ve videolar: Galeri → DCIM/Kamera. PDF'ler: İndirilenler/Kamera.");
        note.setTextColor(0x88FFFFFF);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setPadding(0, dp(14), 0, 0);
        setList.addView(note);
    }

    // ================== MODLAR ==================
    private void setMode(int m) {
        if (recording || capturing || m == mode) return;
        boolean reopenNeeded = (m == M_VIDEO) != (mode == M_VIDEO);
        mode = m;
        prefs.edit().putInt("mode", m).apply();
        docQuad = null;
        docHits = 0;
        codeQuad = null;
        lastCode = null;
        refreshModeUi();
        if (m == M_VIDEO) stopVoice(); else if (voice) startVoice();
        if (reopenNeeded) reopen();
        else { updatePreview(); buildPro(); }
        if (m == M_BELGE) toast("Belgeyi kadraja alın; yeşil çerçeve kenarları gösterir");
        else if (m == M_GECE) toast("Gece: birkaç kare çekip birleştirir, sabit tutun");
        else if (m == M_OTO) toast("OTO: ışığa göre normal ya da gece çekimini kendisi seçer");
        else if (m == M_BARKOD) toast("Barkodu veya QR kodu kadraja alın");
    }

    private void reopen() {
        if (bg == null) return;
        closeCamera();
        openCamera();
    }

    private void switchCamera() {
        if (recording || capturing) return;
        if (camIds.size() < 2) { toast("Başka kamera bulunamadı"); return; }
        camIndex = (camIndex + 1) % camIds.size();
        prefs.edit().putInt("cam", camIndex).apply();
        zoom = 1f;
        reopen();
    }

    // ================== KAMERA ==================
    private static boolean contains(int[] a, int v) {
        if (a == null) return false;
        for (int x : a) if (x == v) return true;
        return false;
    }

    private static double ratio(Size s) { return (double) s.getWidth() / s.getHeight(); }

    private static Size pickJpeg(Size[] sizes, boolean max) {
        Size[] s = sizes.clone();
        Arrays.sort(s, (a, b) -> Long.compare((long) b.getWidth() * b.getHeight(), (long) a.getWidth() * a.getHeight()));
        if (max) return s[0];
        for (Size z : s) if ((long) z.getWidth() * z.getHeight() <= 13_000_000L && Math.abs(ratio(z) - 4.0 / 3.0) < 0.02) return z;
        for (Size z : s) if ((long) z.getWidth() * z.getHeight() <= 13_000_000L) return z;
        return s[0];
    }

    private static Size pickPreview(Size[] sizes, double r, int maxW, int maxH) {
        Size best = null;
        for (Size z : sizes) {
            if (z.getWidth() > maxW || z.getHeight() > maxH || Math.abs(ratio(z) - r) > 0.02) continue;
            if (best == null || (long) z.getWidth() * z.getHeight() > (long) best.getWidth() * best.getHeight()) best = z;
        }
        if (best != null) return best;
        for (Size z : sizes) {
            if (z.getWidth() > maxW || z.getHeight() > maxH) continue;
            if (best == null || (long) z.getWidth() * z.getHeight() > (long) best.getWidth() * best.getHeight()) best = z;
        }
        return best != null ? best : sizes[0];
    }

    private static Size pickAna(Size[] sizes, Size preview) {
        Size best = null;
        double r = ratio(preview);
        for (Size z : sizes) {
            if (z.getWidth() > 1280 || z.getWidth() > preview.getWidth() || Math.abs(ratio(z) - r) > 0.02) continue;
            if (best == null || z.getWidth() > best.getWidth()) best = z;
        }
        if (best != null) return best;
        for (Size z : sizes) {
            if (z.getWidth() > 1280 || z.getWidth() < 320) continue;
            if (best == null || z.getWidth() > best.getWidth()) best = z;
        }
        return best != null ? best : sizes[sizes.length - 1];
    }

    private static Size pickVideo(Size[] sizes, int vres) {
        int[] tw = {1280, 1920, 3840};
        int target = tw[Math.max(0, Math.min(2, vres))];
        Size best = null;
        for (Size z : sizes) {
            if (Math.abs(ratio(z) - 16.0 / 9.0) > 0.02 || z.getWidth() > target) continue;
            if (best == null || z.getWidth() > best.getWidth()) best = z;
        }
        if (best != null) return best;
        for (Size z : sizes) {
            if (z.getWidth() > 1920) continue;
            if (best == null || z.getWidth() > best.getWidth()) best = z;
        }
        return best != null ? best : sizes[0];
    }

    private static Range<Integer> pickFastFps(Range<Integer>[] ranges) {
        if (ranges == null) return null;
        Range<Integer> best = null;
        for (Range<Integer> r : ranges) {
            int lo = r.getLower();
            if (lo < 24 || lo > 30) continue;
            if (best == null || lo > best.getLower() || (lo == best.getLower() && r.getUpper() < best.getUpper())) best = r;
        }
        return best;
    }

    private void listCameras() throws CameraAccessException {
        camIds.clear();
        List<String> fronts = new ArrayList<>();
        for (String id : cm.getCameraIdList()) {
            Integer f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (f == null) continue;
            if (f == CameraCharacteristics.LENS_FACING_BACK) camIds.add(id);
            else if (f == CameraCharacteristics.LENS_FACING_FRONT) fronts.add(id);
        }
        camIds.addAll(fronts);
        if (camIndex >= camIds.size()) camIndex = 0;
    }

    private void openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        if (dev != null || bg == null) return;
        try {
            listCameras();
            if (camIds.isEmpty()) { toast("Kamera bulunamadı"); return; }
            camId = camIds.get(camIndex);
            ch = cm.getCameraCharacteristics(camId);
            Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
            front = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
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
            hasAfContVideo = contains(afModes, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            hasAfOff = contains(afModes, CameraMetadata.CONTROL_AF_MODE_OFF);
            Boolean fl = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            hasTorch = fl != null && fl;
            manualSensor = contains(ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES), CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR);
            Range<Integer>[] fr = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            fastFps = pickFastFps(fr);
            fps30 = null;
            if (fr != null) for (Range<Integer> r : fr) if (r.getLower() == 30 && r.getUpper() == 30) fps30 = r;
            expRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            Float mf = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            minFocus = mf == null ? 0 : mf;
            evRange = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            Rational st = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
            evStep = st == null ? 1f / 3f : st.floatValue();
            awbModes = ch.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
            hasStab = contains(ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES), CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON);
            zoomRange = Build.VERSION.SDK_INT >= 30 ? ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) : null;
            Float mdz = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            maxDigitalZoom = mdz == null ? 1f : mdz;
            float[] fls = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            SizeF phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            hFov = (fls != null && fls.length > 0 && phys != null) ? (float) Math.toDegrees(2 * Math.atan(phys.getWidth() / (2 * fls[0]))) : 70f;
            zoom = ImageTools.clampf(zoom, zMin(), zMax());

            jpegSize = pickJpeg(map.getOutputSizes(ImageFormat.JPEG), maxRes);
            if (mode == M_VIDEO) {
                videoSize = pickVideo(map.getOutputSizes(MediaRecorder.class), vres);
                previewSize = pickPreview(map.getOutputSizes(SurfaceTexture.class), ratio(videoSize), 1920, 1080);
            } else {
                previewSize = pickPreview(map.getOutputSizes(SurfaceTexture.class), ratio(jpegSize), 1920, 1440);
                anaSize = pickAna(map.getOutputSizes(ImageFormat.YUV_420_888), previewSize);
                jpegReader = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 9);
                jpegReader.setOnImageAvailableListener(onJpeg, bg);
                anaReader = ImageReader.newInstance(anaSize.getWidth(), anaSize.getHeight(), ImageFormat.YUV_420_888, 3);
                anaReader.setOnImageAvailableListener(onAna, anaH);
            }
            final int newH = Math.round(screenW * (float) previewSize.getWidth() / previewSize.getHeight());
            ui.post(() -> { relayout(newH); buildZoomRow(); buildPro(); bSwitch.setText(camIds.size() > 1 ? "⟲ " + (front ? "Ön" : "Arka " + (camIndex + 1)) : "⟲ Kamera"); });
            cm.openCamera(camId, devCb, bg);
        } catch (SecurityException e) {
            toast("Kamera izni yok");
        } catch (Exception e) {
            toast("Kamera açılamadı: " + e.getMessage());
        }
    }

    private void closeCamera() {
        if (recording) finishRecording();
        try { if (sess != null) sess.close(); } catch (Exception ignored) { }
        sess = null;
        try { if (dev != null) dev.close(); } catch (Exception ignored) { }
        dev = null;
        try { if (jpegReader != null) jpegReader.close(); } catch (Exception ignored) { }
        jpegReader = null;
        try { if (anaReader != null) anaReader.close(); } catch (Exception ignored) { }
        anaReader = null;
        if (previewSurface != null) { previewSurface.release(); previewSurface = null; }
        afLocked = false;
        lockRegions = null;
        tapX = -1;
        capturing = false;
        busyMsg = null;
        ui.post(() -> afChip.setVisibility(View.GONE));
    }

    private final CameraDevice.StateCallback devCb = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice d) { dev = d; startPreview(); }
        @Override public void onDisconnected(CameraDevice d) { d.close(); dev = null; }
        @Override public void onError(CameraDevice d, int error) { d.close(); dev = null; toast("Kamera hatası: " + error); }
    };

    private float zMin() { return zoomRange != null ? zoomRange.getLower() : 1f; }
    private float zMax() { return Math.min(zoomRange != null ? zoomRange.getUpper() : maxDigitalZoom, 10f); }
    private boolean ratioZoom() { return Build.VERSION.SDK_INT >= 30 && zoomRange != null; }

    private Rect cropRect() {
        int aw = active.width(), ah = active.height();
        int cw = Math.round(aw / zoom), chh = Math.round(ah / zoom);
        int l = (aw - cw) / 2, t = (ah - chh) / 2;
        return new Rect(l, t, l + cw, t + chh);
    }

    /** Odak bölgelerinin koordinat tabanı (aktif dizi, 0'dan başlar) */
    private Rect regionBase() {
        if (!ratioZoom() && zoom > 1.01f) return cropRect();
        return new Rect(0, 0, active.width(), active.height());
    }

    /** Önizleme / video / çekim isteklerine ortak ayarlar */
    private void applyCommon(CaptureRequest.Builder b) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        boolean manExp = mode == M_PRO && proManualExp && manualSensor && proExp > 0 && proIso > 0;
        if (manExp) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, proExp);
            b.set(CaptureRequest.SENSOR_SENSITIVITY, proIso);
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
            if (mode == M_PRO && evRange != null) b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, proEv);
            if (mode == M_VIDEO) { if (fps30 != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps30); }
            else if (shortExp && fastFps != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fastFps);
        }
        if (mode == M_PRO && proManualFocus && hasAfOff && minFocus > 0) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, proFocus);
        } else if (afLocked && hasAfAuto) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO);
            if (lockRegions != null && maxAfRegions > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, lockRegions);
            if (lockRegions != null && maxAeRegions > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, lockRegions);
        } else if (mode == M_VIDEO && hasAfContVideo) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        } else if (hasAfCont) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        } else if (hasAfAuto) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO);
        }
        b.set(CaptureRequest.CONTROL_AWB_MODE, mode == M_PRO ? proAwb : CameraMetadata.CONTROL_AWB_MODE_AUTO);
        b.set(CaptureRequest.FLASH_MODE, torch ? CameraMetadata.FLASH_MODE_TORCH : CameraMetadata.FLASH_MODE_OFF);
        if (mode == M_VIDEO && hasStab) b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON);
        if (ratioZoom()) {
            if (Build.VERSION.SDK_INT >= 30) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom);
        } else if (zoom > 1.01f) {
            b.set(CaptureRequest.SCALER_CROP_REGION, cropRect());
        }
    }

    private CaptureRequest.Builder newPreviewBuilder() throws CameraAccessException {
        CaptureRequest.Builder b = dev.createCaptureRequest(recording ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW);
        b.addTarget(previewSurface);
        if (recording && recSurface != null) b.addTarget(recSurface);
        else if (mode != M_VIDEO && anaReader != null) b.addTarget(anaReader.getSurface());
        applyCommon(b);
        return b;
    }

    private void startPreview() {
        try {
            SurfaceTexture st = tv.getSurfaceTexture();
            if (st == null || dev == null) return;
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            if (previewSurface == null) previewSurface = new Surface(st);
            List<Surface> outs = new ArrayList<>();
            outs.add(previewSurface);
            if (mode != M_VIDEO) { outs.add(jpegReader.getSurface()); outs.add(anaReader.getSurface()); }
            dev.createCaptureSession(outs, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    if (dev == null) return;
                    sess = s;
                    try { pb = newPreviewBuilder(); repeat(); } catch (Exception e) { toast("Önizleme hatası: " + e.getMessage()); }
                    if (pendingRecord && mode == M_VIDEO) { pendingRecord = false; ui.post(() -> startRecording()); }
                }
                @Override
                public void onConfigureFailed(CameraCaptureSession s) { toast("Kamera oturumu kurulamadı"); }
            }, bg);
        } catch (Exception e) {
            toast("Önizleme başlatılamadı: " + e.getMessage());
        }
    }

    private void repeat() {
        try { if (sess != null && pb != null) sess.setRepeatingRequest(pb.build(), previewCb, bg); }
        catch (Exception e) { toast("Önizleme hatası: " + e.getMessage()); }
    }

    /** Önizleme isteğini güncel ayarlarla yeniden kurar (art arda çağrılar birleştirilir) */
    private void updatePreview() {
        if (bg == null || updatePending) return;
        updatePending = true;
        bg.postDelayed(() -> {
            updatePending = false;
            if (dev == null || sess == null) return;
            try { pb = newPreviewBuilder(); repeat(); } catch (Exception e) { toast("Ayar uygulanamadı"); }
        }, 30);
    }

    private final CameraCaptureSession.CaptureCallback previewCb = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
            Integer a = res.get(CaptureResult.CONTROL_AF_STATE);
            if (a != null) afState = a;
            focusDiopter = res.get(CaptureResult.LENS_FOCUS_DISTANCE);
            Long e = res.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Integer iso = res.get(CaptureResult.SENSOR_SENSITIVITY);
            lastExp = e;
            lastIso = iso;
            if (e != null && iso != null) {
                double ms = e / 1e6;
                // Işık ölçümü: uzun pozlama ya da yüksek ISO = karanlık (eşikler arasında boşluk: titreşmesin)
                autoNight = autoNight ? (ms >= 22 || iso >= 700) : (ms >= 30 || iso >= 1000);
            }
        }
    };

    // ================== YAKINLAŞTIRMA ==================
    private void setZoom(float z) {
        float nz = ImageTools.clampf(z, zMin(), zMax());
        if (Math.abs(nz - zoom) < 0.005f) return;
        zoom = nz;
        if (afLocked) { afLocked = false; lockRegions = null; tapX = -1; afChip.setVisibility(View.GONE); }
        updatePreview();
        refreshZoomRow();
    }

    private void buildZoomRow() {
        zoomRow.removeAllViews();
        if (zMax() - zMin() < 0.2f) return;
        List<Float> opts = new ArrayList<>();
        if (zMin() < 0.95f) opts.add(Math.round(zMin() * 10) / 10f);
        opts.add(1f);
        if (zMax() >= 2f) opts.add(2f);
        if (zMax() >= 5f) opts.add(5f);
        for (final float z : opts) {
            TextView c = chip(z < 1 ? String.format(Locale.US, "%.1fx", z) : String.format(Locale.US, "%.0fx", z));
            c.setTag(z);
            c.setOnClickListener(v -> setZoom(z));
            zoomRow.addView(c, wrapLp(3));
        }
        refreshZoomRow();
    }

    private void refreshZoomRow() {
        for (int i = 0; i < zoomRow.getChildCount(); i++) {
            View v = zoomRow.getChildAt(i);
            if (v instanceof TextView && v.getTag() instanceof Float) setChipColor((TextView) v, Math.abs((Float) v.getTag() - zoom) < 0.05f);
        }
    }

    // ================== DOKUNARAK ODAK ==================
    /** Ekrandaki (portre) noktayı sensör koordinatına çevirir */
    private float[] toSensor(float nx, float ny) {
        switch (sensorOri) {
            case 90: return new float[]{ny, 1f - nx};
            case 180: return new float[]{1f - nx, 1f - ny};
            case 270: return new float[]{1f - ny, nx};
            default: return new float[]{nx, ny};
        }
    }

    /** Sensör koordinatını ekrana çevirir (toSensor'ın tersi) */
    private float[] sensorToDisp(float sx, float sy) {
        switch (sensorOri) {
            case 90: return new float[]{1f - sy, sx};
            case 180: return new float[]{1f - sx, 1f - sy};
            case 270: return new float[]{sy, 1f - sx};
            default: return new float[]{sx, sy};
        }
    }

    private void tapFocus(float nx, float ny) {
        if (sess == null || active == null || capturing || bg == null) return;
        if (front) { toast("Ön kamerada dokunarak odak yok"); return; }
        if (mode == M_PRO && proManualFocus) { toast("Elle odak açık. PRO panelinden otomatiğe alabilirsiniz."); return; }
        tapX = nx;
        tapY = ny;
        tapTime = SystemClock.uptimeMillis();
        if (!hasAfAuto || (maxAfRegions == 0 && maxAeRegions == 0)) { toast("Bu kamera dokunarak odaklamayı desteklemiyor"); return; }
        float[] s = toSensor(nx, ny);
        Rect base = regionBase();
        int half = Math.round(Math.min(base.width(), base.height()) * 0.07f);
        int cx = base.left + Math.round(s[0] * base.width());
        int cy = base.top + Math.round(s[1] * base.height());
        cx = Math.max(base.left + half, Math.min(base.right - half, cx));
        cy = Math.max(base.top + half, Math.min(base.bottom - half, cy));
        lockRegions = new MeteringRectangle[]{new MeteringRectangle(new Rect(cx - half, cy - half, cx + half, cy + half), MeteringRectangle.METERING_WEIGHT_MAX - 1)};
        afLocked = true;
        afChip.setVisibility(View.VISIBLE);
        bg.post(() -> {
            if (sess == null || dev == null) return;
            try {
                pb.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
                sess.capture(pb.build(), null, bg);
                pb = newPreviewBuilder();
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
            if (sess == null || dev == null || pb == null) return;
            try {
                pb.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
                sess.capture(pb.build(), null, bg);
            } catch (Exception ignored) { }
        });
        updatePreview();
    }

    // ================== DEKLANŞÖR ==================
    private float steadyTarget() {
        if (gCount < 20) return 4f;
        float[] a = Arrays.copyOf(ghist, gCount);
        Arrays.sort(a);
        return Math.max(1.5f, Math.min(12f, a[(int) (a.length * 0.25f)]));
    }

    private boolean nightNow() { return mode == M_GECE || (mode == M_OTO && autoNight); }

    private void onShutter() {
        if (docEd.isOpen()) return;
        if (mode == M_VIDEO) { if (recording) stopRecording(); else startRecording(); return; }
        if (mode == M_BARKOD) { lastCode = null; scanCard.setVisibility(View.GONE); toast("Yeniden taranıyor"); return; }
        if (capturing || sess == null || dev == null) return;
        capturing = true;
        setPanel.setVisibility(View.GONE);
        new Thread(() -> {
            try {
                for (int i = timerSec; i > 0; i--) { countdown = i; vib(20); SystemClock.sleep(1000); }
                countdown = 0;
                final boolean night = nightNow();
                if (steady && hasGyro) {
                    float t = steadyTarget();
                    long t0 = SystemClock.uptimeMillis(), max = night ? 700 : 350;
                    while (SystemClock.uptimeMillis() - t0 < max && gyro > t) SystemClock.sleep(5);
                }
                if (!afLocked && !(mode == M_PRO && proManualFocus)) {
                    long t0 = SystemClock.uptimeMillis();
                    while (SystemClock.uptimeMillis() - t0 < 500 && (afState == CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN || afState == CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN)) SystemClock.sleep(15);
                }
                if (night) shootNight();
                else {
                    int n = burst;
                    if (mode == M_OTO) n = (hasGyro && steadyTarget() > 6f) ? 5 : 3;
                    if (mode == M_BELGE) n = Math.min(Math.max(burst, 1), 3);
                    shootNormal(Math.max(1, n), mode == M_BELGE ? JOB_DOC : JOB_NORMAL);
                }
            } catch (Exception e) {
                countdown = 0;
                capturing = false;
                busyMsg = null;
                toast("Çekilemedi: " + e.getMessage());
            }
        }, "cekim").start();
    }

    private int jpegOrientation() {
        return front ? (sensorOri - deviceOri + 360) % 360 : (sensorOri + deviceOri + 360) % 360;
    }

    /** Kamera kareyi teslim etmezse deklanşör kilitli kalmasın */
    private void armWatchdog() {
        final int tok = ++captureToken;
        framesDone = false;
        ui.postDelayed(() -> {
            if (capturing && !framesDone && tok == captureToken) {
                synchronized (shots) { shots.clear(); expected = 0; }
                capturing = false;
                busyMsg = null;
                toast("Kamera yanıt vermedi, tekrar deneyin");
            }
        }, 12000);
    }

    private CaptureRequest.Builder stillBuilder() throws CameraAccessException {
        CaptureRequest.Builder sb = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        sb.addTarget(jpegReader.getSurface());
        applyCommon(sb);
        sb.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation());
        sb.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
        return sb;
    }

    private void shootNormal(int n, int jobType) throws CameraAccessException {
        synchronized (shots) { shots.clear(); expected = n; failed = 0; job = jobType; }
        List<CaptureRequest> reqs = new ArrayList<>();
        for (int i = 0; i < n; i++) reqs.add(stillBuilder().build());
        flashUntil = SystemClock.uptimeMillis() + 120;
        vib(25);
        armWatchdog();
        if (n == 1) sess.capture(reqs.get(0), stillCb, bg);
        else sess.captureBurst(reqs, stillCb, bg);
    }

    /** Gece: kısa pozlamalı kareler (titreme izi azalır), sonra hizalanıp birleştirilir (kumlanma azalır) */
    private void shootNight() throws CameraAccessException {
        Long e0 = lastExp;
        Integer s0 = lastIso;
        int n = 6;
        long tExp = 0;
        int tIso = 0;
        boolean manual = false;
        if (manualSensor && e0 != null && s0 != null && expRange != null && isoRange != null && !(mode == M_PRO)) {
            float tgt = hasGyro ? Math.max(1f, steadyTarget()) : 3f;
            double pxPerDeg = Math.max(jpegSize.getWidth(), jpegSize.getHeight()) / Math.max(30.0, hFov);
            double maxS = 2.0 / (tgt * pxPerDeg);
            maxS = Math.max(1 / 250.0, Math.min(1 / 20.0, maxS));
            tExp = Math.min(e0, (long) (maxS * 1e9));
            tExp = Math.max(expRange.getLower(), Math.min(expRange.getUpper(), tExp));
            long iso = Math.round(s0 * (double) e0 / tExp);
            tIso = (int) Math.max(isoRange.getLower(), Math.min(isoRange.getUpper(), iso));
            n = (int) Math.max(4, Math.min(8, Math.ceil(2.0 * e0 / tExp)));
            manual = true;
        }
        synchronized (shots) { shots.clear(); expected = n; failed = 0; job = JOB_NIGHT; }
        List<CaptureRequest> reqs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            CaptureRequest.Builder sb = stillBuilder();
            if (manual) {
                sb.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
                sb.set(CaptureRequest.SENSOR_EXPOSURE_TIME, tExp);
                sb.set(CaptureRequest.SENSOR_SENSITIVITY, tIso);
            }
            reqs.add(sb.build());
        }
        busyMsg = "Gece: " + n + " kare çekiliyor\nsabit tutun" + (manual ? "\n(" + fmtExp(tExp) + " · ISO " + tIso + ")" : "");
        flashUntil = SystemClock.uptimeMillis() + 120;
        vib(25);
        armWatchdog();
        sess.captureBurst(reqs, stillCb, bg);
    }

    private final CameraCaptureSession.CaptureCallback stillCb = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure f) {
            List<byte[]> done = null;
            int j;
            synchronized (shots) {
                failed++;
                j = job;
                if (expected > 0 && shots.size() + failed >= expected) { done = new ArrayList<>(shots); shots.clear(); expected = 0; }
            }
            if (done != null) finishAsync(done, j);
        }
    };

    private final ImageReader.OnImageAvailableListener onJpeg = r -> {
        Image img = null;
        List<byte[]> done = null;
        int j = JOB_NORMAL;
        try {
            img = r.acquireNextImage();
            if (img == null) return;
            ByteBuffer b = img.getPlanes()[0].getBuffer();
            byte[] d = new byte[b.remaining()];
            b.get(d);
            synchronized (shots) {
                if (expected > 0) {
                    shots.add(d);
                    j = job;
                    if (shots.size() + failed >= expected) { done = new ArrayList<>(shots); shots.clear(); expected = 0; }
                }
            }
        } catch (Exception e) {
            toast("Görüntü alınamadı: " + e.getMessage());
        } finally {
            if (img != null) img.close();
        }
        if (done != null) finishAsync(done, j);
    };

    private void finishAsync(final List<byte[]> list, final int jobType) {
        framesDone = true;
        new Thread(() -> {
            try {
                if (list.isEmpty()) { toast("Çekim başarısız"); return; }
                if (jobType == JOB_NIGHT) {
                    byte[] out = nightMerge(list);
                    saveImage(out, "GECE", "Gece çekimi kaydedildi · " + list.size() + " kare birleştirildi");
                    return;
                }
                byte[] best = list.get(0);
                if (list.size() > 1) {
                    busyMsg = "En net kare seçiliyor…";
                    double bs = -1;
                    for (byte[] j : list) {
                        double s = sharpness(j);
                        if (s > bs) { bs = s; best = j; }
                    }
                }
                if (jobType == JOB_DOC) {
                    busyMsg = "Belge hazırlanıyor…";
                    final Bitmap up = decodeUpright(best, 4000);
                    ui.post(() -> { if (up != null) docEd.open(up); else toast("Görüntü açılamadı"); });
                    return;
                }
                saveImage(best, "IMG", list.size() > 1 ? "Kaydedildi · " + list.size() + " kareden en neti" : "Kaydedildi");
            } catch (Throwable t) {
                toast("İşlenemedi: " + t.getMessage());
            } finally {
                busyMsg = null;
                capturing = false;
            }
        }, "kayit").start();
    }

    private void saveImage(byte[] jpg, String prefix, String msg) throws Exception {
        Uri u = Store.saveJpeg(this, jpg, prefix);
        lastUri = u;
        lastMime = "image/jpeg";
        final Bitmap t = thumbnail(jpg);
        ui.post(() -> {
            if (t != null) thumb.setImageBitmap(t);
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });
    }

    // ================== GECE BİRLEŞTİRME ==================
    private byte[] nightMerge(List<byte[]> list) throws Exception {
        int n = list.size();
        busyMsg = "Gece: kareler inceleniyor…";
        double[] sh = new double[n];
        double mx = 0;
        for (int i = 0; i < n; i++) { sh[i] = sharpness(list.get(i)); mx = Math.max(mx, sh[i]); }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        final double[] shf = sh;
        Arrays.sort(order, (a, b) -> Double.compare(shf[b], shf[a]));
        List<byte[]> sel = new ArrayList<>();
        for (int idx : order) if (sel.size() < 3 || sh[idx] >= mx * 0.6) sel.add(list.get(idx));

        int orientation = exifOrientation(sel.get(0));
        BitmapFactory.Options bo = new BitmapFactory.Options();
        bo.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(sel.get(0), 0, sel.get(0).length, bo);
        int sample = 1;
        while ((long) (bo.outWidth / sample) * (bo.outHeight / sample) > 13_000_000L) sample *= 2;

        ImageTools.Merger m = null;
        int[] buf = null;
        int w = 0, h = 0;
        for (int k = 0; k < sel.size(); k++) {
            busyMsg = "Gece: birleştiriliyor " + (k + 1) + "/" + sel.size();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeByteArray(sel.get(k), 0, sel.get(k).length, o);
            if (b == null) continue;
            if (m == null) { w = b.getWidth(); h = b.getHeight(); m = new ImageTools.Merger(w, h); buf = new int[w * h]; }
            if (b.getWidth() != w || b.getHeight() != h) { b.recycle(); continue; }
            b.getPixels(buf, 0, w, 0, 0, w, h);
            b.recycle();
            m.add(buf);
        }
        if (m == null) throw new Exception("kareler açılamadı");
        buf = null;
        busyMsg = "Gece: son düzeltmeler…";
        int[] res = m.result();
        m = null;
        Bitmap rb = Bitmap.createBitmap(res, w, h, Bitmap.Config.ARGB_8888);
        res = null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        rb.compress(Bitmap.CompressFormat.JPEG, 95, bos);
        rb.recycle();
        return withOrientation(bos.toByteArray(), orientation);
    }

    private static int exifOrientation(byte[] jpg) {
        try {
            return new ExifInterface(new ByteArrayInputStream(jpg)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception e) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    /** Birleştirilmiş görüntüye ilk karenin yön bilgisini yazar (pikselleri döndürmeden) */
    private byte[] withOrientation(byte[] jpg, int orientation) {
        if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) return jpg;
        File f = new File(getCacheDir(), "gece_tmp.jpg");
        try {
            try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(jpg); }
            ExifInterface ex = new ExifInterface(f.getAbsolutePath());
            ex.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(orientation));
            ex.saveAttributes();
            return Files.readAllBytes(f.toPath());
        } catch (Exception e) {
            return jpg;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    // ================== GÖRÜNTÜ YARDIMCILARI ==================
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
        int[] px = new int[w * h];
        bm.getPixels(px, 0, w, 0, 0, w, h);
        bm.recycle();
        byte[] g = ImageTools.gray(px);
        return ImageTools.lapVar(g, w, h, w / 5, h / 5, w * 3 / 5, h * 3 / 5);
    }

    static Bitmap decodeUpright(byte[] jpg, int maxSide) {
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpg, 0, jpg.length, b);
        int s = 1;
        while (Math.max(b.outWidth, b.outHeight) / s > maxSide) s *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = s;
        Bitmap bm = BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
        if (bm == null) return null;
        int rot = rotationOf(exifOrientation(jpg));
        if (rot == 0) return bm;
        Matrix m = new Matrix();
        m.postRotate(rot);
        Bitmap r = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
        if (r != bm) bm.recycle();
        return r;
    }

    private static int rotationOf(int exifOri) {
        if (exifOri == ExifInterface.ORIENTATION_ROTATE_90) return 90;
        if (exifOri == ExifInterface.ORIENTATION_ROTATE_180) return 180;
        if (exifOri == ExifInterface.ORIENTATION_ROTATE_270) return 270;
        return 0;
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
            int rot = rotationOf(exifOrientation(jpg));
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

    private Bitmap labelBitmap(String text) {
        Bitmap b = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF333333);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setTextSize(44);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(text, 80, 96, p);
        return b;
    }

    private void openLast() {
        if (lastUri == null) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(lastUri, lastMime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            toast("Açılamadı");
        }
    }

    // ================== CANLI ANALİZ (histogram, zebra, belge, barkod) ==================
    private final ImageReader.OnImageAvailableListener onAna = r -> {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            long now = SystemClock.uptimeMillis();
            boolean needDoc = mode == M_BELGE || (mode == M_OTO && docHint);
            boolean needScan = mode == M_BARKOD;
            if (!(hist || zebra || needDoc || needScan) || capturing || now - lastAna < 110) return;
            lastAna = now;
            int w = img.getWidth(), h = img.getHeight();
            Image.Plane yp = img.getPlanes()[0];
            ByteBuffer buf = yp.getBuffer();
            int rs = yp.getRowStride(), ps = yp.getPixelStride();
            if (yBuf == null || yBuf.length != w * h) yBuf = new byte[w * h];
            byte[] y = yBuf;
            if (ps == 1) {
                for (int row = 0; row < h; row++) { buf.position(row * rs); buf.get(y, row * w, w); }
            } else {
                for (int row = 0; row < h; row++) for (int x = 0; x < w; x++) y[row * w + x] = buf.get(row * rs + x * ps);
            }
            img.close();
            img = null;
            if (hist || zebra) histZebra(y, w, h);
            if (needDoc && (mode == M_BELGE || now - lastDocT > 400)) { lastDocT = now; docDetect(y, w, h); }
            if (needScan && now - lastScanT > 200) { lastScanT = now; scan(y, w, h, now); }
        } catch (Exception ignored) {
        } finally {
            if (img != null) img.close();
        }
    };

    private void histZebra(byte[] y, int w, int h) {
        int step = 4, bw = w / step, bh = h / step;
        int[] hs = new int[64];
        int[] zp = zebra ? new int[bw * bh] : null;
        zebraPhase = (zebraPhase + 1) & 7;
        for (int by = 0; by < bh; by++) {
            int row = by * step * w;
            for (int bx = 0; bx < bw; bx++) {
                int v = y[row + bx * step] & 255;
                hs[v >> 2]++;
                if (zp != null && v >= 250 && ((bx + by + zebraPhase) & 7) < 4) zp[by * bw + bx] = 0xC8FF2828;
            }
        }
        hist64 = hs;
        zebraBmp = zp != null ? Bitmap.createBitmap(zp, bw, bh, Bitmap.Config.ARGB_8888) : null;
    }

    private void docDetect(byte[] y, int w, int h) {
        int f = Math.max(1, w / 320);
        int[] wh = new int[2];
        byte[] s = ImageTools.down(y, w, h, f, wh);
        float[] q = ImageTools.detectDoc(s, wh[0], wh[1]);
        boolean good = false;
        if (q != null) {
            float[] d = new float[8];
            for (int k = 0; k < 4; k++) {
                float[] p = sensorToDisp(q[2 * k], q[2 * k + 1]);
                d[2 * k] = p[0];
                d[2 * k + 1] = p[1];
            }
            float[] prev = docQuad;
            if (prev != null) for (int i = 0; i < 8; i++) d[i] = prev[i] * 0.5f + d[i] * 0.5f;
            docQuad = d;
            good = ImageTools.looksLikeDocument(s, wh[0], wh[1], q);
        } else {
            docQuad = null;
        }
        docHits = good ? docHits + 1 : 0;
    }

    private void scan(byte[] y, int w, int h, long now) {
        if (zx == null) {
            zx = new MultiFormatReader();
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            zx.setHints(hints);
        }
        Result res = null;
        boolean rot = false;
        try {
            res = zx.decodeWithState(new BinaryBitmap(new HybridBinarizer(new PlanarYUVLuminanceSource(y, w, h, 0, 0, w, h, false))));
        } catch (ReaderException | RuntimeException ignored) {
        } finally { zx.reset(); }
        if (res == null) {
            byte[] rt = new byte[w * h];
            for (int yy = 0; yy < h; yy++) for (int xx = 0; xx < w; xx++) rt[xx * h + (h - 1 - yy)] = y[yy * w + xx];
            try {
                res = zx.decodeWithState(new BinaryBitmap(new HybridBinarizer(new PlanarYUVLuminanceSource(rt, h, w, 0, 0, h, w, false))));
                rot = true;
            } catch (ReaderException | RuntimeException ignored) {
            } finally { zx.reset(); }
        }
        if (res == null) {
            if (now - codeSeen > 700) codeQuad = null;
            return;
        }
        ResultPoint[] pts = res.getResultPoints();
        if (pts != null && pts.length > 0) {
            float[][] qd = new float[pts.length][];
            for (int i = 0; i < pts.length; i++) {
                float px = pts[i].getX(), py = pts[i].getY();
                float sx = rot ? py / w : px / w, sy = rot ? (h - 1 - px) / h : py / h;
                qd[i] = sensorToDisp(sx, sy);
            }
            codeQuad = qd;
        }
        codeSeen = now;
        final String text = res.getText();
        final String fmt = res.getBarcodeFormat().toString().replace('_', ' ');
        if (text != null && !text.equals(lastCode)) {
            lastCode = text;
            vib(60);
            ui.post(() -> {
                scanFmt.setText(fmt);
                scanText.setText(text);
                scanOpen.setVisibility(text.startsWith("http://") || text.startsWith("https://") ? View.VISIBLE : View.GONE);
                scanCard.setVisibility(View.VISIBLE);
            });
        }
    }

    // ================== PRO ==================
    private interface FloatFmt { String f(float v); }

    private interface FloatSet { void set(float v); }

    private void addSlider(String label, final float lo, final float hi, float cur, final boolean log, final FloatSet set, final FloatFmt fmt) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(Color.WHITE);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        row.addView(l, new LinearLayout.LayoutParams(dp(84), LinearLayout.LayoutParams.WRAP_CONTENT));
        SeekBar sb = new SeekBar(this);
        sb.setMax(1000);
        final TextView val = new TextView(this);
        val.setTextColor(Color.WHITE);
        val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        val.setGravity(Gravity.END);
        float c = ImageTools.clampf(cur, lo, hi);
        int prog = log ? (int) Math.round(1000 * Math.log(c / lo) / Math.log(hi / lo)) : Math.round(1000 * (c - lo) / (hi - lo));
        sb.setProgress(ImageTools.clamp(prog, 0, 1000));
        val.setText(fmt.f(c));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) return;
                float v = log ? (float) (lo * Math.pow(hi / lo, p / 1000.0)) : lo + (hi - lo) * p / 1000f;
                val.setText(fmt.f(v));
                set.set(v);
                updatePreview();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        row.addView(sb, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(val, new LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT));
        proPanel.addView(row);
    }

    private static String awbName(int m) {
        switch (m) {
            case CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT: return "Gün ışığı";
            case CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT: return "Bulutlu";
            case CameraMetadata.CONTROL_AWB_MODE_SHADE: return "Gölge";
            case CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT: return "Ampul";
            case CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT: return "Floresan";
            case CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT: return "Sıcak floresan";
            case CameraMetadata.CONTROL_AWB_MODE_TWILIGHT: return "Alacakaranlık";
            default: return "Otomatik";
        }
    }

    private void buildPro() {
        proPanel.removeAllViews();
        if (mode != M_PRO || ch == null) { proPanel.setVisibility(View.GONE); return; }
        proPanel.setVisibility(View.VISIBLE);
        boolean any = false;
        if (evRange != null && evRange.getUpper() > evRange.getLower() && !proManualExp) {
            any = true;
            addSlider("Pozlama ±", evRange.getLower(), evRange.getUpper(), proEv, false,
                    v -> proEv = Math.round(v), v -> String.format(Locale.US, "%+.1f EV", Math.round(v) * evStep));
        }
        if (manualSensor && expRange != null && isoRange != null) {
            any = true;
            final float eLo = Math.max(expRange.getLower(), 100_000L), eHi = Math.min(expRange.getUpper(), 1_000_000_000L);
            Long le = lastExp;
            Integer li = lastIso;
            addSlider("Enstantane", eLo, eHi, proExp > 0 ? proExp : (le != null ? le : 16_666_666L), true, v -> {
                proExp = (long) v;
                if (proIso == 0) { Integer i = lastIso; proIso = i != null ? i : 400; }
                proManualExp = true;
            }, v -> fmtExp((long) v));
            addSlider("ISO", isoRange.getLower(), isoRange.getUpper(), proIso > 0 ? proIso : (li != null ? li : 400), true, v -> {
                proIso = Math.round(v);
                if (proExp == 0) { Long e = lastExp; proExp = e != null ? e : 16_666_666L; }
                proManualExp = true;
            }, v -> "ISO " + Math.round(v));
        }
        if (hasAfOff && minFocus > 0 && !front) {
            any = true;
            Float fd = focusDiopter;
            addSlider("Elle odak", 0, minFocus, proManualFocus ? proFocus : (fd != null ? fd : 0), false, v -> {
                proFocus = v;
                proManualFocus = true;
                if (afLocked) { afLocked = false; lockRegions = null; tapX = -1; ui.post(() -> afChip.setVisibility(View.GONE)); }
            }, v -> v <= 0.05f ? "uzak" : String.format(Locale.US, "%.2f m", 1f / v));
        }
        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.CENTER);
        if (awbModes != null && awbModes.length > 1) {
            any = true;
            final TextView wb = chip("Beyaz: " + awbName(proAwb));
            wb.setOnClickListener(v -> {
                int idx = 0;
                for (int i = 0; i < awbModes.length; i++) if (awbModes[i] == proAwb) idx = i;
                int next = awbModes[(idx + 1) % awbModes.length];
                if (next == CameraMetadata.CONTROL_AWB_MODE_OFF) next = awbModes[(idx + 2) % awbModes.length];
                proAwb = next;
                wb.setText("Beyaz: " + awbName(proAwb));
                updatePreview();
            });
            btns.addView(wb, wrapLp(4));
        }
        if (any) {
            TextView reset = chip("Hepsi otomatik");
            reset.setOnClickListener(v -> {
                proManualExp = false;
                proManualFocus = false;
                proExp = 0;
                proIso = 0;
                proEv = 0;
                proAwb = CameraMetadata.CONTROL_AWB_MODE_AUTO;
                buildPro();
                updatePreview();
            });
            btns.addView(reset, wrapLp(4));
        } else {
            TextView t = new TextView(this);
            t.setText("Bu kamera elle ayar (enstantane, ISO, odak) desteklemiyor.");
            t.setTextColor(0xAAFFFFFF);
            btns.addView(t);
        }
        proPanel.addView(btns);
    }

    // ================== VİDEO ==================
    private void startRecording() {
        if (dev == null || recording || bg == null) return;
        final boolean audio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (!audio && !askedAudio) {
            askedAudio = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO_REC);
            return;
        }
        bg.post(() -> {
            try {
                recUri = Store.newVideo(this);
                if (recUri == null) throw new Exception("video dosyası oluşturulamadı");
                recPfd = getContentResolver().openFileDescriptor(recUri, "w");
                rec = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
                if (audio) rec.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
                rec.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                rec.setOutputFile(recPfd.getFileDescriptor());
                int vw = videoSize.getWidth();
                rec.setVideoEncodingBitRate(vw >= 3840 ? 40_000_000 : vw >= 1920 ? 16_000_000 : 8_000_000);
                rec.setVideoFrameRate(30);
                rec.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
                rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                if (audio) {
                    rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                    rec.setAudioEncodingBitRate(128_000);
                    rec.setAudioSamplingRate(48_000);
                }
                rec.setOrientationHint(jpegOrientation());
                rec.prepare();
                recSurface = rec.getSurface();
                if (sess != null) { sess.close(); sess = null; }
                dev.createCaptureSession(Arrays.asList(previewSurface, recSurface), new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession s) {
                        sess = s;
                        try {
                            recording = true;
                            pb = newPreviewBuilder();
                            s.setRepeatingRequest(pb.build(), previewCb, bg);
                            rec.start();
                            recStart = SystemClock.uptimeMillis();
                            vib(40);
                        } catch (Exception e) {
                            recFailed(e);
                        }
                    }
                    @Override
                    public void onConfigureFailed(CameraCaptureSession s) { recFailed(new Exception("oturum kurulamadı")); }
                }, bg);
            } catch (Exception e) {
                recFailed(e);
            }
        });
    }

    private void recFailed(Exception e) {
        recording = false;
        try { if (rec != null) rec.release(); } catch (Exception ignored) { }
        rec = null;
        recSurface = null;
        try { if (recPfd != null) recPfd.close(); } catch (Exception ignored) { }
        recPfd = null;
        if (recUri != null) Store.delete(this, recUri);
        recUri = null;
        toast("Video başlatılamadı: " + e.getMessage());
        if (dev != null) startPreview();
    }

    private void stopRecording() {
        if (bg == null) return;
        bg.post(() -> {
            finishRecording();
            if (dev != null) startPreview();
        });
    }

    /** Kaydı durdurur ve galeriye yayınlar */
    private void finishRecording() {
        if (!recording) return;
        recording = false;
        boolean ok = true;
        try { sess.stopRepeating(); } catch (Exception ignored) { }
        try { rec.stop(); } catch (RuntimeException e) { ok = false; }
        try { rec.release(); } catch (Exception ignored) { }
        rec = null;
        recSurface = null;
        try { if (recPfd != null) recPfd.close(); } catch (Exception ignored) { }
        recPfd = null;
        final Uri u = recUri;
        recUri = null;
        if (u == null) return;
        if (ok) {
            Store.publishVideo(this, u);
            lastUri = u;
            lastMime = "video/mp4";
            Bitmap t = null;
            try { t = getContentResolver().loadThumbnail(u, new Size(240, 240), null); } catch (Exception ignored) { }
            final Bitmap tf = t != null ? t : labelBitmap("▶");
            ui.post(() -> { thumb.setImageBitmap(tf); Toast.makeText(this, "Video kaydedildi", Toast.LENGTH_SHORT).show(); });
        } else {
            Store.delete(this, u);
            toast("Video çok kısa, kaydedilmedi");
        }
    }

    // ================== SESLE ÇEKİM ==================
    private void startVoice() {
        if (!voice || mode == M_VIDEO || voiceRun) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO_VOICE);
            return;
        }
        voiceRun = true;
        new Thread(() -> {
            AudioRecord ar = null;
            try {
                int sr = 16000;
                int min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                ar = new AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 4096));
                if (ar.getState() != AudioRecord.STATE_INITIALIZED) { toast("Mikrofon açılamadı"); return; }
                ar.startRecording();
                short[] b = new short[1024];
                float floor = 0.02f;
                long last = 0;
                while (voiceRun) {
                    int n = ar.read(b, 0, b.length);
                    if (n <= 0) continue;
                    int pk = 0;
                    for (int i = 0; i < n; i++) { int a = Math.abs(b[i]); if (a > pk) pk = a; }
                    float p = pk / 32768f;
                    long now = SystemClock.uptimeMillis();
                    if (p > 0.5f && p > floor * 5 && now - last > 2500) {
                        last = now;
                        ui.post(() -> {
                            if (!capturing && !docEd.isOpen() && mode != M_VIDEO && mode != M_BARKOD) { toast("Ses algılandı"); onShutter(); }
                        });
                    }
                    floor = floor * 0.97f + Math.min(p, 0.3f) * 0.03f;
                }
            } catch (SecurityException e) {
                toast("Mikrofon izni yok");
            } catch (Exception e) {
                toast("Sesle çekim hatası: " + e.getMessage());
            } finally {
                voiceRun = false;
                if (ar != null) { try { ar.stop(); } catch (Exception ignored) { } ar.release(); }
            }
        }, "ses").start();
    }

    private void stopVoice() { voiceRun = false; }

    // ================== BELGE DÜZENLEYİCİ GERİ ÇAĞRILARI ==================
    @Override
    public void onDocJpeg(final byte[] jpg) {
        new Thread(() -> {
            try { saveImage(jpg, "BELGE", "Belge kaydedildi"); } catch (Exception e) { toast("Kaydedilemedi: " + e.getMessage()); }
        }, "belge-kayit").start();
    }

    @Override
    public void onDocPdfPage(byte[] jpg, int w, int h) {
        pdfJpegs.add(jpg);
        pdfSizes.add(new int[]{w, h});
        updatePdfBar();
        Toast.makeText(this, "PDF'e eklendi (" + pdfJpegs.size() + " sayfa). Sonraki sayfayı çekin ya da \"PDF kaydet\"e basın.", Toast.LENGTH_LONG).show();
    }

    @Override
    public void onDocMessage(String msg) { toast(msg); }

    private void savePdf() {
        if (pdfJpegs.isEmpty()) return;
        final List<byte[]> js = new ArrayList<>(pdfJpegs);
        final List<int[]> sz = new ArrayList<>(pdfSizes);
        busyMsg = "PDF hazırlanıyor…";
        new Thread(() -> {
            try {
                Uri u = Store.savePdf(this, ImageTools.makePdf(js, sz), "BELGE");
                lastUri = u;
                lastMime = "application/pdf";
                final Bitmap t = labelBitmap("PDF");
                ui.post(() -> {
                    pdfJpegs.clear();
                    pdfSizes.clear();
                    updatePdfBar();
                    thumb.setImageBitmap(t);
                    Toast.makeText(this, "PDF kaydedildi: İndirilenler/Kamera (" + js.size() + " sayfa)", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                toast("PDF kaydedilemedi: " + e.getMessage());
            } finally {
                busyMsg = null;
            }
        }, "pdf").start();
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
        private final Paint bp = new Paint();
        private final Matrix mtx = new Matrix();
        private float downX, downY;
        private boolean multi;

        Overlay(Context c) { super(c); }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            scaleDet.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    multi = false;
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    multi = true;
                    break;
                case MotionEvent.ACTION_UP:
                    if (!multi && Math.hypot(e.getX() - downX, e.getY() - downY) < 30 * getResources().getDisplayMetrics().density) {
                        if (setPanel.getVisibility() == View.VISIBLE) setPanel.setVisibility(View.GONE);
                        else tapFocus(e.getX() / getWidth(), e.getY() / getHeight());
                        performClick();
                    }
                    break;
                default:
                    break;
            }
            return true;
        }

        @Override
        public boolean performClick() { return super.performClick(); }

        private void poly(Canvas c, float[][] pts, int w, int h, int stroke, int fill) {
            if (pts == null || pts.length < 2) return;
            Path path = new Path();
            for (int i = 0; i < pts.length; i++) {
                float x = pts[i][0] * w, y = pts[i][1] * h;
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            path.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(fill);
            c.drawPath(path, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3 * getResources().getDisplayMetrics().density);
            p.setColor(stroke);
            c.drawPath(path, p);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float d = getResources().getDisplayMetrics().density;
            boolean photoLike = mode != M_VIDEO && mode != M_BARKOD;

            // Zebra (sensör yönündeki küçük maskeyi ekrana döndürerek)
            Bitmap zb = zebraBmp;
            if (zebra && zb != null && mode != M_VIDEO) {
                float bw = zb.getWidth(), bh = zb.getHeight();
                float[] v;
                switch (sensorOri) {
                    case 90: v = new float[]{0, -w / bh, w, h / bw, 0, 0, 0, 0, 1}; break;
                    case 270: v = new float[]{0, w / bh, 0, -h / bw, 0, h, 0, 0, 1}; break;
                    case 180: v = new float[]{-w / bw, 0, w, 0, -h / bh, h, 0, 0, 1}; break;
                    default: v = new float[]{w / bw, 0, 0, 0, h / bh, 0, 0, 0, 1}; break;
                }
                mtx.setValues(v);
                c.drawBitmap(zb, mtx, bp);
            }
            // Izgara
            if (grid) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1 * d);
                p.setColor(0x66FFFFFF);
                for (int i = 1; i < 3; i++) {
                    c.drawLine(w * i / 3f, 0, w * i / 3f, h, p);
                    c.drawLine(0, h * i / 3f, w, h * i / 3f, p);
                }
            }
            // Belge çerçevesi
            float[] dq = docQuad;
            if (dq != null && (mode == M_BELGE || (mode == M_OTO && docHint && docHits >= 3))) {
                poly(c, new float[][]{{dq[0], dq[1]}, {dq[2], dq[3]}, {dq[4], dq[5]}, {dq[6], dq[7]}}, w, h, 0xFF5DF58A, 0x265DF58A);
            }
            // Barkod
            float[][] cq = codeQuad;
            if (mode == M_BARKOD && cq != null) {
                if (cq.length >= 3) poly(c, cq, w, h, 0xFF4AA3FF, 0x334AA3FF);
                else {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(4 * d);
                    p.setColor(0xFF4AA3FF);
                    c.drawLine(cq[0][0] * w, cq[0][1] * h, cq[cq.length - 1][0] * w, cq[cq.length - 1][1] * h, p);
                }
            }
            // Ufuk çizgisi / su terazisi
            if (level && hasAcc && mode != M_BARKOD) {
                float g = (float) Math.sqrt(ax * ax + ay * ay + az * az);
                boolean flat = g > 1 && Math.abs(az) / g > 0.8f;
                if (!flat && g > 1) {
                    float th = (float) Math.toDegrees(Math.atan2(-ax, ay));
                    float dv = th - Math.round(th / 90f) * 90f;
                    boolean ok = Math.abs(dv) < 1f;
                    if (ok && !wasLevel) vib(10);
                    wasLevel = ok;
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2 * d);
                    p.setColor(ok ? 0xFF5DF58A : 0xBBFFFFFF);
                    c.save();
                    c.rotate(-dv, w / 2f, h / 2f);
                    c.drawLine(w * 0.2f, h / 2f, w * 0.8f, h / 2f, p);
                    c.restore();
                } else if (flat && mode == M_BELGE) {
                    float tx = (float) Math.toDegrees(Math.atan2(ax, Math.abs(az))), ty = (float) Math.toDegrees(Math.atan2(ay, Math.abs(az)));
                    boolean ok = Math.abs(tx) < 2 && Math.abs(ty) < 2;
                    if (ok && !wasLevel) vib(15);
                    wasLevel = ok;
                    float R = 60 * d, cx = w / 2f, cy = h / 2f;
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2 * d);
                    p.setColor(ok ? 0xFF5DF58A : 0x99FFFFFF);
                    c.drawCircle(cx, cy, R, p);
                    c.drawCircle(cx, cy, 15 * d, p);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(ok ? 0xFF5DF58A : 0xFFFFD54A);
                    c.drawCircle(cx + ImageTools.clampf(tx * 2.5f * d, -R + 11 * d, R - 11 * d), cy + ImageTools.clampf(-ty * 2.5f * d, -R + 11 * d, R - 11 * d), 11 * d, p);
                }
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
            // Sakinlik göstergesi + OTO kararı
            float ly = 8 * d;
            p.setTextSize(12 * d);
            if (hasGyro && steady && photoLike) {
                boolean calm = gyro <= steadyTarget();
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x80000000);
                c.drawRoundRect(8 * d, ly, 112 * d, ly + 24 * d, 12 * d, 12 * d, p);
                p.setColor(calm ? 0xFF5DF58A : 0xFFFF5252);
                c.drawCircle(22 * d, ly + 12 * d, 5 * d, p);
                p.setColor(Color.WHITE);
                c.drawText(calm ? "Sakin an" : "Kıpırdıyor", 33 * d, ly + 16 * d, p);
                ly += 30 * d;
            }
            if (mode == M_OTO) {
                String t = autoNight ? "OTO · Gece birleştirme" : "OTO · Normal";
                float tw = p.measureText(t);
                p.setStyle(Paint.Style.FILL);
                p.setColor(autoNight ? 0xCC3949AB : 0x80000000);
                c.drawRoundRect(8 * d, ly, 8 * d + tw + 20 * d, ly + 24 * d, 12 * d, 12 * d, p);
                p.setColor(Color.WHITE);
                c.drawText(t, 18 * d, ly + 16 * d, p);
            }
            // Histogram
            int[] hs = hist64;
            if (hist && hs != null && photoLike) {
                float hw = 128 * d, hh = 56 * d, hx = w - hw - 8 * d, hy = 8 * d;
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x80000000);
                c.drawRect(hx, hy, hx + hw, hy + hh, p);
                int mx = 1;
                long tot = 0;
                for (int v : hs) { mx = Math.max(mx, v); tot += v; }
                p.setColor(0xDDFFFFFF);
                float bwid = hw / 64f;
                for (int i = 0; i < 64; i++) {
                    float bh = (float) Math.sqrt(hs[i] / (double) mx) * (hh - 4 * d);
                    c.drawRect(hx + i * bwid, hy + hh - bh, hx + (i + 1) * bwid, hy + hh, p);
                }
                if (hs[63] > tot * 0.02) { p.setColor(0xFFFF5252); c.drawRect(hx + hw - 3 * d, hy, hx + hw, hy + hh, p); }
                if (hs[0] > tot * 0.02) { p.setColor(0xFF4AA3FF); c.drawRect(hx, hy, hx + 3 * d, hy + hh, p); }
            }
            // Geri sayım
            int cd = countdown;
            if (cd > 0) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xEEFFFFFF);
                p.setTextSize(96 * d);
                p.setTextAlign(Paint.Align.CENTER);
                c.drawText(String.valueOf(cd), w / 2f, h / 2f + 32 * d, p);
                p.setTextAlign(Paint.Align.LEFT);
            }
            // Çekim anı
            if (SystemClock.uptimeMillis() < flashUntil) c.drawColor(0x66FFFFFF);
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
            float r = Math.min(getWidth(), getHeight()) / 2f, d = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(4 * d);
            p.setColor(Color.WHITE);
            c.drawCircle(cx, cy, r - 3 * d, p);
            p.setStyle(Paint.Style.FILL);
            if (mode == M_VIDEO) {
                p.setColor(0xFFD32F2F);
                if (recording) {
                    float s = r * 0.42f;
                    c.drawRoundRect(cx - s, cy - s, cx + s, cy + s, 6 * d, 6 * d, p);
                } else c.drawCircle(cx, cy, r - 10 * d, p);
            } else {
                p.setColor(mode == M_BARKOD ? 0xFF4AA3FF : capturing ? 0xFFBBBBBB : nightNow() ? 0xFF9FA8DA : Color.WHITE);
                c.drawCircle(cx, cy, r - 10 * d, p);
            }
        }

        @Override
        public boolean performClick() {
            boolean r = super.performClick();
            invalidate();
            return r;
        }
    }
}
