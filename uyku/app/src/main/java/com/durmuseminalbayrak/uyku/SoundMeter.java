package com.durmuseminalbayrak.uyku;

import java.util.Arrays;

/**
 * Duyulur bantta horlama ve gürültü olaylarını sayar. Ses kaydedilmez; yalnızca her 21 ms'lik
 * blokta iki bant enerjisi (94–1000 Hz ve 94–8000 Hz) hesaplanır.
 */
public final class SoundMeter {
    static final int N = Sonar.N;
    static final double BLK_SEC = N / (double) Sonar.FS;
    static final double REF = (N / 2.0) * (N / 2.0);   // tam ölçekli sinüs = 0 dBFS
    static final int LO_A = 2, LO_B = 21, BR_B = 170;
    static final int HIST = 1406;                       // ~30 s seviye geçmişi (taban için)

    private final Fft fft = new Fft(N);
    private final double[] re = new double[N], im = new double[N];
    private final float[] histLo = new float[HIST], histBr = new float[HIST];
    private int hPos, hCount, sinceFloor;
    private float floorLo = Float.NaN, floorBr = Float.NaN;
    private long blocks;

    // horlama durum makinesi
    private int act, inact;
    private boolean inEv;
    private long evStart;
    private double lastSnoreT = -1e9;
    // gürültü
    private int nAct;
    private boolean inNoise;
    private float noisePeak = -200;
    private double nLo, nBr, nStart;

    // dönem birikimleri
    private int epSnores, epSnoresReg, epNoises;
    private double epEnergy;
    private int epBlocks;
    private float epMax = -200;

    /** Son gürültü olayının zamanı/seviyesi (kayıt için). */
    public interface NoiseSink { void noise(double tSec, float levelDb); }
    private final NoiseSink sink;

    public volatile float levelDb = Float.NaN;

    public SoundMeter(NoiseSink sink) { this.sink = sink; }

    private static final double[] HANN = new double[N];
    static {
        for (int i = 0; i < N; i++) HANN[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / N);
    }

    public void process(float[] x) {
        for (int i = 0; i < N; i++) {
            re[i] = x[i] * HANN[i];   // Hann penceresi: duyulur bantta sızıntıyı azalt
            im[i] = 0;
        }
        fft.forward(re, im);
        double lo = 0, br = 0;
        for (int k = LO_A; k <= BR_B; k++) {
            double p = re[k] * re[k] + im[k] * im[k];
            br += p;
            if (k <= LO_B) lo += p;
        }
        // Hann penceresinin güç kaybını telafi et (×(1/0.375))
        lo /= 0.375;
        br /= 0.375;
        float dLo = db(lo), dBr = db(br);
        double t = blocks * BLK_SEC;
        blocks++;
        histLo[hPos] = dLo;
        histBr[hPos] = dBr;
        hPos = (hPos + 1) % HIST;
        if (hCount < HIST) hCount++;
        if (++sinceFloor >= 47 && hCount >= 94) {
            sinceFloor = 0;
            floorLo = pct(histLo, hCount, 0.10f);
            floorBr = pct(histBr, hCount, 0.10f);
        }
        levelDb = dBr;
        epEnergy += br / REF;
        epBlocks++;
        if (dBr > epMax) epMax = dBr;
        if (floorLo != floorLo) return;

        // --- horlama: alçak bant baskın ve tabanın ≥10 dB üstünde, 0,25–3,5 sn süren olay ---
        boolean on = dLo > floorLo + 10 && lo >= 0.5 * br;
        if (on) { act++; inact = 0; } else { inact++; act = 0; }
        if (!inEv && act >= 3) { inEv = true; evStart = blocks - 3; }
        if (inEv && inact >= 6) {
            inEv = false;
            double dur = (blocks - 6 - evStart) * BLK_SEC;
            double st = evStart * BLK_SEC;
            if (dur >= 0.25 && dur <= 3.5) {
                epSnores++;
                double gap = st - lastSnoreT;
                if (gap >= 2 && gap <= 10) epSnoresReg++;
                lastSnoreT = st;
            }
        }
        // --- gürültü: geniş bant tabanın ≥18 dB üstünde; alçak bant baskın ve kısa olanlar horlamadır ---
        boolean loud = dBr > floorBr + 18;
        if (loud) {
            nAct++;
            noisePeak = Math.max(noisePeak, dBr);
            nLo += lo;
            nBr += br;
            if (!inNoise && nAct >= 2) { inNoise = true; nStart = t - BLK_SEC; }
        } else {
            nAct = 0;
            if (inNoise) {
                inNoise = false;
                double dur = t - nStart;
                boolean snoreLike = nLo >= 0.5 * nBr && dur <= 3.5;
                if (!snoreLike) {
                    epNoises++;
                    if (sink != null) sink.noise(nStart, noisePeak);
                }
            }
            noisePeak = -200;
            nLo = nBr = 0;
        }
    }

    /** Dönem istatistikleri: {horlama, düzenli horlama, gürültü, ort. seviye dBFS, en yüksek dBFS} ve sıfırlama. */
    public float[] takeEpoch() {
        float mean = epBlocks > 0 ? (float) (10 * Math.log10(epEnergy / epBlocks + 1e-20)) : Float.NaN;
        float[] r = {epSnores, epSnoresReg, epNoises, mean, epMax};
        epSnores = epSnoresReg = epNoises = 0;
        epEnergy = 0;
        epBlocks = 0;
        epMax = -200;
        return r;
    }

    private static float db(double p) { return (float) (10 * Math.log10(p / REF + 1e-20)); }

    private static float pct(float[] a, int n, float q) {
        float[] c = Arrays.copyOf(a, n);
        Arrays.sort(c);
        return c[Math.min(n - 1, (int) (q * n))];
    }
}
