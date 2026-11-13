package com.durmuseminalbayrak.uyku;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Hata günlüğü (telefonda files/hata.txt, en çok ~64 KB). Hiçbir yere gönderilmez; yalnızca CSV dışa
 * aktarılırken başlığa eklenir, böylece gece neden kesildiği anlaşılır.
 */
final class Diag {
    private Diag() { }

    private static volatile File dir;
    private static boolean installed;
    /** Çökmede çalıştırılır (ör. kayıt dosyasını kapatmak için). */
    static volatile Runnable onCrash;

    static synchronized void install(File d) {
        dir = d;
        if (installed) return;
        installed = true;
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((th, ex) -> {
            try { log("ÇÖKME [" + th.getName() + "]", ex); } catch (Throwable ignored) { }
            try {
                Runnable r = onCrash;
                if (r != null) r.run();
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(th, ex);
        });
    }

    static File file() {
        File d = dir;
        return d == null ? null : new File(d, "hata.txt");
    }

    static synchronized void log(String what, Throwable t) {
        File f = file();
        if (f == null) return;
        try {
            if (f.length() > 64 * 1024) {
                File old = new File(f.getParentFile(), "hata_eski.txt");
                old.delete();
                f.renameTo(old);
            }
            try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
                w.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + " " + what + "\n");
                if (t != null) {
                    StringWriter sw = new StringWriter();
                    t.printStackTrace(new PrintWriter(sw));
                    String st = sw.toString();
                    w.write(st.length() > 4000 ? st.substring(0, 4000) + "\n" : st);
                }
            }
        } catch (IOException ignored) {
        }
    }

    /** Günlüğün belirli bir andan sonraki satırları (en çok maxChars karakter, sondan); yoksa "". */
    static String since(long fromMillis, int maxChars) {
        File f = file();
        if (f == null || !f.exists()) return "";
        String all;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 64 * 1024 + 8192)];
            int n = 0, k;
            while (n < b.length && (k = in.read(b, n, b.length - n)) > 0) n += k;
            all = new String(b, 0, n, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
        // tarih damgalı ilk satırı bul: "yyyy-MM-dd HH:mm:ss ..."
        SimpleDateFormat p = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        StringBuilder out = new StringBuilder();
        boolean keep = false;
        for (String line : all.split("\n")) {
            if (line.length() >= 19 && line.charAt(4) == '-' && line.charAt(13) == ':') {
                try {
                    Date d = p.parse(line.substring(0, 19));
                    keep = d != null && d.getTime() >= fromMillis;
                } catch (Exception ignored) {
                }
            }
            if (keep) out.append(line).append('\n');
        }
        String s = out.toString();
        return s.length() > maxChars ? s.substring(s.length() - maxChars) : s;
    }
}
