package com.durmuseminalbayrak.uyku;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 30 sn'lik dönemlerden uyku/uyanıklık ve tahmini evreler çıkarır.
 * Uyanıklık hareketten (aktigrafi mantığı), evreler nefes düzeninden tahmin edilir:
 * derin uykuda nefes çok düzenli ve hareketsiz, REM'de hareketsiz ama düzensizdir.
 * Bunlar tahmindir; tıbbi bir uyku testi yerine geçmez.
 */
public final class SleepAnalyzer {
    public static final int NODATA = 0, WAKE = 1, REM = 2, LIGHT = 3, DEEP = 4;
    static final float EP_MIN = Night.EPOCH_SEC / 60f;

    public static final class Report {
        public int[] stage = new int[0];
        public int onset = -1, last = -1;
        public float tibMin, tstMin, solMin, wasoMin, effPct, nodataMin;
        public int awakenings;
        public float wakeMin, remMin, lightMin, deepMin;
        public float rateMed = Float.NaN, rateLo = Float.NaN, rateHi = Float.NaN;
        public int pauseCount;
        public float pauseIndex, pauseLongest;
        public float snoreMin;
        public int noiseCount, moveCount;
        public float coverage;
        public final List<Night.Event> pauses = new ArrayList<>();
    }

    public static Report analyze(Night n) {
        Report r = new Report();
        int E = n.epochs.size();
        r.stage = new int[E];
        r.tibMin = E * EP_MIN;
        if (E == 0) return r;
        float[] mov = new float[E];
        boolean[] breath = new boolean[E], nod = new boolean[E];
        for (int i = 0; i < E; i++) {
            Night.Epoch e = n.epochs.get(i);
            mov[i] = e.move;
            breath[i] = e.valid >= 0.4f;
            nod[i] = e.valid < 0.15f && e.move < 1;
        }
        // 1) uyanıklık: ağırlıklı hareket penceresi
        double[] w = {0.15, 0.3, 1.0, 0.3, 0.15};
        int[] st = new int[E];
        for (int i = 0; i < E; i++) {
            double d = 0;
            for (int j = -2; j <= 2; j++) {
                int k = i + j;
                if (k >= 0 && k < E) d += w[j + 2] * mov[k];
            }
            boolean wake = d >= 6.0 || (mov[i] >= 2 && !breath[i] && !nod[i]);
            st[i] = wake ? WAKE : nod[i] ? NODATA : LIGHT;
        }
        // 2) veri yok bölümleri: kısa ise uyku say; uyanıklığın hemen ardından geliyorsa (kalktı) uyanık
        for (int i = 0; i < E; ) {
            if (st[i] != NODATA) { i++; continue; }
            int j = i;
            while (j < E && st[j] == NODATA) j++;
            int before = i > 0 ? st[i - 1] : WAKE, after = j < E ? st[j] : WAKE;
            int fill = NODATA;
            if (before == WAKE) fill = WAKE;
            else if (j - i <= 6 && before == LIGHT && after == LIGHT) fill = LIGHT;
            for (int k = i; k < j; k++) st[k] = fill;
            i = j;
        }
        // 3) uyanıklarla çevrili kısa (<3 dk) uyku parçaları uyanıklıktır
        for (int i = 0; i < E; ) {
            if (st[i] != LIGHT) { i++; continue; }
            int j = i;
            while (j < E && st[j] == LIGHT) j++;
            if (j - i < 6 && runBefore(st, i, WAKE) >= 10 && runAfter(st, j, WAKE) >= 10)
                for (int k = i; k < j; k++) st[k] = WAKE;
            i = j;
        }
        // 4) uykuya dalış: sonraki 10 dk'nın ≥%80'i uyku olan ilk dönem; son uyku dönemi
        int onset = -1;
        for (int i = 0; i < E && onset < 0; i++) {
            if (st[i] != LIGHT) continue;
            int cnt = 0, tot = 0;
            for (int k = i; k < Math.min(E, i + 20); k++) { tot++; if (st[k] == LIGHT) cnt++; }
            if (tot >= 6 && cnt >= 0.8 * tot) onset = i;
        }
        int last = -1;
        for (int i = E - 1; i >= 0; i--) if (st[i] == LIGHT) { last = i; break; }
        if (onset < 0) {
            Arrays.fill(st, WAKE);
            for (int i = 0; i < E; i++) if (nod[i]) st[i] = NODATA;
        } else {
            for (int i = 0; i < onset; i++) st[i] = WAKE;
            for (int i = last + 1; i < E; i++) st[i] = WAKE;
            stageSleep(n, st, onset, last);
        }
        r.stage = st;
        r.onset = onset;
        r.last = last;
        summarize(n, r);
        return r;
    }

    private static int runBefore(int[] st, int i, int v) {
        int c = 0;
        for (int k = i - 1; k >= 0 && st[k] == v; k--) c++;
        return i == 0 ? 99 : c;
    }

    private static int runAfter(int[] st, int j, int v) {
        int c = 0;
        for (int k = j; k < st.length && st[k] == v; k++) c++;
        return j >= st.length ? 99 : c;
    }

    /** Uyku dönemleri içinde nefes düzenine göre DEEP/LIGHT/REM. */
    private static void stageSleep(Night n, int[] st, int onset, int last) {
        int E = st.length;
        float[] cvW = new float[E], rateW = new float[E], movW = new float[E];
        List<Float> cvs = new ArrayList<>(), rates = new ArrayList<>();
        for (int i = onset; i <= last; i++) {
            if (st[i] != LIGHT) continue;
            float[] cvBuf = new float[11], rBuf = new float[11];
            int nc = 0, nr = 0;
            float mv = 0;
            for (int k = Math.max(onset, i - 5); k <= Math.min(last, i + 5); k++) {
                Night.Epoch e = n.epochs.get(k);
                if (e.cv == e.cv && e.valid >= 0.4f) cvBuf[nc++] = e.cv;
                if (e.rate == e.rate) rBuf[nr++] = e.rate;
                mv += e.move;
            }
            cvW[i] = nc >= 4 ? Sonar.median(cvBuf, nc) : Float.NaN;
            rateW[i] = nr >= 4 ? Sonar.median(rBuf, nr) : Float.NaN;
            movW[i] = mv;
            if (cvW[i] == cvW[i]) cvs.add(cvW[i]);
            if (rateW[i] == rateW[i]) rates.add(rateW[i]);
        }
        if (cvs.size() < 20) return;   // yeterli düzenlilik verisi yok: hepsi hafif uyku
        float p30 = pct(cvs, 0.30f), p70 = pct(cvs, 0.70f), rMed = rates.isEmpty() ? Float.NaN : pct(rates, 0.5f);
        float cvLo = Math.min(0.10f, p30), cvHi = Math.max(0.16f, p70);
        int[] raw = new int[E];
        for (int i = onset; i <= last; i++) {
            raw[i] = st[i];
            if (st[i] != LIGHT || cvW[i] != cvW[i]) continue;
            float frac = (i - onset) / (float) Math.max(1, last - onset);
            float minSince = (i - onset) * EP_MIN;
            boolean slowish = rMed != rMed || rateW[i] != rateW[i] || rateW[i] <= rMed + 0.5f;
            if (cvW[i] <= (frac <= 0.66f ? cvLo : 0.75f * cvLo) && movW[i] <= 1 && slowish) raw[i] = DEEP;
            else if (cvW[i] >= cvHi && movW[i] <= 4 && minSince >= 50) raw[i] = REM;
        }
        // 5'li çoğunluk süzgeci + en az 1,5 dk'lık evre bölümleri
        for (int i = onset; i <= last; i++) {
            if (st[i] != LIGHT) continue;
            int[] c = new int[5];
            for (int k = Math.max(onset, i - 2); k <= Math.min(last, i + 2); k++) if (st[k] == LIGHT) c[raw[k]]++;
            int best = LIGHT;
            if (c[DEEP] > c[best]) best = DEEP;
            if (c[REM] > c[best]) best = REM;
            st[i] = best;
        }
        for (int i = onset; i <= last; ) {
            int v = st[i], j = i;
            while (j <= last && st[j] == v) j++;
            if ((v == DEEP || v == REM) && j - i < 3) for (int k = i; k < j; k++) st[k] = LIGHT;
            i = j;
        }
    }

    private static void summarize(Night n, Report r) {
        int E = r.stage.length;
        int nod = 0, sleep = 0;
        for (int i = 0; i < E; i++) {
            switch (r.stage[i]) {
                case NODATA: nod++; break;
                case WAKE: r.wakeMin += EP_MIN; break;
                case REM: r.remMin += EP_MIN; sleep++; break;
                case LIGHT: r.lightMin += EP_MIN; sleep++; break;
                case DEEP: r.deepMin += EP_MIN; sleep++; break;
            }
        }
        r.nodataMin = nod * EP_MIN;
        r.tstMin = sleep * EP_MIN;
        float known = r.tibMin - r.nodataMin;
        r.effPct = known > 0 ? 100f * r.tstMin / known : 0;
        if (r.onset < 0) return;
        r.solMin = r.onset * EP_MIN;
        int cov = 0, span = 0;
        for (int i = r.onset; i <= r.last; i++) {
            span++;
            if (n.epochs.get(i).valid >= 0.4f) cov++;
            if (r.stage[i] == WAKE) r.wasoMin += EP_MIN;
        }
        r.coverage = span > 0 ? cov / (float) span : 0;
        for (int i = r.onset; i <= r.last; ) {
            if (r.stage[i] != WAKE) { i++; continue; }
            int j = i;
            while (j <= r.last && r.stage[j] == WAKE) j++;
            if (j - i >= 2) r.awakenings++;
            i = j;
        }
        List<Float> rates = new ArrayList<>();
        for (int i = r.onset; i <= r.last; i++) {
            Night.Epoch e = n.epochs.get(i);
            int s = r.stage[i];
            if ((s == LIGHT || s == DEEP || s == REM) && e.rate == e.rate) rates.add(e.rate);
            if ((s == LIGHT || s == DEEP || s == REM) && (e.snoresReg >= 2 || e.snores >= 4)) r.snoreMin += EP_MIN;
        }
        if (!rates.isEmpty()) {
            r.rateMed = pct(rates, 0.5f);
            r.rateLo = pct(rates, 0.1f);
            r.rateHi = pct(rates, 0.9f);
        }
        double t0 = r.onset * Night.EPOCH_SEC, t1 = (r.last + 1) * Night.EPOCH_SEC;
        for (Night.Event v : n.events) {
            if (v.t < t0 || v.t > t1) {
                if (v.type == Night.Event.NOISE) r.noiseCount++;
                continue;
            }
            if (v.type == Night.Event.PAUSE) {
                int ei = (int) (v.t / Night.EPOCH_SEC);
                if (ei >= 0 && ei < E && r.stage[ei] == WAKE) continue;   // uyanıkken sayılmaz
                r.pauseCount++;
                r.pauseLongest = Math.max(r.pauseLongest, v.dur);
                r.pauses.add(v);
            } else if (v.type == Night.Event.MOVE) r.moveCount++;
            else if (v.type == Night.Event.NOISE) r.noiseCount++;
        }
        r.pauseIndex = r.tstMin > 0 ? r.pauseCount / (r.tstMin / 60f) : 0;
    }

    private static float pct(List<Float> l, float q) {
        float[] a = new float[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        Arrays.sort(a);
        return a[Math.min(a.length - 1, (int) (q * a.length))];
    }
}
