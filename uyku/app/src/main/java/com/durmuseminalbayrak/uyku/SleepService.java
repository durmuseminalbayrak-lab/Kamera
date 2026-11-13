package com.durmuseminalbayrak.uyku;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Gece boyunca ön planda çalışan servis: hoparlörden duyulmayan 18–20 kHz sinyali döngüyle çalar,
 * mikrofondan yankıları okur, NightRecorder ile işler ve 30 sn'lik sonuçları dosyaya ekler.
 * Ses hiçbir yere kaydedilmez.
 */
public final class SleepService extends Service {
    public static final String ACT_START = "com.durmuseminalbayrak.uyku.BASLA";
    public static final String ACT_TEST = "com.durmuseminalbayrak.uyku.TEST";
    public static final String ACT_STOP = "com.durmuseminalbayrak.uyku.BITIR";
    static final String CH = "takip";
    static final int NOTIF = 7;
    static final long TEST_MAX_MS = 20 * 60 * 1000L;

    /** Çalışan servis (yoksa null) — arayüz canlı verileri buradan okur. */
    static volatile SleepService running;
    /** Son biten gecenin dosyası (arayüz raporu açar). */
    static volatile File lastFile;
    static volatile String lastError;

    volatile boolean test;
    volatile long startMs;
    volatile NightRecorder rec;
    volatile String source = "", status = "Hazırlanıyor…", probeLog = "";
    volatile int drops;
    volatile boolean micReady, weakUltrasound;
    volatile float rawPeakDb = Float.NaN, volScale = 1f;
    volatile int underruns, clipFixes;
    private short[] period;
    private Thread speaker;
    private HandlerThread sensorThread;
    private volatile long discAt;
    private final SensorEventListener accListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent e) {
            NightRecorder r = rec;
            if (r != null && micReady) r.addAccel(e.timestamp, e.values[0], e.values[1], e.values[2]);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };
    private boolean unprocessed;
    private AudioDeviceInfo micDevice;

    private volatile AudioTrack track;
    private volatile AudioRecord mic;
    private int micSrc = -1;
    /** Kaydı ne bitirdi (Night.Event.BY_*); 0 = henüz bilinmiyor. */
    private volatile int stopReason;
    private long elapsedStart;
    private double lastClockDiff;
    private volatile long rampStart, lastBlockAt;
    private int faults;
    private long lastFaultEv = -1_000_000;
    private Thread reader, worker;
    private volatile boolean stopFlag, discFlag;
    private boolean stopping, autoStop;
    private PowerManager.WakeLock wl;
    private int savedVol = -1;
    private volatile Night.Writer writer;
    private volatile File file;
    private final ArrayBlockingQueue<float[]> q = new ArrayBlockingQueue<>(940);
    private final ArrayBlockingQueue<float[]> pool = new ArrayBlockingQueue<>(960);

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "geceler");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        Diag.install(getFilesDir());
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Diag.log("uygulama son kullanılanlardan kaldırıldı (kayıt sürüyordu: " + (running != null) + ")", null);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onTrimMemory(int level) {
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL) Diag.log("bellek uyarısı " + level, null);
        super.onTrimMemory(level);
    }

    @Override
    public int onStartCommand(Intent it, int flags, int startId) {
        String a = it != null ? it.getAction() : null;
        if (ACT_STOP.equals(a)) {
            if (stopReason == 0) stopReason = Night.Event.BY_USER;
            new Thread(() -> {
                stopAll();
                stopSelf();
            }, "uyku-dur").start();
            return START_NOT_STICKY;
        }
        if (running != null || stopping) return START_NOT_STICKY;
        test = ACT_TEST.equals(a);
        startMs = System.currentTimeMillis();
        startFg(notification(test ? "Yerleşim testi" : "Uyku takibi sürüyor", "Hazırlanıyor…"));
        try {
            begin();
        } catch (Exception e) {
            lastError = "Başlatılamadı: " + e.getMessage();
            stopAll();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startFg(Notification n) {
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(NOTIF, n);
    }

    private void begin() throws IOException {
        lastError = null;
        AudioManager am = getSystemService(AudioManager.class);
        SharedPreferences sp = getSharedPreferences("uyku", MODE_PRIVATE);

        // medya ses düzeyi: sinyal gücü ayarına göre (bitince eski düzeye döner)
        try {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            savedVol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int lvl = sp.getInt("seviye", 1);
            float frac = lvl == 0 ? 0.5f : lvl == 2 ? 0.9f : 0.7f;
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(1, Math.round(max * frac)), 0);
        } catch (Exception ignored) {
            savedVol = -1;
        }

        // verici: tek periyot, ayrı iş parçacığıyla kesintisiz yazılır (akış kipi her cihazda çalışır)
        float[] w = Sonar.waveform();
        period = new short[Sonar.N];
        for (int i = 0; i < Sonar.N; i++) period[i] = (short) Math.round(w[i] * 0.7f * 32767);
        AudioTrack t = makeTrack(am);
        t.setVolume(0f);
        t.play();
        track = t;
        speaker = new Thread(this::speakLoop, "uyku-hoparlor");
        speaker.start();

        unprocessed = "true".equals(am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
        micDevice = find(am, AudioManager.GET_DEVICES_INPUTS, AudioDeviceInfo.TYPE_BUILTIN_MIC);

        rec = new NightRecorder(new NightRecorder.Out() {
            @Override
            public void epoch(Night.Epoch e) {
                Night.Writer w = writer;
                if (w != null) {
                    try {
                        w.epoch(e);
                        float t = (e.idx + 1) * Night.EPOCH_SEC;
                        if (e.idx % 20 == 0) w.event(battery(t));
                        // ses örneklerinin saati duvar saatinden geri kalırsa veri kaybı/donma var demektir
                        double d = (SystemClock.elapsedRealtime() - elapsedStart) / 1000.0 - t;
                        if (Math.abs(d - lastClockDiff) > 2) {
                            lastClockDiff = d;
                            w.event(new Night.Event(Night.Event.CLOCK, t, 0, d));
                        }
                    } catch (IOException x) {
                        lastError = "Kayıt yazılamadı: " + x.getMessage();
                    }
                }
                updateNotification();
            }

            @Override
            public void event(Night.Event v) {
                if (writer != null) {
                    try { writer.event(v); } catch (IOException x) { lastError = "Kayıt yazılamadı: " + x.getMessage(); }
                }
            }

            @Override
            public void sec(Night.Sec c) {
                Night.Writer w = writer;
                if (w != null) {
                    try { w.sec(c); } catch (IOException x) { lastError = "Kayıt yazılamadı: " + x.getMessage(); }
                }
            }
        });

        // ivmeölçer: telefon göğüste/yatakta ise nefesi ve hareketi doğrudan ölçer
        SensorManager sm = getSystemService(SensorManager.class);
        Sensor acc = sm != null ? sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
        if (acc != null) {
            sensorThread = new HandlerThread("uyku-ivme");
            sensorThread.start();
            sm.registerListener(accListener, acc, 20000, 1_000_000, new Handler(sensorThread.getLooper()));
        }

        PowerManager pm = getSystemService(PowerManager.class);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "uyku:takip");
        wl.acquire(16 * 3600 * 1000L);

        running = this;

        reader = new Thread(this::readLoop, "uyku-mikrofon");
        worker = new Thread(this::workLoop, "uyku-isle");
        reader.start();
        worker.start();
    }

    private AudioTrack makeTrack(AudioManager am) throws IOException {
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                .build();
        AudioFormat of = new AudioFormat.Builder()
                .setSampleRate(Sonar.FS)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        int minOut = AudioTrack.getMinBufferSize(Sonar.FS, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(aa)
                .setAudioFormat(of)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(Math.max(minOut * 4, Sonar.FS))
                .build();
        if (t.getState() != AudioTrack.STATE_INITIALIZED) {
            t.release();
            throw new IOException("hoparlör açılamadı");
        }
        AudioDeviceInfo spk = find(am, AudioManager.GET_DEVICES_OUTPUTS, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
        if (spk != null) t.setPreferredDevice(spk);
        return t;
    }

    /** Ses sunucusu yeniden başlarsa (ör. ERROR_DEAD_OBJECT) hoparlörü yeniden kurar; sesi yavaşça açar. */
    private boolean reopenTrack() {
        AudioTrack old = track;
        track = null;
        if (old != null) {
            try { old.pause(); old.flush(); old.stop(); } catch (Exception ignored) { }
            old.release();
        }
        for (int k = 0; k < 3 && !stopFlag; k++) {
            SystemClock.sleep(1000);
            if (stopFlag) break;
            try {
                AudioTrack t = makeTrack(getSystemService(AudioManager.class));
                t.setVolume(0f);
                t.play();
                rampStart = SystemClock.elapsedRealtime();
                track = t;
                return true;
            } catch (Exception e) {
                Diag.log("hoparlör yeniden açılamadı", e);
            }
        }
        return false;
    }

    /** Mikrofon okunamazsa (ses sunucusu yeniden başladı vb.) aynı kaynağı yeniden açar. */
    private boolean reopenMic() {
        lastBlockAt = SystemClock.elapsedRealtime();   // bekçi yeniden açılırken tetiklenmesin
        AudioRecord old = mic;
        mic = null;
        if (old != null) {
            try { old.stop(); } catch (Exception ignored) { }
            old.release();
        }
        for (int k = 0; k < 3 && !stopFlag; k++) {
            SystemClock.sleep(1000);
            if (stopFlag) break;
            AudioRecord r = openMic(micSrc);
            if (r == null) continue;
            try {
                r.startRecording();
                if (r.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING && !stopFlag) {
                    mic = r;
                    return true;
                }
            } catch (Exception e) {
                Diag.log("mikrofon yeniden açılamadı", e);
            }
            r.release();
        }
        return false;
    }

    /** Kurtarılan bir aksaklığı kayda işler (tanı için). */
    private void fault(int code) {
        Night.Writer w = writer;
        NightRecorder r = rec;
        if (w == null || r == null) return;
        try {
            w.event(new Night.Event(Night.Event.FAULT, r.sonar.timeSec(), 0, code));
        } catch (IOException ignored) {
        }
    }

    private Night.Event battery(float t) {
        int lvl = -1, fl = 0;
        try {
            BatteryManager bm = getSystemService(BatteryManager.class);
            lvl = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            if (bm.isCharging()) fl |= 1;
            if (getSystemService(PowerManager.class).isPowerSaveMode()) fl |= 2;
        } catch (Exception ignored) {
        }
        return new Night.Event(Night.Event.BATTERY, t, fl, lvl);
    }

    private static AudioDeviceInfo find(AudioManager am, int flag, int type) {
        for (AudioDeviceInfo d : am.getDevices(flag)) if (d.getType() == type) return d;
        return null;
    }

    private AudioRecord openMic(int src) {
        AudioFormat inf = new AudioFormat.Builder()
                .setSampleRate(Sonar.FS)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
        int min = AudioRecord.getMinBufferSize(Sonar.FS, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        try {
            AudioRecord r = new AudioRecord.Builder()
                    .setAudioSource(src)
                    .setAudioFormat(inf)
                    .setBufferSizeInBytes(Math.max(min * 4, Sonar.FS * 2))
                    .build();
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                r.release();
                return null;
            }
            if (micDevice != null) r.setPreferredDevice(micDevice);
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    private static String srcName(int src) {
        switch (src) {
            case MediaRecorder.AudioSource.UNPROCESSED: return "işlenmemiş";
            case MediaRecorder.AudioSource.CAMCORDER: return "kamera";
            case MediaRecorder.AudioSource.VOICE_RECOGNITION: return "ses tanıma";
            default: return "normal";
        }
    }

    private boolean readBlock(AudioRecord r, short[] buf) {
        int got = 0, zeros = 0;
        while (got < Sonar.N && !stopFlag) {
            int n = r.read(buf, got, Sonar.N - got);
            if (n < 0) return false;
            if (n == 0) {
                if (++zeros > 200) return false;   // ~2 sn hiç veri gelmedi
                SystemClock.sleep(10);
                continue;
            }
            zeros = 0;
            got += n;
        }
        return got == Sonar.N;
    }

    /** Hoparlörü yavaşça açar, mikrofon kaynaklarını sırayla dener; ultrasonu en iyi alanı seçer. */
    private AudioRecord chooseMic() {
        for (int i = 1; i <= 15 && !stopFlag; i++) {
            if (track != null) track.setVolume(i / 15f);
            SystemClock.sleep(100);
        }
        // sıra = tercih: otomatik kazanç/gürültü bastırma içermesi en az beklenenden başla
        int[] cands = unprocessed
                ? new int[]{MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.CAMCORDER}
                : new int[]{MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.CAMCORDER};
        short[] buf = new short[Sonar.N];
        float[] x = new float[Sonar.N];
        int best = -1;
        float bestSnr = -999;
        StringBuilder log = new StringBuilder();
        for (int src : cands) {
            if (stopFlag) break;
            status = "Mikrofon deneniyor: " + srcName(src);
            AudioRecord r = openMic(src);
            if (r == null) continue;
            float snr = -999;
            try {
                r.startRecording();
                float sum = 0;
                int n = 0;
                for (int k = 0; k < 70 && !stopFlag; k++) {     // ~1,5 sn
                    if (!readBlock(r, buf)) break;
                    if (k < 24) continue;                         // ilk 0,5 sn: oturma süresi
                    for (int i = 0; i < Sonar.N; i++) x[i] = buf[i] / 32768f;
                    float v = Sonar.bandSnrDb(x);
                    if (v == v) { sum += v; n++; }
                }
                if (n > 0) snr = sum / n;
                r.stop();
            } catch (Exception ignored) {
            }
            r.release();
            log.append(srcName(src)).append(String.format(Locale.US, " %.0f dB  ", snr));
            if (snr > bestSnr + 3) {   // belirgin daha iyiyse değiştir (sıra = tercih)
                bestSnr = snr;
                best = src;
            }
            if (snr >= 45) break;     // yeterince iyi: diğerlerini denemeye gerek yok
        }
        probeLog = log.toString().trim();
        if (best < 0) return null;
        AudioRecord r = openMic(best);
        if (r == null) return null;
        micSrc = best;
        source = srcName(best) + String.format(Locale.US, " (%.0f dB)", bestSnr);
        weakUltrasound = bestSnr < 20;
        status = null;
        return r;
    }

    private void speakLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        int lastUnder = 0, k = 0, fails = 0;
        long failWin = 0;
        while (!stopFlag) {
            AudioTrack t = track;
            if (t == null) return;
            int off = 0, n = 0;
            while (off < period.length && !stopFlag) {
                n = t.write(period, off, period.length - off);
                if (n < 0) break;
                off += n;
            }
            if (n < 0) {
                if (stopFlag) return;
                long now = SystemClock.elapsedRealtime();
                if (now - failWin > 600_000) { failWin = now; fails = 0; }
                Diag.log("hoparlöre yazılamadı (" + n + ")", null);
                if (++fails > 5 || !reopenTrack()) {
                    lastError = "Hoparlöre yazılamadı (" + n + ")";
                    return;
                }
                lastUnder = 0;
                discAt = SystemClock.elapsedRealtime() + 1200;
                fault(Night.Event.F_SPEAKER);
                continue;
            }
            long rs = rampStart;
            if (rs != 0) {   // yeniden kurulan hoparlörün sesini 0,6 sn'de aç (tık sesi olmasın)
                float g = Math.min(1f, (SystemClock.elapsedRealtime() - rs) / 600f);
                t.setVolume(volScale * g);
                if (g >= 1f) rampStart = 0;
            }
            if (++k % 47 == 0) {
                int u = t.getUnderrunCount();
                if (u != lastUnder) {
                    lastUnder = u;
                    underruns = u;
                    discAt = SystemClock.elapsedRealtime() + 1200;   // kesinti mikrofona ~1 sn sonra ulaşır
                }
            }
        }
    }

    private int peakSec;
    private long blkCount;

    private void readLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        mic = chooseMic();
        if (mic == null) {
            if (!stopFlag) {
                lastError = "Mikrofon açılamadı";
                new Thread(() -> { stopAll(); stopSelf(); }, "uyku-dur").start();
            }
            return;
        }
        mic.startRecording();
        startMs = System.currentTimeMillis();   // kayıt zamanı: mikrofon gerçekten başladığında
        elapsedStart = SystemClock.elapsedRealtime();
        if (!test) {
            try {
                String name = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(startMs)) + ".uyku";
                file = new File(dir(this), name);
                writer = new Night.Writer(file, startMs);
            } catch (IOException e) {
                lastError = "Kayıt dosyası açılamadı: " + e.getMessage();
            }
        }
        if (!test) {
            Diag.log("kayıt başladı · mikrofon " + source, null);
            Diag.onCrash = () -> {
                Night.Writer w = writer;
                if (w == null) return;
                try {
                    NightRecorder r = rec;
                    w.event(new Night.Event(Night.Event.STOP, r != null ? r.sonar.timeSec() : 0, 0, Night.Event.BY_CRASH));
                    w.end(System.currentTimeMillis());
                    w.close();
                } catch (Exception ignored) {
                }
            };
        }
        micReady = true;
        short[] buf = new short[Sonar.N];
        int fails = 0;
        long failWin = 0;
        while (!stopFlag) {
            AudioRecord m = mic;
            if (m == null || !readBlock(m, buf)) {
                if (stopFlag) break;
                long now = SystemClock.elapsedRealtime();
                if (now - failWin > 600_000) { failWin = now; fails = 0; }
                Diag.log("mikrofon okunamadı, yeniden açılıyor", null);
                if (++fails > 5 || !reopenMic()) {
                    if (!stopFlag) {
                        lastError = "Mikrofon okunamadı";
                        if (stopReason == 0) stopReason = Night.Event.BY_MIC;
                        new Thread(() -> { stopAll(); stopSelf(); }, "uyku-dur").start();
                    }
                    break;
                }
                discFlag = true;
                fault(Night.Event.F_MIC);
                continue;
            }
            lastBlockAt = SystemClock.elapsedRealtime();
            float[] b = pool.poll();
            if (b == null) b = new float[Sonar.N];
            int pk = 0;
            for (int i = 0; i < Sonar.N; i++) {
                b[i] = buf[i] / 32768f;
                int v = Math.abs(buf[i]);
                if (v > pk) pk = v;
            }
            if (pk > peakSec) peakSec = pk;
            if (++blkCount % 47 == 0) {
                rawPeakDb = (float) (20 * Math.log10(Math.max(1, peakSec) / 32768.0));
                // mikrofon doyuyorsa (kırpılma) hoparlörü yarıya indir; en fazla 3 kez
                AudioTrack tr = track;
                if (peakSec >= 32600 && clipFixes < 3 && tr != null) {
                    volScale *= 0.5f;
                    tr.setVolume(volScale);
                    clipFixes++;
                    discAt = SystemClock.elapsedRealtime() + 600;
                }
                peakSec = 0;
            }
            if (!q.offer(b)) {
                // işleme yetişemedi: blok atıldı → hizalama yeniden kurulacak
                drops++;
                discFlag = true;
                pool.offer(b);
            }
        }
    }

    private void workLoop() {
        long t0 = SystemClock.elapsedRealtime();
        while (!stopFlag || !q.isEmpty()) {
            float[] b;
            try {
                b = q.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }
            long el = SystemClock.elapsedRealtime() - t0;
            if (b == null) {
                // bekçi: mikrofondan 5 sn blok gelmediyse okuma takılmıştır; durdurup yeniden açılmasını sağla
                long now = SystemClock.elapsedRealtime(), lb = lastBlockAt;
                if (micReady && !stopFlag && lb != 0 && now - lb > 5000) {
                    lastBlockAt = now;
                    Diag.log("mikrofondan 5 sn veri gelmedi", null);
                    AudioRecord m = mic;
                    if (m != null) {
                        try { m.stop(); } catch (Exception ignored) { }
                    }
                }
                continue;
            }
            long da = discAt;
            if (discFlag || (da != 0 && SystemClock.elapsedRealtime() >= da)) {
                discFlag = false;
                if (da != 0 && SystemClock.elapsedRealtime() >= da) discAt = 0;
                rec.discontinuity();
            }
            try {
                rec.process(b);
            } catch (Throwable x) {
                // çözümlemedeki bir hata geceyi bitirmesin: günlüğe yaz, sonarı sıfırla, devam et
                if (++faults <= 3) Diag.log("işleme hatası", x);
                try { rec.discontinuity(); } catch (Throwable ignored) { }
                long now = SystemClock.elapsedRealtime();
                if (now - lastFaultEv > 60_000) {
                    lastFaultEv = now;
                    fault(Night.Event.F_PROCESS);
                }
            }
            pool.offer(b);
            if (test && el > TEST_MAX_MS && !autoStop) {
                autoStop = true;
                new Thread(() -> { stopAll(); stopSelf(); }, "uyku-dur").start();
            }
        }
    }

    private long lastNotif;

    private void updateNotification() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotif < 25_000) return;
        lastNotif = now;
        NightRecorder r = rec;
        if (r == null) return;
        float rate = r.lastEpochRate;
        String txt = (rate == rate ? String.format(Locale.forLanguageTag("tr"), "Nefes %.0f/dk · ", rate) : "")
                + MainActivity.dur((System.currentTimeMillis() - startMs) / 60000f);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIF, notification(test ? "Yerleşim testi" : "Uyku takibi sürüyor", txt));
    }

    private Notification notification(String title, String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, "Uyku takibi", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Gece boyunca takip sürerken görünür");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pOpen = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, SleepService.class).setAction(ACT_STOP);
        PendingIntent pStop = PendingIntent.getService(this, 2, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(pOpen)
                .addAction(new Notification.Action.Builder(null, "Bitir", pStop).build())
                .build();
    }

    /** Her şeyi düzgünce kapatır; kayıt dosyasını tamamlar. Birden çok kez çağrılabilir. */
    synchronized void stopAll() {
        if (stopping) return;
        stopping = true;
        stopFlag = true;
        try {
            SensorManager sm = getSystemService(SensorManager.class);
            if (sm != null) sm.unregisterListener(accListener);
        } catch (Exception ignored) {
        }
        if (sensorThread != null) sensorThread.quitSafely();
        AudioTrack t0 = track;
        if (t0 != null) {
            try {
                float v0 = volScale;
                for (int i = 8; i >= 0; i--) {
                    t0.setVolume(v0 * i / 8f);
                    SystemClock.sleep(40);
                }
                t0.pause();
                t0.flush();
            } catch (Exception ignored) {
            }
        }
        AudioRecord m0 = mic;
        if (m0 != null) {
            try { m0.stop(); } catch (Exception ignored) { }
        }
        join(speaker);
        join(reader);
        join(worker);
        AudioTrack t1 = track;
        track = null;
        if (t1 != null) {
            try { t1.stop(); } catch (Exception ignored) { }
            t1.release();
        }
        AudioRecord m1 = mic;
        mic = null;
        if (m1 != null) m1.release();
        Diag.onCrash = null;
        if (savedVol >= 0) {
            try {
                getSystemService(AudioManager.class).setStreamVolume(AudioManager.STREAM_MUSIC, savedVol, 0);
            } catch (Exception ignored) {
            }
        }
        if (writer != null) {
            int why = stopReason == 0 ? Night.Event.BY_SYSTEM : stopReason;
            try {
                NightRecorder r = rec;
                writer.event(new Night.Event(Night.Event.STOP, r != null ? r.sonar.timeSec() : 0, 0, why));
                writer.end(System.currentTimeMillis());
                writer.close();
            } catch (IOException ignored) {
            }
            if (!test) Diag.log("kayıt bitti · neden " + why + (lastError != null ? " · " + lastError : ""), null);
            writer = null;
            if (rec != null && rec.epochs < 2 && file != null) file.delete();   // 1 dk'dan kısa kayıt tutulmaz
            else lastFile = file;
        }
        if (wl != null && wl.isHeld()) wl.release();
        running = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private static void join(Thread t) {
        if (t == null) return;
        try { t.join(3000); } catch (InterruptedException ignored) { }
    }

    @Override
    public void onDestroy() {
        if (stopReason == 0) stopReason = Night.Event.BY_SYSTEM;
        stopAll();
        super.onDestroy();
    }
}
