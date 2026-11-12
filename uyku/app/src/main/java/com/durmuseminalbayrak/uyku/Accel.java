package com.durmuseminalbayrak.uyku;

/**
 * İvmeölçerden nefes ve hareket. Telefon göğsün/karnın üstünde ya da yatakta vücuda dayalıyken
 * nefesle telefonun eğimi çok az değişir; yerçekiminin üç eksene dağılımı nefes ritmiyle salınır.
 * Sensör olayları add() ile (herhangi bir iş parçacığından), saniyelik analiz second() ile verilir.
 */
public final class Accel {
    static final double FS = 10.0;               // iç örnekleme (Hz)
    static final long BIN_NS = 100_000_000L;
    static final int RING = 900, WIN = 300, MINWIN = 120;
    static final int LAG_MIN = 13, LAG_MAX = 100;  // 1,3–10 sn periyot
    static final int PW = 40;                      // genlik penceresi ~4 sn
    static final float MIN_AMP = 0.004f;           // m/s²: sensör gürültüsünün üstü
    static final float Q_MIN = 0.5f;

    private final float[][] ring = new float[3][RING];
    private int pos, count;
    private long total;
    private long binStart = -1;
    private double bx, by, bz;
    private int bn;
    private float lx, ly, lz, px, py, pz;
    private boolean lInit, pInit;
    private long lastNs;
    private double hfSum;
    private int hfN, samplesInSec;
    private final float[] mvRing = new float[300];
    private int mvPos, mvCount, moveRun;
    private long lastMoveEnd = Long.MIN_VALUE / 4;
    private double lastMoveTime = -1e9;
    private final PauseDetector pd;
    private float[] lastWave;
    private long lastWaveEnd;
    private float lastA = Float.NaN;

    public volatile boolean present, valid, moving, inPause;
    public volatile float rate = Float.NaN, quality, moveIntensity, ampRms = Float.NaN, ratio = Float.NaN, lagSec = Float.NaN;
    public volatile float[] waveOut;

    public Accel(PauseDetector.Sink sink) { pd = new PauseDetector(sink); }

    /** Ham ivme örneği (m/s², yerçekimi dahil), zaman damgası ns. */
    public synchronized void add(long tNs, float x, float y, float z) {
        samplesInSec++;
        if (lInit) {
            if (tNs - lastNs > 1_000_000_000L || tNs < lastNs) {   // süreksizlik
                count = 0;
                binStart = -1;
                bn = 0;
                bx = by = bz = 0;
            } else {
                float dx = x - lx, dy = y - ly, dz = z - lz;
                hfSum += dx * dx + dy * dy + dz * dz;
                hfN++;
            }
        }
        lx = x;
        ly = y;
        lz = z;
        lastNs = tNs;
        lInit = true;
        if (binStart < 0) binStart = tNs;
        while (tNs - binStart >= BIN_NS) {
            if (bn > 0) push((float) (bx / bn), (float) (by / bn), (float) (bz / bn));
            else if (pInit) push(px, py, pz);
            bx = by = bz = 0;
            bn = 0;
            binStart += BIN_NS;
        }
        bx += x;
        by += y;
        bz += z;
        bn++;
    }

    private void push(float x, float y, float z) {
        ring[0][pos] = x;
        ring[1][pos] = y;
        ring[2][pos] = z;
        pos = (pos + 1) % RING;
        if (count < RING) count++;
        total++;
        px = x;
        py = y;
        pz = z;
        pInit = true;
    }

    /** Saniyede bir (sonarın saat tıkıyla) çağrılır. */
    public synchronized void second(double t) {
        present = samplesInSec > 0;
        samplesInSec = 0;
        float hf = hfN > 0 ? (float) (hfSum / hfN) : Float.NaN;
        hfSum = 0;
        hfN = 0;
        boolean mov = false;
        float inten = 0;
        if (hf == hf) {
            float base = Sonar.median(mvRing, mvCount);
            if (mvCount >= 20 && base > 0) {
                inten = hf / base;
                mov = inten > 8 && hf > 2e-3f;
            }
            mvRing[mvPos] = hf;
            mvPos = (mvPos + 1) % mvRing.length;
            if (mvCount < mvRing.length) mvCount++;
        }
        moving = mov;
        moveIntensity = inten;
        if (mov) {
            lastMoveEnd = total;
            lastMoveTime = t;
            if (++moveRun >= 2) pd.resetRef();
        } else moveRun = 0;
        analyze();
        pd.second(t, lastA, valid, moving, lastMoveTime);
        ratio = pd.ratio;
        inPause = pd.inPause;
    }

    private void analyze() {
        int n = Math.min(count, WIN);
        long since = total - lastMoveEnd;
        if (since < n) n = (int) Math.max(0, since);
        if (n < MINWIN) {
            valid = false;
            quality = 0;
            rate = Float.NaN;
            lagSec = Float.NaN;
            lastA = Float.NaN;
            return;
        }
        int start = (pos - n + RING) % RING;
        double[][] a = new double[3][n];
        for (int k = 0; k < 3; k++) {
            for (int i = 0, p = start; i < n; i++, p = (p + 1) % RING) a[k][i] = ring[k][p];
            detrend(a[k], n);
        }
        // ana eksen (en çok salınan doğrultu)
        double[][] C = new double[3][3];
        for (int i = 0; i < n; i++)
            for (int r = 0; r < 3; r++)
                for (int c = r; c < 3; c++) C[r][c] += a[r][i] * a[c][i];
        for (int r = 0; r < 3; r++) for (int c = 0; c < r; c++) C[r][c] = C[c][r];
        double[] v = {0.577, 0.577, 0.577};
        for (int it = 0; it < 40; it++) {
            double[] w = new double[3];
            for (int r = 0; r < 3; r++) w[r] = C[r][0] * v[0] + C[r][1] * v[1] + C[r][2] * v[2];
            double nn = Math.sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2]);
            if (nn <= 0) break;
            for (int r = 0; r < 3; r++) v[r] = w[r] / nn;
        }
        double[] s = new double[n];
        for (int i = 0; i < n; i++) s[i] = a[0][i] * v[0] + a[1][i] * v[1] + a[2][i] * v[2];
        // 0,5 sn'lik ortalama: sensör gürültüsünü bastır
        double[] sm = new double[n];
        for (int i = 0; i < n; i++) {
            double acc = 0;
            int c = 0;
            for (int k = Math.max(0, i - 2); k <= Math.min(n - 1, i + 2); k++) { acc += s[k]; c++; }
            sm[i] = acc / c;
        }
        double e = 0;
        for (int i = 0; i < n; i++) e += sm[i] * sm[i];
        float rms = (float) Math.sqrt(e / n);
        ampRms = rms;
        // duraklama genliği: 1 sn arayla fark (düz kalınca hemen sıfıra iner, eğilim/ofsetten etkilenmez)
        double ea = 0;
        int na = 0;
        for (int i = Math.max(10, n - PW); i < n; i++) {
            double d = sm[i] - sm[i - 10];
            ea += d * d;
            na++;
        }
        lastA = na > 0 ? (float) Math.sqrt(ea / na) : Float.NaN;
        // otokorelasyon
        int lagMax = Math.min(LAG_MAX, n - 20);
        double[] cum = new double[n + 1];
        for (int i = 0; i < n; i++) cum[i + 1] = cum[i] + sm[i] * sm[i];
        float[] r = new float[lagMax + 2];
        for (int lag = 1; lag <= lagMax; lag++) {
            double acc = 0;
            for (int i = 0, j = lag; j < n; i++, j++) acc += sm[i] * sm[j];
            double nrm = Math.sqrt(cum[n - lag] * (cum[n] - cum[lag]));
            r[lag] = nrm > 0 ? (float) (acc / nrm) : 0;
        }
        int pk = Sonar.pickPeak(r, LAG_MIN, lagMax);
        if (pk < 0) {
            valid = false;
            quality = 0;
            rate = Float.NaN;
            lagSec = Float.NaN;
            return;
        }
        double lagF = pk;
        if (pk > 1 && pk < lagMax) {
            double y0 = r[pk - 1], y1 = r[pk], y2 = r[pk + 1], den = y0 - 2 * y1 + y2;
            if (den < 0) lagF = pk + 0.5 * (y0 - y2) / den;
        }
        quality = r[pk];
        rate = (float) (60.0 * FS / lagF);
        lagSec = (float) (lagF / FS);
        valid = quality >= Q_MIN && rate >= 5 && rate <= 45 && rms >= MIN_AMP;
        float[] w = new float[n];
        float g = rms > 0 ? 1f / rms : 0;
        for (int i = 0; i < n; i++) w[i] = (float) sm[i] * g;
        if (lastWave != null) {
            long o = (total - n) - (lastWaveEnd - lastWave.length);
            double c = 0;
            for (int i = 0; i < n; i++) {
                long j = i + o;
                if (j >= 0 && j < lastWave.length) c += w[i] * lastWave[(int) j];
            }
            if (c < 0) for (int i = 0; i < n; i++) w[i] = -w[i];
        }
        lastWave = w;
        lastWaveEnd = total;
        waveOut = w;
    }

    /** Son pencerenin soluk istatistikleri: {sayı, ort. aralık sn, CV}. */
    public float[] breathStats() {
        return valid ? Sonar.breathStats(waveOut, lagSec, FS) : null;
    }

    private static void detrend(double[] s, int n) {
        double st = 0, stt = 0, sv = 0, stv = 0;
        for (int i = 0; i < n; i++) { st += i; stt += (double) i * i; sv += s[i]; stv += i * s[i]; }
        double den = n * stt - st * st;
        double b = den != 0 ? (n * stv - st * sv) / den : 0, a = (sv - b * st) / n;
        for (int i = 0; i < n; i++) s[i] -= a + b * i;
    }
}
