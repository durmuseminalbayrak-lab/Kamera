package com.durmuseminalbayrak.uyku;

/**
 * Nefes hareketinin genliğini olağan düzeyiyle (son 3 dk'nın medyanı) karşılaştırır;
 * ≥10 sn süren belirgin düşüşleri "nefes duraklaması" olarak bildirir. Sonar ve ivmeölçer ortak kullanır.
 */
final class PauseDetector {
    interface Sink { void pause(double startSec, double durSec, float depth); }

    /** Genlik penceresi (4 sn) + fark aralığı (1 sn) kadar gecikmeyle algılanır; başlangıç geri alınır. */
    static final double LEAD = 4.0, TAIL = 1.0, MAX = 90;

    private final Sink sink;
    final float[] aRing = new float[180];
    int aPos, aCount;
    private final boolean[] vRing = new boolean[30];
    private int vPos;
    int calmValid;
    private double pauseStart, pauseEndT = -1e9;
    private float pauseMin;
    float ratio = Float.NaN;
    boolean inPause;

    PauseDetector(Sink s) { sink = s; }

    /** Pozisyon değişti: olağan genlik yeniden öğrenilsin. */
    void resetRef() { aCount = 0; }

    void reset() {
        aCount = 0;
        inPause = false;
    }

    double endTime() { return pauseEndT; }

    /** Saniyede bir: A = son ~4 sn'lik nefes hareketi genliği (NaN olabilir). */
    void second(double t, float A, boolean valid, boolean moving, double lastMoveTime) {
        boolean calm = !moving && t - lastMoveTime > 15;
        if (!inPause) {
            // son 30 sn'de geçerli nefes oranı (duraklamadan önce nefes olmalı)
            vRing[vPos] = valid && calm;
            vPos = (vPos + 1) % vRing.length;
            calmValid = 0;
            for (boolean v : vRing) if (v) calmValid++;
        }
        if (A == A && valid && calm && !inPause && t > pauseEndT + 30) {
            aRing[aPos] = A;
            aPos = (aPos + 1) % aRing.length;
            if (aCount < aRing.length) aCount++;
        }
        float ref = aCount >= 40 ? Sonar.median(aRing, aCount) : Float.NaN;
        ratio = (A == A && ref == ref && ref > 0) ? A / ref : Float.NaN;
        if (!inPause) {
            if (ratio == ratio && ratio < 0.25f && t - lastMoveTime > LEAD + 16 && calmValid >= 15) {
                inPause = true;
                pauseStart = t - LEAD;
                pauseMin = ratio;
            }
        } else {
            if (ratio == ratio) pauseMin = Math.min(pauseMin, ratio);
            double dur = t - pauseStart;
            if (moving || ratio != ratio || ratio > 0.45f) {
                pauseEndT = t;
                dur -= TAIL;
                if (dur >= 10 && dur <= MAX && sink != null) sink.pause(pauseStart, dur, pauseMin);
                inPause = false;
            } else if (dur > MAX) {   // çok uzun: kişi yok / sinyal kaybı, duraklama sayılmaz
                inPause = false;
                pauseEndT = t;
            }
        }
    }
}
