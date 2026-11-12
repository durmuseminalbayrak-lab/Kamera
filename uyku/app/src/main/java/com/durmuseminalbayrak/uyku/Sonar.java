package com.durmuseminalbayrak.uyku;

import java.util.Arrays;

/**
 * Akustik sonar: hoparlörden sürekli çalınan, 1024 örneklik periyodu olan 18–20 kHz'lik
 * yumuşak frekans süpürmesinin yankılarından nefes ve hareket çıkarır.
 *
 * Her 1024 örneklik mikrofon bloğu (48 kHz, [-1,1]) process() ile verilir. Blok, verici periyodunun
 * dairesel kaydırılmış bir kopyasını içerdiğinden FFT'deki her spektral çizgi kanalın o frekanstaki
 * yanıtını verir; bunlardan 64 gecikme hücreli (≈5,7 cm) kanal dürtü yanıtı hesaplanır. Göğsün
 * hareketi kendi gecikme hücrelerindeki karmaşık değeri döndürür; bu dönüş nefestir.
 *
 * Android bağımlılığı yoktur (bilgisayarda benzetimle test edilir).
 */
public final class Sonar {
    public static final int FS = 48000, N = 1024, K0 = 405, L = 23, NL = 2 * L + 1, M = 64, DEC = 4;
    public static final double DF = 940, H3 = -0.06;
    public static final double FRAME_HZ = (double) FS / N;   // 46.875 blok/s
    public static final double SLOW_HZ = FRAME_HZ / DEC;     // 11.72 örnek/s
    static final int WARM = 94;                    // ~2 s: doğrudan yolu bul
    static final int RING = 1024;                  // ~87 s geçmiş
    static final int WIN = 352;                    // 30 s analiz penceresi
    static final int MINWIN = 117;                 // en az 10 s
    static final int LAG_MIN = 15, LAG_MAX = 117;  // 1,3–10 s periyot (6–47 nefes/dk)
    static final int PW = 47;                      // ~4 s genlik penceresi
    static final int LAGD = 12;                    // ~1 s fark aralığı
    // saat kayması izleme döngüsü (doğal frekans ~0,05 Hz, kritik sönümlü)
    static final double LOOP_W = 2 * Math.PI * 0.05 / FRAME_HZ;
    static final double LOOP_A = 2 * LOOP_W, LOOP_B = LOOP_W * LOOP_W;
    public static final float Q_MIN = 0.45f;

    public interface Listener {
        /** Saniyede bir çağrılır; alanlar güncel. */
        void second(Sonar s);
        /** ≥10 sn nefes hareketinin durduğu/çok azaldığı an. */
        void pause(double startSec, double durSec, float depth);
    }

    /** Verici dalga biçimi (tek periyot, tepe 1). */
    public static float[] waveform() {
        float[] w = new float[N];
        double fc = K0 * (double) FS / N, gmax = 0;
        for (int i = 0; i < N; i++) {
            double t = (double) i / N;
            gmax = Math.max(gmax, Math.abs(Math.sin(2 * Math.PI * t) + H3 * Math.sin(6 * Math.PI * t)));
        }
        double ph = 0;
        for (int i = 0; i < N; i++) {
            w[i] = (float) Math.cos(ph);
            double t = (double) i / N;
            double f = fc + DF * (Math.sin(2 * Math.PI * t) + H3 * Math.sin(6 * Math.PI * t)) / gmax;
            ph += 2 * Math.PI * f / FS;
        }
        return w;
    }

    private final Listener listener;
    final Fft fft = new Fft(N);
    /** Son bloğun (yüksek geçirilmiş) spektrumu. */
    final double[] re = new double[N], im = new double[N];
    private final double[] gr = new double[NL], gi = new double[NL], fk = new double[NL];
    private final double[] er = new double[NL * M], ei = new double[NL * M];
    private final double[] hr = new double[NL], hi = new double[NL];
    private final double[] tr = new double[M], ti = new double[M];
    private final double[] ar = new double[M], ai = new double[M];
    private int an;

    // hizalama ve saat kayması
    private int d0 = -1, warm;
    private final double[] warmP = new double[M];
    private boolean pInit;
    private double pPrev, pRes, pVel, pSm, zMag;

    // yavaş zaman (11,7 Hz) halkaları
    private final float[][] rr = new float[M][RING], ri = new float[M][RING];
    private int rpos, rcount;
    private long slowTotal;
    private int hpFill;
    private final float[][] hp2 = new float[M][PW];
    private final double[] hpSum = new double[M];
    private int hpPos;
    private final double[] pvR = new double[M], pvI = new double[M];
    private boolean pvOk;

    // saniyelik durum
    private long frames;
    private double nextTick = FRAME_HZ;
    private double mvSecMax;
    private int slowInSec;
    private final float[] mvRing = new float[300];
    private int mvPos, mvCount;
    private long lastMoveEndSlow = Long.MIN_VALUE / 4;
    private double lastMoveTime = -1e9;
    private int moveRun;

    // nefes analizi
    private final double[] xr = new double[WIN], xi = new double[WIN];
    private final float[][] ac = new float[M][LAG_MAX + 2];
    private final double[] en = new double[M];
    private final float[] score = new float[M], ss = new float[M], wgt = new float[M];
    private final int[] lagOf = new int[M];
    private float[] lastWave;
    private long lastWaveEnd;

    // genlik / duraklama
    private final PauseDetector pd;

    // tanı (test ekranı): spektrum, gecikme profili, en iyi hücrenin yörüngesi
    public static final int SPEC_LO = 320, SPEC_HI = 490;   // 15,0–23,0 kHz
    private final double[] specAcc = new double[SPEC_HI - SPEC_LO + 1];
    public volatile float[] specDb, profStatDb, profDynDb, trajX, trajY;
    public volatile int diagD0 = -1;

    // --- dışarıya açık sonuçlar (her saniye) ---
    public volatile float rate = Float.NaN, quality, ratio = Float.NaN, moveIntensity;
    public volatile boolean moving, valid, disturbed, inPause;
    public volatile int bestTap = -1, resets;
    public volatile float directDb = Float.NaN, echoDb = Float.NaN, noiseDb = Float.NaN, bandSnrDb = Float.NaN;
    /** Son 30 sn'lik nefes dalgası (birim RMS); arayüz kopyalar. */
    public volatile float[] waveOut;
    public volatile float lagSec = Float.NaN;

    public Sonar(Listener l) {
        listener = l;
        pd = new PauseDetector(l == null ? null : l::pause);
        float[] w = waveform();
        for (int i = 0; i < N; i++) { re[i] = w[i]; im[i] = 0; }
        fft.forward(re, im);
        double p = 0;
        for (int j = 0; j < NL; j++) {
            int k = K0 - L + j;
            p += re[k] * re[k] + im[k] * im[k];
        }
        p /= NL;
        double eps = 0.05 * p, wsum = 0;
        double[] win = new double[NL];
        for (int j = 0; j < NL; j++) {
            win[j] = 0.5 * (1 + Math.cos(Math.PI * (j - L) / (L + 1)));
            wsum += win[j];
        }
        for (int j = 0; j < NL; j++) {
            int k = K0 - L + j;
            double sr = re[k], si = im[k], s2 = sr * sr + si * si;
            double g = win[j] / (s2 + eps) / wsum;
            gr[j] = sr * g;
            gi[j] = -si * g;
            fk[j] = (double) k / K0;
            for (int d = 0; d < M; d++) {
                double a = 2 * Math.PI * (j - L) * d / M;
                er[j * M + d] = Math.cos(a);
                ei[j * M + d] = Math.sin(a);
            }
        }
        Arrays.fill(re, 0);
        Arrays.fill(im, 0);
    }

    public double timeSec() { return frames / FRAME_HZ; }

    /** Bir bloktaki ultrason bandı / komşu bant güç oranı (dB): mikrofon yolunun 18–20 kHz'i geçirip geçirmediği. */
    public static float bandSnrDb(float[] x) {
        Fft f = new Fft(N);
        double[] a = new double[N], b = new double[N];
        for (int i = 0; i < N; i++) a[i] = x[i];
        f.forward(a, b);
        double in = 0, out = 0;
        int no = 0;
        for (int k = K0 - L; k <= K0 + L; k++) in += a[k] * a[k] + b[k] * b[k];
        for (int k = K0 - L - 40; k <= K0 - L - 8; k++) { out += a[k] * a[k] + b[k] * b[k]; no++; }
        for (int k = K0 + L + 8; k <= K0 + L + 40; k++) { out += a[k] * a[k] + b[k] * b[k]; no++; }
        return out > 0 && in > 0 ? (float) (10 * Math.log10((in / NL) / (out / no))) : Float.NaN;
    }

    /** Hoparlör ile mikrofon saatleri arasındaki kayma (milyonda bir). */
    public float driftPpm() {
        return (float) (Math.abs(pVel) / (2 * Math.PI * K0) * 1e6);   // pVel: fc'de blok başına faz
    }

    /** Seçili nefes hücresinin yaklaşık uzaklığı (cm); yoksa -1. */
    public int distanceCm() {
        int b = bestTap, d = d0;
        if (b < 0 || d < 0) return -1;
        int k = ((b - d) % M + M) % M;
        return Math.round(k * (1000f / (M * (float) FRAME_HZ)) * 34.3f / 2f);
    }
    String dbg() {
        double ws = 0; int nw = 0; for (float v : wgt) { ws += v; if (v > 0) nw++; }
        return String.format("d0=%d zMag=%.4g vel=%.4f A=%.3g aRef=%.3g aCount=%d wsum=%.3f nw=%d calmValid=%d", d0, zMag, pVel, dbgA, median(pd.aRing, pd.aCount), pd.aCount, ws, nw, pd.calmValid);
    }
    private float dbgA;

    /** Ses akışında kopukluk olduysa (örnek kaybı) çağırın: hizalama baştan kurulur. */
    public void reset() {
        d0 = -1;
        warm = 0;
        Arrays.fill(warmP, 0);
        pInit = false;
        pSm = 0;
        pVel = 0;
        rcount = 0;
        hpFill = 0;
        hpPos = 0;
        pvOk = false;
        an = 0;
        Arrays.fill(ar, 0);
        Arrays.fill(ai, 0);
        for (float[] a : hp2) Arrays.fill(a, 0);
        Arrays.fill(hpSum, 0);
        pd.reset();
        inPause = false;
        lastWave = null;
        disturbed = true;
        resets++;
    }

    // 14 kHz yüksek geçiren (4. derece Butterworth, iki biquad): konuşma/horlama gibi duyulur
    // seslerin dikdörtgen pencere sızıntısı ultrason bandına karışmasın
    private static final double[][] HP = hpCoef(14000);
    private final double[] hz = new double[4];

    private static double[][] hpCoef(double fc) {
        double k = Math.tan(Math.PI * fc / FS);
        double[] qs = {0.5411961, 1.3065630};
        double[][] c = new double[2][5];
        for (int i = 0; i < 2; i++) {
            double q = qs[i], nrm = 1 / (1 + k / q + k * k);
            c[i][0] = nrm;
            c[i][1] = -2 * nrm;
            c[i][2] = nrm;
            c[i][3] = 2 * (k * k - 1) * nrm;
            c[i][4] = (1 - k / q + k * k) * nrm;
        }
        return c;
    }

    /** Bir mikrofon bloğu (N örnek). */
    public void process(float[] x) {
        for (int i = 0; i < N; i++) {
            double v = x[i];
            for (int st = 0; st < 2; st++) {
                double[] c = HP[st];
                double y = c[0] * v + hz[2 * st];
                hz[2 * st] = c[1] * v - c[3] * y + hz[2 * st + 1];
                hz[2 * st + 1] = c[2] * v - c[4] * y;
                v = y;
            }
            re[i] = v;
            im[i] = 0;
        }
        fft.forward(re, im);
        frames++;
        for (int k = SPEC_LO; k <= SPEC_HI; k++) {
            double pw = (re[k] * re[k] + im[k] * im[k]) / ((N / 2.0) * (N / 2.0));
            specAcc[k - SPEC_LO] += (pw - specAcc[k - SPEC_LO]) * 0.1;
        }
        for (int j = 0; j < NL; j++) {
            int k = K0 - L + j;
            double r = re[k], q = im[k];
            hr[j] = r * gr[j] - q * gi[j];
            hi[j] = r * gi[j] + q * gr[j];
        }
        if (d0 < 0) {
            taps();
            for (int d = 0; d < M; d++) warmP[d] += tr[d] * tr[d] + ti[d] * ti[d];
            if (++warm >= WARM) {
                int b = 0;
                for (int d = 1; d < M; d++) if (warmP[d] > warmP[b]) b = d;
                d0 = b;
                pInit = false;
            }
        } else {
            // geçerli düzeltmeyi uygula (saat kayması = ortak gecikme → frekansla orantılı faz)
            if (pSm != 0) {
                for (int j = 0; j < NL; j++) {
                    double a = -pSm * fk[j], cr = Math.cos(a), ci = Math.sin(a);
                    double r = hr[j] * cr - hi[j] * ci;
                    hi[j] = hr[j] * ci + hi[j] * cr;
                    hr[j] = r;
                }
            }
            // düzeltilmiş doğrudan yolun fazı: kalan hata → ikinci dereceden izleme döngüsü
            double zr = 0, zi = 0;
            for (int j = 0; j < NL; j++) {
                int e = j * M + d0;
                zr += hr[j] * er[e] - hi[j] * ei[e];
                zi += hr[j] * ei[e] + hi[j] * er[e];
            }
            double mag = Math.hypot(zr, zi), p = Math.atan2(zi, zr);
            boolean ok = true;
            if (!pInit) {
                pInit = true;
                pPrev = p;
                pRes = 0;
                pVel = 0;
                zMag = mag;
            } else {
                double dp = p - pPrev;
                dp -= 2 * Math.PI * Math.rint(dp / (2 * Math.PI));
                pPrev = p;
                if (Math.abs(dp) > 1.0 || mag < 0.5 * zMag || mag > 2.0 * zMag) ok = false;
                else {
                    pRes += dp;                       // kalan faz (düzeltilmiş − başlangıç)
                    zMag += (mag - zMag) * 0.02;
                }
            }
            if (!ok) {
                reset();
            } else {
                // döngü: düzeltme pSm gerçek kaymayı izler (rampada kalıcı hata yok)
                double inc = LOOP_A * pRes + pVel;
                pVel += LOOP_B * pRes;
                pSm += inc;                           // sonraki blokta ölçülen faz bu kadar düşük gelir
                taps();
                for (int d = 0; d < M; d++) { ar[d] += tr[d]; ai[d] += ti[d]; }
                if (++an == DEC) {
                    for (int d = 0; d < M; d++) { ar[d] /= DEC; ai[d] /= DEC; }
                    slow();
                    Arrays.fill(ar, 0);
                    Arrays.fill(ai, 0);
                    an = 0;
                }
            }
        }
        if (frames >= nextTick) {
            nextTick += FRAME_HZ;
            second();
        }
    }

    private void taps() {
        for (int d = 0; d < M; d++) {
            double sr = 0, si = 0;
            for (int j = 0, e = d; j < NL; j++, e += M) {
                sr += hr[j] * er[e] - hi[j] * ei[e];
                si += hr[j] * ei[e] + hi[j] * er[e];
            }
            tr[d] = sr;
            ti[d] = si;
        }
    }

    private boolean near(int d) {
        if (d0 < 0) return true;
        int k = Math.abs(d - d0);
        k = Math.min(k, M - k);
        return k <= 2;
    }

    private void slow() {
        slowTotal++;
        if (pvOk) {
            double mv = 0;
            for (int d = 0; d < M; d++) {
                if (near(d)) continue;
                double a = ar[d] - pvR[d], b = ai[d] - pvI[d];
                mv += a * a + b * b;
            }
            if (mv > mvSecMax) mvSecMax = mv;
            slowInSec++;
        }
        System.arraycopy(ar, 0, pvR, 0, M);
        System.arraycopy(ai, 0, pvI, 0, M);
        pvOk = true;
        for (int d = 0; d < M; d++) {
            rr[d][rpos] = (float) ar[d];
            ri[d][rpos] = (float) ai[d];
        }
        rpos = (rpos + 1) % RING;
        rcount++;
        // 1 sn arayla fark gücü: nefes sürerken yüksek, durunca hemen sıfıra iner (genlik/duraklama)
        if (rcount > LAGD) {
            int q = (rpos - 1 - LAGD + RING) % RING;
            for (int d = 0; d < M; d++) {
                double a = ar[d] - rr[d][q], b = ai[d] - ri[d][q];
                float p = (float) (a * a + b * b);
                hpSum[d] += p - hp2[d][hpPos];
                hp2[d][hpPos] = p;
            }
            hpPos = (hpPos + 1) % PW;
            if (hpFill < PW) hpFill++;
        }
    }

    private void second() {
        double t = timeSec();
        // --- hareket: ardışık örnekler arası değişim, son 5 dk'nın medyanına göre ---
        boolean mov = false;
        float inten = 0;
        if (slowInSec > 0 && d0 >= 0) {
            float base = median(mvRing, mvCount);
            if (mvCount >= 20 && base > 0) {
                inten = (float) (mvSecMax / base);
                mov = inten > 6f;
            }
            mvRing[mvPos] = (float) mvSecMax;
            mvPos = (mvPos + 1) % mvRing.length;
            if (mvCount < mvRing.length) mvCount++;
        }
        moveIntensity = inten;
        moving = mov;
        if (mov) {
            lastMoveEndSlow = slowTotal;
            lastMoveTime = t;
            moveRun++;
            if (moveRun >= 2 || inten > 25) pd.resetRef();   // pozisyon değişti: genlik referansı yeniden
        } else moveRun = 0;
        mvSecMax = 0;
        slowInSec = 0;

        analyze();
        amplitudeAndPause(t);
        diagnostics();
        publishDiag();
        if (listener != null) listener.second(this);
        disturbed = false;
    }

    private void analyze() {
        int n = Math.min(rcount, WIN);
        long sinceMove = slowTotal - lastMoveEndSlow;
        if (sinceMove < n) n = (int) Math.max(0, sinceMove);
        if (d0 < 0 || n < MINWIN) {
            valid = false;
            quality = 0;
            rate = Float.NaN;
            lagSec = Float.NaN;
            bestTap = -1;
            return;
        }
        int lagMax = Math.min(LAG_MAX, n - 30);
        int start = (rpos - n + RING) % RING;
        double[] cum = new double[n + 1];
        for (int d = 0; d < M; d++) {
            score[d] = 0;
            en[d] = 0;
            lagOf[d] = -1;
            if (near(d)) continue;
            load(d, start, n);
            double e = 0;
            cum[0] = 0;
            for (int i = 0; i < n; i++) {
                double p = xr[i] * xr[i] + xi[i] * xi[i];
                e += p;
                cum[i + 1] = cum[i] + p;
            }
            en[d] = e / n;
            if (e <= 0) continue;
            float[] a = ac[d];
            for (int lag = 1; lag <= lagMax; lag++) {
                double s = 0;
                for (int i = 0, j = lag; j < n; i++, j++) s += xr[i] * xr[j] + xi[i] * xi[j];
                double nrm = Math.sqrt(cum[n - lag] * (cum[n] - cum[lag]));
                a[lag] = nrm > 0 ? (float) (s / nrm) : 0;
            }
            int pk = pickPeak(a, lagMax);
            if (pk > 0) {
                score[d] = a[pk];
                lagOf[d] = pk;
            }
        }
        // gürültü tabanı: hücrelerin medyan enerjisi
        double[] es = new double[M];
        int ne = 0;
        for (int d = 0; d < M; d++) if (!near(d) && en[d] > 0) es[ne++] = en[d];
        Arrays.sort(es, 0, ne);
        double enoise = ne > 0 ? es[ne / 2] : 0;
        // sıralama: periyodiklik × enerji yeterliliği; genlik ağırlıkları duraklamada dondurulur
        boolean freeze = inPause || timeSec() < pd.endTime() + 30;   // pencere duraklamayı içerirken ağırlıklar sabit
        int[] top = {-1, -1, -1};
        float[] tv = {0, 0, 0};
        for (int d = 0; d < M; d++) {
            if (near(d)) { wgt[d] = 0; continue; }
            float eff = lagOf[d] < 0 ? 0f
                    : score[d] * (float) Math.sqrt(Math.min(1.0, en[d] / (6 * enoise + 1e-30)));
            if (!freeze) {
                ss[d] += (eff - ss[d]) * 0.1f;
                float w = Math.max(0f, ss[d] - 0.3f);
                wgt[d] = w * w;
            }
            if (lagOf[d] < 0) continue;
            for (int k = 0; k < 3; k++) {
                if (eff > tv[k]) {
                    for (int q = 2; q > k; q--) { tv[q] = tv[q - 1]; top[q] = top[q - 1]; }
                    tv[k] = eff;
                    top[k] = d;
                    break;
                }
            }
        }
        if (top[0] < 0 || tv[0] < 0.3f) {
            valid = false;
            quality = top[0] < 0 ? 0 : score[top[0]];
            rate = Float.NaN;
            lagSec = Float.NaN;
            bestTap = top[0];
            echoDb = top[0] < 0 ? Float.NaN : db10(en[top[0]] / (zMag * zMag));
            noiseDb = db10(enoise / (zMag * zMag));
            return;
        }
        // seçilen hücrelerin otokorelasyonlarını enerjiyle ağırlıklandırıp birleştir
        float[] comb = new float[lagMax + 2];
        double wsum = 0;
        for (int k = 0; k < 3; k++) {
            int d = top[k];
            if (d < 0 || tv[k] < 0.3f || tv[k] < 0.6f * tv[0]) continue;
            double w = Math.sqrt(en[d]);
            wsum += w;
            for (int lag = 1; lag <= lagMax; lag++) comb[lag] += (float) (w * ac[d][lag]);
        }
        for (int lag = 1; lag <= lagMax; lag++) comb[lag] /= (float) wsum;
        int pk = pickPeak(comb, lagMax);
        if (pk < 0) pk = lagOf[top[0]];
        double lagF = pk;
        if (pk > 1 && pk < lagMax) {
            double y0 = comb[pk - 1], y1 = comb[pk], y2 = comb[pk + 1], den = y0 - 2 * y1 + y2;
            if (den < 0) lagF = pk + 0.5 * (y0 - y2) / den;
        }
        quality = comb[pk];
        rate = (float) (60.0 * SLOW_HZ / lagF);
        lagSec = (float) (lagF / SLOW_HZ);
        valid = quality >= Q_MIN && rate >= 5 && rate <= 45;
        bestTap = top[0];
        echoDb = db10(en[top[0]] / (zMag * zMag));
        noiseDb = db10(enoise / (zMag * zMag));
        // gösterim / soluk aralığı için dalga
        load(top[0], start, n);
        float[] w = shape(n);
        if (lastWave != null) {
            // önceki dalgayla aynı yönde olsun: örtüşen örnekleri karşılaştır
            long o = (slowTotal - n) - (lastWaveEnd - lastWave.length);
            double c = 0;
            for (int i = 0; i < n; i++) {
                long j = i + o;
                if (j >= 0 && j < lastWave.length) c += w[i] * lastWave[(int) j];
            }
            if (c < 0) for (int i = 0; i < n; i++) w[i] = -w[i];
        }
        lastWave = w;
        lastWaveEnd = slowTotal;
        waveOut = w;
    }

    private void load(int d, int start, int n) {
        float[] a = rr[d], b = ri[d];
        double sr = 0, si = 0, st = 0, stt = 0, str = 0, sti = 0;
        for (int i = 0, p = start; i < n; i++, p = (p + 1) % RING) {
            xr[i] = a[p];
            xi[i] = b[p];
        }
        // doğrusal eğilim çıkar (yavaş sürüklenme)
        for (int i = 0; i < n; i++) {
            sr += xr[i];
            si += xi[i];
            st += i;
            stt += (double) i * i;
            str += i * xr[i];
            sti += i * xi[i];
        }
        double den = n * stt - st * st;
        double br = den != 0 ? (n * str - st * sr) / den : 0, bi = den != 0 ? (n * sti - st * si) / den : 0;
        double a0r = (sr - br * st) / n, a0i = (si - bi * st) / n;
        for (int i = 0; i < n; i++) {
            xr[i] -= a0r + br * i;
            xi[i] -= a0i + bi * i;
        }
    }

    /** İlk belirgin tepe (en yüksek tepenin %85'ine ulaşan ilk tepe; öncesinde çukur şartı). */
    static int pickPeak(float[] a, int lagMax) { return pickPeak(a, LAG_MIN, lagMax); }

    static int pickPeak(float[] a, int lagMin, int lagMax) {
        float gmax = -1;
        for (int k = lagMin; k < lagMax; k++)
            if (a[k] >= a[k - 1] && a[k] >= a[k + 1] && a[k] > gmax) gmax = a[k];
        if (gmax <= 0.1f) return -1;
        float mn = 1;
        for (int k = 1; k < lagMax; k++) {
            if (a[k] < mn) mn = a[k];
            if (k >= lagMin && a[k] >= a[k - 1] && a[k] >= a[k + 1] && a[k] >= 0.85f * gmax && mn < a[k] - 0.2f)
                return k;
        }
        return -1;
    }

    /** Karmaşık yörüngeyi tek boyutlu nefes dalgasına çevirir: çember uydurma (faz) ya da ana eksen. */
    private float[] shape(int n) {
        double sxx = 0, syy = 0, sxy = 0;
        for (int i = 0; i < n; i++) {
            sxx += xr[i] * xr[i];
            syy += xi[i] * xi[i];
            sxy += xr[i] * xi[i];
        }
        double[] s = new double[n];
        boolean done = false;
        // Kasa çember uydurma: x²+y² + D x + E y + F = 0
        double sx = 0, sy = 0, sz = 0, sxz = 0, syz = 0;
        for (int i = 0; i < n; i++) {
            double z = xr[i] * xr[i] + xi[i] * xi[i];
            sx += xr[i];
            sy += xi[i];
            sz += z;
            sxz += xr[i] * z;
            syz += xi[i] * z;
        }
        double[][] A = {{sxx, sxy, sx}, {sxy, syy, sy}, {sx, sy, n}};
        double[] B = {-sxz, -syz, -sz};
        double[] sol = solve3(A, B);
        double spread = Math.sqrt((sxx + syy) / n);
        if (sol != null) {
            double cx = -sol[0] / 2, cy = -sol[1] / 2, r2 = cx * cx + cy * cy - sol[2];
            if (r2 > 0) {
                double r = Math.sqrt(r2);
                if (r < 30 * spread) {
                    double res = 0, prev = 0, acc = 0, mn = 0, mx = 0;
                    for (int i = 0; i < n; i++) {
                        double dx = xr[i] - cx, dy = xi[i] - cy;
                        double dist = Math.hypot(dx, dy) - r;
                        res += dist * dist;
                        double ang = Math.atan2(dy, dx);
                        if (i == 0) acc = ang;
                        else {
                            double da = ang - prev;
                            da -= 2 * Math.PI * Math.rint(da / (2 * Math.PI));
                            acc += da;
                        }
                        prev = ang;
                        s[i] = acc;
                        if (i == 0 || acc < mn) mn = acc;
                        if (i == 0 || acc > mx) mx = acc;
                    }
                    res = Math.sqrt(res / n) / r;
                    if (res < 0.3 && mx - mn > 1.2) done = true;
                }
            }
        }
        if (!done) {
            double tr2 = sxx + syy, det = sxx * syy - sxy * sxy;
            double l1 = tr2 / 2 + Math.sqrt(Math.max(0, tr2 * tr2 / 4 - det));
            double vx, vy;
            if (Math.abs(sxy) > 1e-30) { vx = l1 - syy; vy = sxy; }
            else if (sxx >= syy) { vx = 1; vy = 0; }
            else { vx = 0; vy = 1; }
            double vn = Math.hypot(vx, vy);
            vx /= vn;
            vy /= vn;
            for (int i = 0; i < n; i++) s[i] = xr[i] * vx + xi[i] * vy;
        }
        // doğrusal eğilimi çıkar, hafif yumuşat, birim RMS
        detrend(s, n);
        float[] out = new float[n];
        double e = 0;
        for (int i = 0; i < n; i++) {
            double v = 0;
            int c = 0;
            for (int k = Math.max(0, i - 2); k <= Math.min(n - 1, i + 2); k++) { v += s[k]; c++; }
            out[i] = (float) (v / c);
            e += out[i] * out[i];
        }
        float g = e > 0 ? (float) (1 / Math.sqrt(e / n)) : 0;
        for (int i = 0; i < n; i++) out[i] *= g;
        return out;
    }

    private static void detrend(double[] s, int n) {
        double st = 0, stt = 0, sv = 0, stv = 0;
        for (int i = 0; i < n; i++) { st += i; stt += (double) i * i; sv += s[i]; stv += i * s[i]; }
        double den = n * stt - st * st;
        double b = den != 0 ? (n * stv - st * sv) / den : 0, a = (sv - b * st) / n;
        for (int i = 0; i < n; i++) s[i] -= a + b * i;
    }

    private static double[] solve3(double[][] A, double[] B) {
        double[][] m = new double[3][4];
        for (int i = 0; i < 3; i++) { System.arraycopy(A[i], 0, m[i], 0, 3); m[i][3] = B[i]; }
        for (int c = 0; c < 3; c++) {
            int p = c;
            for (int r = c + 1; r < 3; r++) if (Math.abs(m[r][c]) > Math.abs(m[p][c])) p = r;
            if (Math.abs(m[p][c]) < 1e-300) return null;
            double[] t = m[c]; m[c] = m[p]; m[p] = t;
            for (int r = 0; r < 3; r++) {
                if (r == c) continue;
                double f = m[r][c] / m[c][c];
                for (int k = c; k < 4; k++) m[r][k] -= f * m[c][k];
            }
        }
        double[] x = new double[3];
        for (int i = 0; i < 3; i++) x[i] = m[i][3] / m[i][i];
        for (double v : x) if (Double.isNaN(v) || Double.isInfinite(v)) return null;
        return x;
    }

    private void amplitudeAndPause(double t) {
        double num = 0, den = 0;
        for (int d = 0; d < M; d++) {
            if (wgt[d] <= 0) continue;
            double s = 0;
            float[] h = hp2[d];
            for (float v : h) s += v;
            hpSum[d] = s;   // kayan toplamın yuvarlama birikimini temizle
            num += wgt[d] * s / PW;
            den += wgt[d];
        }
        float A = den > 0 && hpFill >= PW ? (float) Math.sqrt(num / den) : Float.NaN;
        dbgA = A;
        pd.second(t, A, valid, moving, lastMoveTime);
        ratio = pd.ratio;
        inPause = pd.inPause;
    }

    private void publishDiag() {
        float[] sp = new float[specAcc.length];
        for (int i = 0; i < sp.length; i++) sp[i] = (float) (10 * Math.log10(specAcc[i] + 1e-20));
        specDb = sp;
        diagD0 = d0;
        int n = Math.min(rcount, WIN);
        if (d0 < 0 || n < 12) return;
        float ref = (float) (zMag * zMag);
        float[] st = new float[M], dy = new float[M];
        int start = (rpos - n + RING) % RING;
        for (int d = 0; d < M; d++) {
            double sr = 0, si = 0;
            for (int i = 0, p = start; i < n; i++, p = (p + 1) % RING) { sr += rr[d][p]; si += ri[d][p]; }
            sr /= n;
            si /= n;
            st[d] = db10((sr * sr + si * si) / ref);
            dy[d] = en[d] > 0 ? db10(en[d] / ref) : -200f;
        }
        profStatDb = st;
        profDynDb = dy;
        int b = bestTap;
        if (b >= 0) {
            load(b, start, n);
            float[] x = new float[n], y = new float[n];
            for (int i = 0; i < n; i++) { x[i] = (float) xr[i]; y[i] = (float) xi[i]; }
            trajX = x;
            trajY = y;
        } else {
            trajX = null;
            trajY = null;
        }
    }

    private void diagnostics() {
        directDb = d0 >= 0 && zMag > 0 ? (float) (20 * Math.log10(zMag)) : Float.NaN;
        double in = 0, out = 0;
        int no = 0;
        for (int j = 0; j < NL; j++) { int k = K0 - L + j; in += re[k] * re[k] + im[k] * im[k]; }
        for (int k = K0 - L - 40; k <= K0 - L - 8; k++) { out += re[k] * re[k] + im[k] * im[k]; no++; }
        for (int k = K0 + L + 8; k <= K0 + L + 40; k++) { out += re[k] * re[k] + im[k] * im[k]; no++; }
        bandSnrDb = out > 0 ? (float) (10 * Math.log10((in / NL) / (out / no))) : Float.NaN;
    }

    /** Son pencerenin nefes dalgasından soluk sayısı, ortalama aralık (sn) ve değişkenlik (CV). */
    public float[] breathStats() {
        return valid ? breathStats(waveOut, lagSec, SLOW_HZ) : null;
    }

    static float[] breathStats(float[] w, float lag, double fs) {
        if (w == null || !(lag > 0)) return null;
        int n = w.length;
        int minDist = Math.max(4, Math.round(0.6f * lag * (float) fs));
        int half = minDist / 2;
        int[] pk = new int[n];
        int np = 0;
        for (int i = half; i < n - half; i++) {
            float v = w[i];
            if (v < 0.3f) continue;
            boolean isMax = true;
            for (int k = i - half; k <= i + half; k++) if (w[k] > v) { isMax = false; break; }
            if (isMax && (np == 0 || i - pk[np - 1] >= minDist)) pk[np++] = i;
        }
        if (np < 3) return new float[]{np, Float.NaN, Float.NaN};
        double s = 0, s2 = 0;
        for (int i = 1; i < np; i++) {
            double d = (pk[i] - pk[i - 1]) / fs;
            s += d;
            s2 += d * d;
        }
        int m = np - 1;
        double mean = s / m, var = Math.max(0, s2 / m - mean * mean);
        return new float[]{np, (float) mean, (float) (Math.sqrt(var) / mean)};
    }

    static float median(float[] a, int n) {
        if (n <= 0) return Float.NaN;
        float[] c = Arrays.copyOf(a, n);
        Arrays.sort(c);
        return c[n / 2];
    }

    private static float db10(double v) {
        return v > 0 ? (float) (10 * Math.log10(v)) : Float.NaN;
    }
}
