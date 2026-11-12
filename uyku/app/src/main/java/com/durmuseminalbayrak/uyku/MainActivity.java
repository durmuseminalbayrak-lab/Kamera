package com.durmuseminalbayrak.uyku;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    static final int BG = 0xFF0B1020, CARD = 0xFF151C30, CARD2 = 0xFF1E2742, ACC = 0xFF7AA2FF, TXT = 0xFFE8ECF5,
            SUB = 0xFF8C96B0, WARN = 0xFFFFB86B, GOOD = 0xFF6EE7A8, BAD = 0xFFFF6B81;
    static final Locale TR = Locale.forLanguageTag("tr");

    private FrameLayout root;
    private final Handler h = new Handler(Looper.getMainLooper());
    private int screen = -1;          // 0 ana, 1 canlı, 2 rapor
    private SharedPreferences sp;
    private Runnable afterPerm, afterBattery;
    private boolean liveWasTest;
    private long startReq;

    private TextView lvClock, lvStatus, lvRate, lvQual, lvInfo, lvDiag;
    private WaveView lvWave;
    private DiagView lvDiagView;
    private int lastResets = -1;
    private long lastResetChange;
    private final Runnable tick = this::liveTick;

    // ------------------------------------------------------------------ yaşam döngüsü

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("uyku", MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (afterBattery != null) {
            Runnable r = afterBattery;
            afterBattery = null;
            r.run();
            return;
        }
        if (SleepService.running != null) showLive();
        else if (screen != 2) showHome();
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
    }

    @Override
    public void onBackPressed() {
        if (screen == 1 || screen == 2) showHome();
        else super.onBackPressed();
    }

    // ------------------------------------------------------------------ başlatma / izinler

    private void start(boolean test) {
        Runnable go = () -> {
            SleepService.lastError = null;
            Intent it = new Intent(this, SleepService.class).setAction(test ? SleepService.ACT_TEST : SleepService.ACT_START);
            startForegroundService(it);
            startReq = System.currentTimeMillis();
            liveWasTest = test;
            h.postDelayed(this::showLive, 400);
        };
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        Runnable next = () -> batteryThen(go, test);
        if (!need.isEmpty()) {
            afterPerm = next;
            requestPermissions(need.toArray(new String[0]), 5);
        } else next.run();
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] perms, int[] res) {
        if (rc != 5) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && afterPerm != null) {
            Runnable r = afterPerm;
            afterPerm = null;
            r.run();
        } else toast("Mikrofon izni olmadan nefes ölçülemez");
    }

    private boolean batteryFree() {
        PowerManager pm = getSystemService(PowerManager.class);
        return pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void batteryThen(Runnable go, boolean test) {
        if (test || batteryFree() || sp.getBoolean("pilSoruldu", false)) {
            go.run();
            return;
        }
        sp.edit().putBoolean("pilSoruldu", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("Gece boyunca kesilmesin")
                .setMessage("Telefon pil tasarrufu için uygulamayı gece durdurabilir. Açılan ekranda bu uygulamanın "
                        + "pil kısıtlamasını kaldır (\"İzin ver\" ya da Pil tasarrufu → \"Kısıtlama yok\"). Sonra geri dön; takip başlayacak.")
                .setPositiveButton("Ayarı aç", (d, w) -> {
                    afterBattery = go;
                    openBatterySettings();
                })
                .setNegativeButton("Şimdilik geç", (d, w) -> go.run())
                .show();
    }

    private void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {
                toast("Ayarlar açılamadı");
            }
        }
    }

    private void stopTracking() {
        startService(new Intent(this, SleepService.class).setAction(SleepService.ACT_STOP));
        toast("Bitiriliyor…");
    }

    // ------------------------------------------------------------------ ana ekran

    private void showHome() {
        screen = 0;
        h.removeCallbacks(tick);
        setBright(-1f);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root.removeAllViews();
        ScrollView sv = new ScrollView(this);
        LinearLayout col = vcol();
        col.setPadding(dp(20), dp(28), dp(20), dp(36));
        sv.addView(col);

        col.addView(tv("Uyku", 32, TXT, true));
        TextView sub = tv("Telefonun duyulmayan ses dalgalarıyla nefesini, hareketini ve horlamanı izler. "
                + "Ses kaydedilmez; uygulamanın internet izni yok, her şey telefonda kalır.", 14, SUB, false);
        sub.setPadding(0, dp(6), 0, dp(18));
        col.addView(sub);

        SleepService s = SleepService.running;
        if (s != null) {
            LinearLayout c = card();
            c.addView(tv(s.test ? "Yerleşim testi sürüyor" : "Uyku takibi sürüyor", 17, GOOD, true));
            c.addView(tv(dur((System.currentTimeMillis() - s.startMs) / 60000f) + " önce başladı", 13, SUB, false));
            c.addView(button("Canlı ekranı aç", ACC, BG, v -> showLive()), lp(-1, dp(52), 0, dp(12), 0, 0));
            col.addView(c);
        } else {
            col.addView(button("Uykuya başla", ACC, BG, v -> start(false)), lp(-1, dp(64), 0, 0, 0, 0));
            col.addView(button("Yerleşimi test et", CARD2, TXT, v -> start(true)), lp(-1, dp(52), 0, dp(10), 0, 0));
        }
        String err = SleepService.lastError;
        if (err != null) {
            TextView e = tv(err, 13, BAD, false);
            e.setPadding(0, dp(10), 0, 0);
            col.addView(e);
        }

        File[] files = nights();
        if (files.length > 0) {
            col.addView(section("Son gece"));
            col.addView(nightCard(files[0], true));
            if (files.length > 1) {
                col.addView(section("Geçmiş geceler"));
                for (int i = 1; i < Math.min(files.length, 30); i++) col.addView(nightCard(files[i], false));
            }
        }

        col.addView(section("Ayarlar"));
        LinearLayout set = card();
        String[] lv = {"Düşük", "Orta", "Yüksek"};
        TextView level = tv("Sinyal gücü: " + lv[sp.getInt("seviye", 1)], 15, TXT, false);
        level.setPadding(0, dp(6), 0, dp(6));
        level.setOnClickListener(v -> {
            int k = (sp.getInt("seviye", 1) + 1) % 3;
            sp.edit().putInt("seviye", k).apply();
            ((TextView) v).setText("Sinyal gücü: " + lv[k]);
        });
        set.addView(level);
        set.addView(tv("Dokunarak değiştir. Test ekranında sinyal zayıfsa yükselt; bir ses duyuyorsan düşür.", 12, SUB, false));
        boolean free = batteryFree();
        TextView bat = tv(free ? "Pil kısıtlaması: kaldırılmış ✓" : "Pil kısıtlaması: açık — gece kesilebilir. Kaldırmak için dokun.", 15, free ? GOOD : WARN, false);
        bat.setPadding(0, dp(14), 0, dp(4));
        if (!free) bat.setOnClickListener(v -> openBatterySettings());
        set.addView(bat);
        col.addView(set);

        col.addView(section("Nasıl kullanılır?"));
        LinearLayout how = card();
        how.addView(tv("İki yolla ölçer; uygulama hangisi iyi çalışıyorsa onu kullanır:\n"
                + "1) Sonar: telefonu komodine ya da yatağın kenarına koy, göğsüne 30–80 cm uzakta olsun, alt kenarı "
                + "(hoparlör ve mikrofon) sana baksın. Göğse dayama: o kadar yakında yankı hoparlörün sesine karışır.\n"
                + "2) Hareket sensörü: sırt üstü yatarken telefonu göğsüne ya da karnına koyarsan nefesi telefonun "
                + "eğiminden ölçer. Yatağın üstünde, vücuduna yakın dururken de hareketleri algılar.\n"
                + "• Telefonu elde tutma; el titremesi hareket sayılır.\n"
                + "• Şarja tak. Ekran kapalıyken de çalışır. Kulaklık bağlıysa çıkar; müzik ya da beyaz gürültü çalma.\n"
                + "• İlk gece önce \"Yerleşimi test et\". Normal nefes al; \"Sinyal iyi/orta\" ve düzenli dalga görürsen tamam. "
                + "Nefes bulunamazsa ekran nedenini yazar.\n"
                + "• Sabah \"Bitir\"e basılı tut ya da bildirimdeki \"Bitir\"e dokun; rapor açılır.\n"
                + "• Tıbbi cihaz değildir. Sık nefes duraklaması görürsen bir uyku uzmanına danış.", 14, TXT, false));
        col.addView(how);
        root.addView(sv);
    }

    private File[] nights() {
        File[] fs = SleepService.dir(this).listFiles((d, n) -> n.endsWith(".uyku"));
        if (fs == null) return new File[0];
        Arrays.sort(fs, (a, b) -> b.getName().compareTo(a.getName()));
        return fs;
    }

    private View nightCard(File f, boolean big) {
        LinearLayout c = card();
        try {
            Night n = Night.read(f);
            SleepAnalyzer.Report r = SleepAnalyzer.analyze(n);
            c.addView(tv(nightTitle(n.startMillis), big ? 17 : 15, TXT, true));
            String line = r.onset < 0 ? "Uyku algılanamadı · " + dur(r.tibMin) + " kayıt"
                    : dur(r.tstMin) + " uyku · verim %" + Math.round(r.effPct)
                    + (r.rateMed == r.rateMed ? String.format(TR, " · nefes %.0f/dk", r.rateMed) : "")
                    + (r.pauseCount > 0 ? " · duraklama " + r.pauseCount : "");
            c.addView(tv(line, 13, SUB, false));
        } catch (IOException e) {
            c.addView(tv(f.getName() + " okunamadı", 13, BAD, false));
        }
        c.setOnClickListener(v -> showReport(f));
        c.setOnLongClickListener(v -> {
            confirmDelete(f);
            return true;
        });
        LinearLayout.LayoutParams l = lp(-1, -2, 0, dp(8), 0, 0);
        c.setLayoutParams(l);
        return c;
    }

    private void confirmDelete(File f) {
        new AlertDialog.Builder(this)
                .setTitle("Bu gece silinsin mi?")
                .setPositiveButton("Sil", (d, w) -> {
                    if (f.delete()) toast("Silindi");
                    showHome();
                })
                .setNegativeButton("Vazgeç", null)
                .show();
    }

    // ------------------------------------------------------------------ canlı ekran

    private void showLive() {
        SleepService s = SleepService.running;
        if (s == null) {
            if (System.currentTimeMillis() - startReq < 4000 && SleepService.lastError == null) {
                h.postDelayed(this::showLive, 300);   // servis daha ayağa kalkmadı
                return;
            }
            showHome();
            return;
        }
        screen = 1;
        liveWasTest = s.test;
        root.removeAllViews();
        LinearLayout col = vcol();
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(20), dp(36), dp(20), dp(28));

        lvClock = tv("", 54, 0xFF9AA5C4, false);
        lvClock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        lvClock.setGravity(Gravity.CENTER);
        col.addView(lvClock);
        lvStatus = tv("", 14, SUB, false);
        lvStatus.setGravity(Gravity.CENTER);
        col.addView(lvStatus);

        lvRate = tv("—", 72, TXT, false);
        lvRate.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        lvRate.setGravity(Gravity.CENTER);
        lvRate.setPadding(0, dp(26), 0, 0);
        col.addView(lvRate);
        TextView unit = tv("nefes / dk", 14, SUB, false);
        unit.setGravity(Gravity.CENTER);
        col.addView(unit);
        lvQual = tv("", 16, SUB, true);
        lvQual.setGravity(Gravity.CENTER);
        lvQual.setPadding(0, dp(10), 0, dp(8));
        col.addView(lvQual);

        lvWave = new WaveView(this);
        col.addView(lvWave, lp(-1, dp(130), 0, dp(4), 0, dp(4)));
        lvInfo = tv("", 14, SUB, false);
        lvInfo.setGravity(Gravity.CENTER);
        col.addView(lvInfo);
        lvDiagView = null;
        if (s.test) {
            lvDiagView = new DiagView(this);
            col.addView(lvDiagView, lp(-1, -2, 0, dp(10), 0, 0));
        }

        if (s.test) {
            TextView tip = tv("Telefonu bırak (elde tutma), normal nefes al ve kıpırdama. Dalga düzenli inip çıkıyorsa yerleşim tamam. "
                    + "Sonar için 30–80 cm uzak, alt kenarı sana dönük; hareket sensörü için sırt üstü yatıp göğsüne koy. "
                    + "Sorun olursa bu ekranın görüntüsünü gönder.", 13, SUB, false);
            tip.setGravity(Gravity.CENTER);
            tip.setPadding(0, dp(14), 0, 0);
            col.addView(tip);
        }

        View spacer = new View(this);
        col.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1f));
        lvDiag = tv("", 11, 0xFF5D6785, false);
        lvDiag.setGravity(Gravity.CENTER);
        col.addView(lvDiag);

        if (s.test) {
            col.addView(button("Testi bitir", CARD2, TXT, v -> stopTracking()), lp(-1, dp(56), 0, dp(12), 0, 0));
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            setBright(-1f);
        } else {
            TextView stop = button("Bitirmek için basılı tut", CARD2, TXT, v -> toast("Bitirmek için basılı tut"));
            stop.setOnLongClickListener(v -> {
                stopTracking();
                return true;
            });
            col.addView(stop, lp(-1, dp(56), 0, dp(12), 0, 0));
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            setBright(0.03f);
        }
        TextView home = tv("Ana ekran", 13, SUB, false);
        home.setGravity(Gravity.CENTER);
        home.setPadding(0, dp(12), 0, 0);
        home.setOnClickListener(v -> showHome());
        col.addView(home);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(col);
        root.addView(sv);
        h.removeCallbacks(tick);
        h.post(tick);
    }

    private void liveTick() {
        if (screen != 1) return;
        SleepService s = SleepService.running;
        if (s == null) {
            File f = SleepService.lastFile;
            if (!liveWasTest && f != null && f.exists()) {
                SleepService.lastFile = null;
                showReport(f);
            } else {
                if (!liveWasTest && SleepService.lastError == null) toast("Kayıt 1 dakikadan kısa olduğu için saklanmadı");
                showHome();
            }
            return;
        }
        NightRecorder r = s.rec;
        lvClock.setText(new SimpleDateFormat("HH:mm", TR).format(new Date()));
        lvStatus.setText((s.test ? "Yerleşim testi · " : "Takip sürüyor · ") + dur((System.currentTimeMillis() - s.startMs) / 60000f));
        if (r == null) {
            lvQual.setText("Başlatılıyor…");
            h.postDelayed(tick, 300);
            return;
        }
        if (!s.micReady) {
            lvRate.setText("—");
            lvQual.setText(s.status != null ? s.status : "Hazırlanıyor…");
            lvQual.setTextColor(SUB);
            lvDiag.setText(s.probeLog);
            h.postDelayed(tick, 300);
            return;
        }
        Sonar so = r.sonar;
        if (so.resets != lastResets) {
            if (lastResets >= 0) lastResetChange = System.currentTimeMillis();
            lastResets = so.resets;
        }
        boolean v = r.valid;
        float qv = r.quality;
        lvRate.setText(v ? String.format(TR, "%.0f", r.rate) : "—");
        String q;
        int qc;
        if (r.moving) { q = "Hareket algılandı — telefon ya da vücut oynuyor"; qc = WARN; }
        else if (r.inPause) { q = "Nefes duraklaması olabilir"; qc = BAD; }
        else if (v) {
            q = (qv >= 0.7f ? "Sinyal iyi" : "Sinyal orta") + String.format(TR, " (%.2f) · %s", qv,
                    r.activeSource == 2 ? "hareket sensörü" : "sonar");
            qc = qv >= 0.7f ? GOOD : WARN;
        }
        else if (so.timeSec() < 14) { q = "Nefes aranıyor… (ilk 10–15 sn)"; qc = SUB; }
        else { q = reason(s, r); qc = BAD; }
        lvQual.setText(q);
        lvQual.setTextColor(qc);
        lvWave.set(r.wave(), v ? ACC : 0xFF4A5577);
        if (lvDiagView != null) lvDiagView.set(so);
        float lvl = r.sound.levelDb;
        lvInfo.setText((s.weakUltrasound ? "⚠ Bu mikrofon/hoparlör ultrasonu zayıf iletiyor; ölçüm zor olabilir.\n" : "")
                + "Duraklama " + r.totalPauses + " · Hareket " + r.totalMoves + " · Horlama " + r.totalSnores
                + (lvl == lvl ? String.format(TR, " · Ses %.0f dB", lvl) : ""));
        int cm = so.distanceCm();
        Accel ac = r.accel;
        lvDiag.setText(String.format(TR, "doğrudan %.0f dB · yankı %.0f dB · taban %.0f dB · ultrason SNR %.0f dB · tepe %.0f dBFS\n"
                        + "mikrofon: %s · ses %%%d · mesafe %s · sıfırlama %d · kayıp %d/%d · kayma %.0f ppm\n"
                        + "sonar q %.2f · ivme q %.2f (%s, %.3f m/s²)\n%s",
                so.directDb, so.echoDb, so.noiseDb, so.bandSnrDb, s.rawPeakDb,
                s.source, Math.round(100 * s.volScale), cm >= 0 ? "~" + cm + " cm" : "—", so.resets, s.drops, s.underruns, so.driftPpm(),
                so.quality, ac.quality, ac.present ? (ac.valid ? "nefes" : "yok") : "sensör yok", ac.ampRms, s.probeLog));
        h.postDelayed(tick, 250);
    }

    /** Nefes bulunamadığında olası nedeni sade dille söyle. */
    private String reason(SleepService s, NightRecorder r) {
        Sonar so = r.sonar;
        float snr = so.bandSnrDb;
        if (snr == snr && snr < 15)
            return String.format(TR, "Ultrason alınamıyor (SNR %.0f dB): hoparlör ya da mikrofon 18–20 kHz'i geçirmiyor", snr);
        if (s.rawPeakDb == s.rawPeakDb && s.rawPeakDb > -1f) return "Mikrofon doyuyor: Ayarlar'dan sinyal gücünü düşür";
        if (System.currentTimeMillis() - lastResetChange < 20000)
            return "Sinyal kararsız: telefon oynuyor ya da ses sistemi araya giriyor";
        if (so.echoDb == so.echoDb && so.noiseDb == so.noiseDb && so.echoDb < so.noiseDb + 4)
            return "Yankı zayıf: telefonu 30–60 cm'ye yaklaştır, alt kenarı göğsüne dönsün";
        return "Nefes bulunamadı: telefonu sabit bir yere bırak (elde tutma), 30–60 cm uzağa koy";
    }

    // ------------------------------------------------------------------ rapor

    private void showReport(File f) {
        Night n;
        try {
            n = Night.read(f);
        } catch (IOException e) {
            toast("Kayıt okunamadı: " + e.getMessage());
            showHome();
            return;
        }
        screen = 2;
        h.removeCallbacks(tick);
        setBright(-1f);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        SleepAnalyzer.Report r = SleepAnalyzer.analyze(n);
        root.removeAllViews();
        ScrollView sv = new ScrollView(this);
        LinearLayout col = vcol();
        col.setPadding(dp(16), dp(18), dp(16), dp(36));
        sv.addView(col);

        TextView back = tv("← Geri", 15, ACC, false);
        back.setPadding(0, 0, 0, dp(12));
        back.setOnClickListener(v -> showHome());
        col.addView(back);
        col.addView(tv(nightTitle(n.startMillis), 24, TXT, true));
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", TR);
        col.addView(tv(hm.format(new Date(n.startMillis)) + " → " + hm.format(new Date(n.endMillis))
                + " · kayıt " + dur(r.tibMin) + (n.complete ? "" : " · kayıt yarıda kalmış"), 13, SUB, false));

        if (r.onset < 0) {
            LinearLayout c = card();
            c.addView(tv("Uyku algılanamadı", 16, WARN, true));
            c.addView(tv("Nefes ya yeterince ölçülemedi ya da kayıt boyunca hareket vardı. Telefonu göğsüne daha yakın "
                    + "(30–80 cm) koyup alt kenarını sana çevir; \"Yerleşimi test et\" ekranında sinyalin \"iyi\" olduğunu gör.", 13, SUB, false));
            c.setLayoutParams(lp(-1, -2, 0, dp(14), 0, 0));
            col.addView(c);
        }

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.addView(stat(dur(r.tstMin), "uyku"), new LinearLayout.LayoutParams(0, -2, 1.3f));
        stats.addView(stat("%" + Math.round(r.effPct), "verim"), new LinearLayout.LayoutParams(0, -2, 1f));
        stats.addView(stat(r.onset < 0 ? "—" : Math.round(r.solMin) + " dk", "dalma"), new LinearLayout.LayoutParams(0, -2, 1f));
        stats.addView(stat(String.valueOf(r.awakenings), "uyanma"), new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(stats, lp(-1, -2, 0, dp(16), 0, 0));

        col.addView(section("Uyku evreleri (tahmini)"));
        LinearLayout c1 = card();
        c1.addView(new ChartView(this, ChartView.STAGES, n, r));
        float tot = Math.max(1f, r.tstMin);
        SpannableStringBuilder lg = new SpannableStringBuilder();
        legend(lg, "Derin", r.deepMin, tot, ChartView.C_DEEP);
        legend(lg, "Hafif", r.lightMin, tot, ChartView.C_LIGHT);
        lg.append("\n");
        legend(lg, "REM", r.remMin, tot, ChartView.C_REM);
        legend(lg, "Uyanık", r.wakeMin, -1, ChartView.C_WAKE);
        if (r.nodataMin > 0) legend(lg, "Ölçüm yok", r.nodataMin, -1, ChartView.C_NO);
        TextView lgv = tv("", 13, SUB, false);
        lgv.setText(lg);
        lgv.setPadding(0, dp(6), 0, 0);
        c1.addView(lgv);
        col.addView(c1);

        col.addView(section("Nefes"));
        LinearLayout c2 = card();
        c2.addView(new ChartView(this, ChartView.RATE, n, r));
        c2.addView(tv(r.rateMed == r.rateMed
                ? String.format(TR, "Uykuda ortalama %.1f nefes/dk (genelde %.0f–%.0f). Kırmızı işaretler nefes duraklamaları.", r.rateMed, r.rateLo, r.rateHi)
                : "Uykuda nefes hızı ölçülemedi.", 13, SUB, false));
        col.addView(c2);

        col.addView(section("Nefes duraklamaları"));
        LinearLayout c3 = card();
        if (r.pauseCount == 0) c3.addView(tv("10 sn ve üzeri duraklama görülmedi.", 14, TXT, false));
        else {
            c3.addView(tv(String.format(TR, "%d kez · saatte %.1f · en uzunu %.0f sn", r.pauseCount, r.pauseIndex, r.pauseLongest), 15, TXT, true));
            StringBuilder sb = new StringBuilder();
            int k = 0;
            for (Night.Event v : r.pauses) {
                if (k++ >= 12) { sb.append("…"); break; }
                sb.append(hm.format(new Date(n.startMillis + (long) (v.t * 1000)))).append(" · ").append(Math.round(v.dur)).append(" sn\n");
            }
            c3.addView(tv(sb.toString().trim(), 13, SUB, false));
            c3.addView(tv("Nefes hareketinin 10 sn ya da daha uzun süre durduğu ya da çok azaldığı anlar. "
                    + "Saatte 5'ten fazla sık görülüyorsa bir uyku uzmanına danışmak iyi olur.", 12, SUB, false));
        }
        col.addView(c3);

        col.addView(section("Hareket ve ses"));
        LinearLayout c4 = card();
        c4.addView(new ChartView(this, ChartView.ACTIVITY, n, r));
        c4.addView(tv("Uykuda hareket " + r.moveCount + " kez · horlama " + dur(r.snoreMin) + " · gürültü " + r.noiseCount + " kez (sarı noktalar)", 13, SUB, false));
        col.addView(c4);

        LinearLayout c5 = card();
        c5.addView(tv(String.format(TR, "Ölçüm kapsamı %%%d", Math.round(100 * r.coverage)), 14, r.coverage >= 0.7f ? TXT : WARN, true));
        c5.addView(tv((r.coverage < 0.7f ? "Gecenin bir kısmında nefes ölçülemedi; telefonu biraz daha yakına koymak sonucu iyileştirir. " : "")
                + "Evreler nefes düzeni ve hareketten tahmin edilir; tıbbi bir uyku testi değildir.", 12, SUB, false));
        c5.setLayoutParams(lp(-1, -2, 0, dp(14), 0, 0));
        col.addView(c5);

        TextView del = button("Bu geceyi sil", CARD, BAD, v -> confirmDelete(f));
        col.addView(del, lp(-1, dp(50), 0, dp(18), 0, 0));
        root.addView(sv);
    }

    private static void legend(SpannableStringBuilder sb, String name, float min, float tot, int col) {
        int a = sb.length();
        sb.append("■ ");
        sb.setSpan(new ForegroundColorSpan(col), a, a + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(name).append(" ").append(dur(min));
        if (tot > 0) sb.append(" (%").append(String.valueOf(Math.round(100 * min / tot))).append(")");
        sb.append("    ");
    }

    private View stat(String big, String small) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.addView(tv(big, 19, TXT, true));
        c.addView(tv(small, 12, SUB, false));
        return c;
    }

    // ------------------------------------------------------------------ yardımcılar

    static String nightTitle(long start) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(start);
        int hr = c.get(Calendar.HOUR_OF_DAY);
        boolean day = hr >= 9 && hr < 18;
        if (hr < 9) c.add(Calendar.DAY_OF_MONTH, -1);
        String d = new SimpleDateFormat("d MMMM EEEE", TR).format(c.getTime());
        return d + (day ? " (gündüz)" : " gecesi");
    }

    static String dur(float min) {
        int m = Math.max(0, Math.round(min));
        if (m < 60) return m + " dk";
        return (m / 60) + " sa " + (m % 60) + " dk";
    }

    static float dpf(Context c, float v) { return v * c.getResources().getDisplayMetrics().density; }

    private int dp(float v) { return Math.round(dpf(this, v)); }

    private void setBright(float v) {
        WindowManager.LayoutParams l = getWindow().getAttributes();
        l.screenBrightness = v;
        getWindow().setAttributes(l);
    }

    private LinearLayout vcol() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout card() {
        LinearLayout c = vcol();
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(16));
        c.setBackground(g);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        return c;
    }

    private TextView section(String s) {
        TextView t = tv(s, 13, SUB, true);
        t.setAllCaps(true);
        t.setLetterSpacing(0.06f);
        t.setPadding(dp(4), dp(24), 0, dp(8));
        return t;
    }

    private TextView tv(String s, float sp, int col, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(col);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private TextView button(String s, int bg, int fg, View.OnClickListener l) {
        TextView b = tv(s, 17, fg, true);
        b.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(18));
        b.setBackground(g);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams lp(int w, int hgt, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w == -1 ? ViewGroup.LayoutParams.MATCH_PARENT : w,
                hgt == -2 ? ViewGroup.LayoutParams.WRAP_CONTENT : hgt);
        p.setMargins(l, t, r, b);
        return p;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
