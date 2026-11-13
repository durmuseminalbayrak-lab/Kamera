package com.durmuseminalbayrak.uyku;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Bir geceyi CSV olarak dışa aktarır (ses yok, yalnız ölçüm sayıları). Saniyelik kayıt varsa saniye
 * başına bir satır, yoksa 30 sn'lik dönem başına bir satır yazılır.
 */
public final class Exporter {
    private Exporter() { }

    public static String fileName(Night n) {
        return "uyku_" + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date(n.startMillis)) + ".csv";
    }

    private static String stage(int s) {
        switch (s) {
            case SleepAnalyzer.WAKE: return "uyanik";
            case SleepAnalyzer.REM: return "rem";
            case SleepAnalyzer.LIGHT: return "hafif";
            case SleepAnalyzer.DEEP: return "derin";
            case SleepAnalyzer.UNK: return "uyku_evre_yok";
            default: return "veri_yok";
        }
    }

    private static String f(float v, int dec) {
        if (v != v) return "";
        return String.format(Locale.US, "%." + dec + "f", v);
    }

    public static void write(Night n, SleepAnalyzer.Report r, OutputStream os) throws IOException {
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8), 1 << 16);
        SimpleDateFormat hms = new SimpleDateFormat("HH:mm:ss", Locale.US);
        w.write("# Uyku disa aktarma; baslangic " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(n.startMillis))
                + "; dosya surumu " + n.version + "; " + (n.secs.isEmpty() ? "donem basina satir (30 sn)" : "saniye basina satir") + "\n");
        w.write(String.format(Locale.US, "# ozet: uyku_dk=%.0f verim=%.0f dalma_dk=%.0f uyanma=%d duraklama=%d horlama_dk=%.0f kapsam=%.2f\n",
                r.tstMin, r.effPct, r.solMin, r.awakenings, r.pauseCount, r.snoreMin, r.coverage));
        if (!n.secs.isEmpty()) {
            w.write("saat,t_sn,nefes_hizi,kaynak,kalite,sonar_q,ivme_q,hareket,duraklama,sonar_gecerli,ivme_gecerli,sifirlama,"
                    + "yanki_db,taban_db,ultrason_snr_db,bant_disi_gurultu_db,dogrudan_db,sonar_hareket,ivme_hareket,genlik_orani,"
                    + "mesafe_cm,ivme_genlik,ses_db,kayma_ppm,horlama_ritmi,evre,olay\n");
            int ev = 0;
            for (Night.Sec c : n.secs) {
                int ei = (int) (c.t / Night.EPOCH_SEC);
                StringBuilder o = new StringBuilder();
                while (ev < n.events.size() && n.events.get(ev).t < c.t) {
                    Night.Event v = n.events.get(ev++);
                    if (o.length() > 0) o.append(" | ");
                    o.append(event(v));
                }
                w.write(hms.format(new Date(n.startMillis + (long) (c.t * 1000))) + "," + f(c.t, 1) + "," + f(c.rate, 2) + ","
                        + (c.src == 1 ? "sonar" : c.src == 2 ? "ivme" : c.src == 3 ? "horlama" : "-") + "," + f(c.q, 2) + "," + f(c.sq, 2) + "," + f(c.aq, 2) + ","
                        + (c.flags & 1) + "," + ((c.flags >> 1) & 1) + "," + ((c.flags >> 2) & 1) + "," + ((c.flags >> 3) & 1) + ","
                        + ((c.flags >> 4) & 1) + "," + f(c.echoDb, 1) + "," + f(c.noiseDb, 1) + "," + f(c.snrDb, 1) + ","
                        + f(c.outNoiseDb, 1) + "," + f(c.directDb, 1) + "," + f(c.sMove, 1) + "," + f(c.aMove, 1) + ","
                        + f(c.ratio, 3) + "," + (c.distCm >= 0 ? String.valueOf(c.distCm) : "") + "," + f(c.accAmp, 4) + ","
                        + f(c.soundDb, 1) + "," + f(c.drift, 1) + "," + f(c.snoreRate, 1) + "," + (ei < r.stage.length ? stage(r.stage[ei]) : "") + "," + o + "\n");
            }
        } else {
            w.write("saat,donem,nefes_hizi,kalite,gecerli_oran,hareket_sn,hareket_siddeti,genlik_orani,cv,duraklama,duraklama_sn,"
                    + "horlama,duzenli_horlama,gurultu,ses_db,ses_tepe_db,sifirlama,evre,olaylar\n");
            int ev = 0;
            for (int i = 0; i < n.epochs.size(); i++) {
                Night.Epoch e = n.epochs.get(i);
                float t1 = (i + 1) * Night.EPOCH_SEC;
                StringBuilder o = new StringBuilder();
                while (ev < n.events.size() && n.events.get(ev).t < t1) {
                    Night.Event v = n.events.get(ev++);
                    if (o.length() > 0) o.append(" | ");
                    o.append(event(v));
                }
                w.write(hms.format(new Date(n.startMillis + (long) (i * Night.EPOCH_SEC * 1000))) + "," + i + "," + f(e.rate, 2) + ","
                        + f(e.quality, 2) + "," + f(e.valid, 2) + "," + f(e.move, 0) + "," + f(e.moveMax, 1) + "," + f(e.amp, 3) + ","
                        + f(e.cv, 3) + "," + e.pauses + "," + f(e.pauseSec, 0) + "," + e.snores + "," + e.snoresReg + "," + e.noises + ","
                        + f(e.level, 1) + "," + f(e.levelMax, 1) + "," + e.flags + "," + (i < r.stage.length ? stage(r.stage[i]) : "")
                        + "," + o + "\n");
            }
        }
        w.flush();
    }

    private static String event(Night.Event v) {
        switch (v.type) {
            case Night.Event.PAUSE: return String.format(Locale.US, "duraklama %.0fs (derinlik %.2f)", v.dur, v.val);
            case Night.Event.MOVE: return String.format(Locale.US, "hareket %.0fs (siddet %.0f)", v.dur, v.val);
            case Night.Event.NOISE: return String.format(Locale.US, "gurultu %.0fdB", v.val);
            default: return "olay" + v.type;
        }
    }
}
