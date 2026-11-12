package com.durmuseminalbayrak.uyku;

/** Basit, yerinde çalışan radix-2 karmaşık FFT (Android bağımlılığı yok). */
final class Fft {
    final int n;
    private final double[] cos, sin;
    private final int[] rev;

    Fft(int n) {
        if (Integer.bitCount(n) != 1) throw new IllegalArgumentException("n 2'nin kuvveti olmalı");
        this.n = n;
        cos = new double[n / 2];
        sin = new double[n / 2];
        for (int i = 0; i < n / 2; i++) {
            double a = -2 * Math.PI * i / n;
            cos[i] = Math.cos(a);
            sin[i] = Math.sin(a);
        }
        rev = new int[n];
        int bits = Integer.numberOfTrailingZeros(n);
        for (int i = 0; i < n; i++) rev[i] = Integer.reverse(i) >>> (32 - bits);
    }

    /** İleri dönüşüm: X[k] = sum x[j] e^{-i 2π jk/n} (ölçeklemesiz). */
    void forward(double[] re, double[] im) { run(re, im, false); }

    /** Ters dönüşüm (ölçeklemesiz: sonucu n'e bölmek çağırana kalmış). */
    void inverse(double[] re, double[] im) { run(re, im, true); }

    private void run(double[] re, double[] im, boolean inv) {
        for (int i = 0; i < n; i++) {
            int j = rev[i];
            if (j > i) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int size = 2; size <= n; size <<= 1) {
            int half = size >> 1, step = n / size;
            for (int i = 0; i < n; i += size) {
                for (int j = 0, k = 0; j < half; j++, k += step) {
                    double wr = cos[k], wi = inv ? -sin[k] : sin[k];
                    int a = i + j, b = a + half;
                    double xr = re[b] * wr - im[b] * wi;
                    double xi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                }
            }
        }
    }
}
