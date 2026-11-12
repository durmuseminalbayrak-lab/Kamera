package com.durmuseminalbayrak.kamera;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Saf Java görüntü işleme (Android'e bağımlı değil, bilgisayarda test edilebilir).
 * Pikseller: ARGB int dizisi (0xAARRGGBB), gri: byte dizisi (0..255, & 0xFF ile okunur).
 */
final class ImageTools {
    private ImageTools() { }

    static int clamp(int v, int a, int b) { return v < a ? a : (v > b ? b : v); }
    static float clampf(float v, float a, float b) { return v < a ? a : (v > b ? b : v); }

    // ---------------- Temel ----------------
    static byte[] gray(int[] px) {
        byte[] g = new byte[px.length];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            g[i] = (byte) ((((c >> 16) & 255) * 77 + ((c >> 8) & 255) * 150 + (c & 255) * 29) >> 8);
        }
        return g;
    }

    /** Kutu ortalamasıyla f kat küçültme */
    static byte[] down(byte[] g, int w, int h, int f, int[] outWH) {
        int ow = Math.max(1, w / f), oh = Math.max(1, h / f);
        byte[] o = new byte[ow * oh];
        int area = f * f;
        for (int y = 0; y < oh; y++) {
            for (int x = 0; x < ow; x++) {
                int s = 0;
                for (int yy = 0; yy < f; yy++) {
                    int row = (y * f + yy) * w + x * f;
                    for (int xx = 0; xx < f; xx++) s += g[row + xx] & 255;
                }
                o[y * ow + x] = (byte) (s / area);
            }
        }
        outWH[0] = ow;
        outWH[1] = oh;
        return o;
    }

    /** Bölgede Laplace varyansı (netlik ölçüsü) */
    static double lapVar(byte[] g, int w, int h, int x0, int y0, int cw, int ch) {
        x0 = clamp(x0, 1, w - 2);
        y0 = clamp(y0, 1, h - 2);
        int x1 = Math.min(w - 1, x0 + cw), y1 = Math.min(h - 1, y0 + ch);
        double s = 0, s2 = 0;
        long n = 0;
        for (int y = y0; y < y1; y++) {
            int i = y * w + x0;
            for (int x = x0; x < x1; x++, i++) {
                int l = 4 * (g[i] & 255) - (g[i - 1] & 255) - (g[i + 1] & 255) - (g[i - w] & 255) - (g[i + w] & 255);
                s += l;
                s2 += (double) l * l;
                n++;
            }
        }
        if (n == 0) return 0;
        double m = s / n;
        return s2 / n - m * m;
    }

    static int percentile(int[] hist, long n, double p) {
        long a = 0, t = (long) (n * p);
        for (int i = 0; i < hist.length; i++) {
            a += hist[i];
            if (a >= t) return i;
        }
        return hist.length - 1;
    }

    // ---------------- Belge algılama ----------------
    /**
     * Gri görüntüde (kâğıt genelde zeminden açıktır) belgeyi bulur.
     * Dönüş: 4 köşe [x0,y0, x1,y1, x2,y2, x3,y3] (0..1, sol-üstten saat yönünde) + [8]=doluluk oranı, ya da null.
     */
    static float[] detectDoc(byte[] g, int w, int h) {
        int n = w * h;
        int[] b = new int[n];
        for (int i = 0; i < n; i++) b[i] = g[i] & 255;
        int[] bl = b.clone();
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                bl[i] = (b[i - w - 1] + b[i - w] + b[i - w + 1] + b[i - 1] + b[i] + b[i + 1] + b[i + w - 1] + b[i + w] + b[i + w + 1]) / 9;
            }
        }
        int[] hist = new int[256];
        for (int i = 0; i < n; i++) hist[bl[i]]++;
        // Otsu eşiği
        double sum = 0;
        for (int i = 0; i < 256; i++) sum += (double) i * hist[i];
        double sB = 0, max = 0;
        long wB = 0;
        int T = 128;
        for (int i = 0; i < 256; i++) {
            wB += hist[i];
            if (wB == 0) continue;
            long wF = n - wB;
            if (wF == 0) break;
            sB += (double) i * hist[i];
            double mB = sB / wB, mF = (sum - sB) / wF, v = (double) wB * wF * (mB - mF) * (mB - mF);
            if (v > max) { max = v; T = i; }
        }
        byte[] m = new byte[n];
        for (int i = 0; i < n; i++) m[i] = (byte) (bl[i] > T ? 1 : 0);
        // Merkeze en yakın parlak noktadan başla
        int seed = -1;
        long bd = Long.MAX_VALUE;
        int cx = w / 2, cy = h / 2, rx = w / 5, ry = h / 5;
        for (int y = cy - ry; y <= cy + ry; y += 2) {
            for (int x = cx - rx; x <= cx + rx; x += 2) {
                if (x < 0 || y < 0 || x >= w || y >= h) continue;
                int i = y * w + x;
                if (m[i] == 1) {
                    long dd = (long) (x - cx) * (x - cx) + (long) (y - cy) * (y - cy);
                    if (dd < bd) { bd = dd; seed = i; }
                }
            }
        }
        if (seed < 0) return null;
        int[] q = new int[n];
        int qh = 0, qt = 0;
        q[qt++] = seed;
        m[seed] = 2;
        long cnt = 0, sx = 0, sy = 0;
        int[] bx = new int[n], by = new int[n];
        int bn = 0;
        while (qh < qt) {
            int i = q[qh++], x = i % w, y = i / w;
            cnt++;
            sx += x;
            sy += y;
            boolean edge = false;
            if (x > 0) { int k = i - 1; if (m[k] == 1) { m[k] = 2; q[qt++] = k; } else if (m[k] == 0) edge = true; } else edge = true;
            if (x < w - 1) { int k = i + 1; if (m[k] == 1) { m[k] = 2; q[qt++] = k; } else if (m[k] == 0) edge = true; } else edge = true;
            if (y > 0) { int k = i - w; if (m[k] == 1) { m[k] = 2; q[qt++] = k; } else if (m[k] == 0) edge = true; } else edge = true;
            if (y < h - 1) { int k = i + w; if (m[k] == 1) { m[k] = 2; q[qt++] = k; } else if (m[k] == 0) edge = true; } else edge = true;
            if (edge) { bx[bn] = x; by[bn] = y; bn++; }
        }
        if (cnt < n * 0.08 || cnt > n * 0.95 || bn < 8) return null;
        double mx = (double) sx / cnt, my = (double) sy / cnt;
        int a = far(bx, by, bn, mx, my);
        int c2 = far(bx, by, bn, bx[a], by[a]);
        double ax = bx[a], ay = by[a], cx2 = bx[c2], cy2 = by[c2];
        double pMax = 0, pMin = 0;
        int iMax = -1, iMin = -1;
        for (int k = 0; k < bn; k++) {
            double s = (cx2 - ax) * (by[k] - ay) - (cy2 - ay) * (bx[k] - ax);
            if (s > pMax) { pMax = s; iMax = k; }
            if (s < pMin) { pMin = s; iMin = k; }
        }
        if (iMax < 0 || iMin < 0) return null;
        float[][] pts = {{(float) ax, (float) ay}, {bx[iMax], by[iMax]}, {(float) cx2, (float) cy2}, {bx[iMin], by[iMin]}};
        if (Math.abs(polyArea(pts)) < n * 0.08) return null;
        double qa = Math.abs(polyArea(pts));
        pts = orderQuad(pts);
        float[] r = new float[9];
        for (int k = 0; k < 4; k++) {
            r[k * 2] = (pts[k][0] + 0.5f) / w;
            r[k * 2 + 1] = (pts[k][1] + 0.5f) / h;
        }
        r[8] = (float) Math.min(1.5, cnt / Math.max(1.0, qa)); // doluluk: kâğıt dörtgeni neredeyse tamamen doldurur
        return r;
    }

    private static int far(int[] bx, int[] by, int bn, double px, double py) {
        int bi = 0;
        double bv = -1;
        for (int k = 0; k < bn; k++) {
            double v = (bx[k] - px) * (bx[k] - px) + (by[k] - py) * (by[k] - py);
            if (v > bv) { bv = v; bi = k; }
        }
        return bi;
    }

    static double polyArea(float[][] p) {
        double s = 0;
        for (int i = 0; i < p.length; i++) {
            float[] a = p[i], b = p[(i + 1) % p.length];
            s += (double) a[0] * b[1] - (double) b[0] * a[1];
        }
        return s / 2;
    }

    /** Köşeleri sol-üstten başlayarak saat yönünde sıralar */
    static float[][] orderQuad(float[][] p) {
        final float cx = (p[0][0] + p[1][0] + p[2][0] + p[3][0]) / 4, cy = (p[0][1] + p[1][1] + p[2][1] + p[3][1]) / 4;
        float[][] s = p.clone();
        Arrays.sort(s, (a, b) -> Double.compare(Math.atan2(a[1] - cy, a[0] - cx), Math.atan2(b[1] - cy, b[0] - cx)));
        int st = 0;
        float best = Float.MAX_VALUE;
        for (int k = 0; k < 4; k++) {
            float v = s[k][0] + s[k][1];
            if (v < best) { best = v; st = k; }
        }
        float[][] r = new float[4][];
        for (int k = 0; k < 4; k++) r[k] = s[(st + k) % 4].clone();
        return r;
    }

    /** OTO belge önerisi için: makul boyutta dörtgen ve içinde yazı/desen olmalı (beyaz koltuk/duvar belge sayılmasın) */
    static boolean looksLikeDocument(byte[] g, int w, int h, float[] q) {
        if (docScore(g, w, h, q) <= DOC_TEXTURE_MIN) return false;
        double ink = docInk(g, w, h, q);
        if (ink < 0.002 || ink > 0.35) return false;
        return edgeContrast(g, w, h, q) >= 0.6;
    }

    /**
     * Dört kenarın en zayıfı: kenar boyunca içerisi dışarısından belirgin açık olan noktaların oranı.
     * Kâğıdın dört kenarı da zeminden ayrışır; koltuk/duvar gibi birleşik bölgelerde en az bir kenar ayrışmaz.
     */
    static double edgeContrast(byte[] g, int w, int h, float[] q) {
        double cx = 0, cy = 0;
        for (int k = 0; k < 4; k++) { cx += q[2 * k] * w / 4.0; cy += q[2 * k + 1] * h / 4.0; }
        double off = Math.max(2, Math.min(w, h) * 0.03);
        double worst = 1;
        for (int k = 0; k < 4; k++) {
            int j = (k + 1) % 4;
            double x1 = q[2 * k] * w, y1 = q[2 * k + 1] * h, x2 = q[2 * j] * w, y2 = q[2 * j + 1] * h;
            double ex = x2 - x1, ey = y2 - y1, len = Math.hypot(ex, ey);
            if (len < 4) return 0;
            double nx = -ey / len, ny = ex / len;
            double mx = (x1 + x2) / 2, my = (y1 + y2) / 2;
            if ((cx - mx) * nx + (cy - my) * ny > 0) { nx = -nx; ny = -ny; } // dışarıyı göster
            int valid = 0, good = 0;
            for (int t = 1; t <= 20; t++) {
                double u = 0.1 + 0.8 * t / 21.0;
                double px = x1 + ex * u, py = y1 + ey * u;
                int ix = (int) Math.round(px - nx * off), iy = (int) Math.round(py - ny * off);
                int ox = (int) Math.round(px + nx * off), oy = (int) Math.round(py + ny * off);
                if (ox < 0 || oy < 0 || ox >= w || oy >= h || ix < 0 || iy < 0 || ix >= w || iy >= h) continue;
                valid++;
                if ((g[iy * w + ix] & 255) - (g[oy * w + ox] & 255) >= 12) good++;
            }
            double sc = valid >= 5 ? (double) good / valid : 1; // görüntü kenarına dayanan kenarı yok say
            worst = Math.min(worst, sc);
        }
        return worst;
    }

    /** Dörtgenin iç bölgesindeki mürekkep oranı */
    static double docInk(byte[] g, int w, int h, float[] q) {
        float minX = w, maxX = 0, minY = h, maxY = 0;
        for (int k = 0; k < 4; k++) {
            minX = Math.min(minX, q[2 * k] * w); maxX = Math.max(maxX, q[2 * k] * w);
            minY = Math.min(minY, q[2 * k + 1] * h); maxY = Math.max(maxY, q[2 * k + 1] * h);
        }
        int x0 = (int) (minX + (maxX - minX) * 0.2f), y0 = (int) (minY + (maxY - minY) * 0.2f);
        return inkRatio(g, w, h, x0, y0, (int) ((maxX - minX) * 0.6f), (int) ((maxY - minY) * 0.6f));
    }

    static final double DOC_TEXTURE_MIN = 150;

    /** Bölgede, çevresinin ortalamasından %25'ten fazla koyu piksellerin oranı (yazı = mürekkep) */
    static double inkRatio(byte[] g, int w, int h, int x0, int y0, int cw, int ch) {
        x0 = clamp(x0, 0, w - 1);
        y0 = clamp(y0, 0, h - 1);
        int x1 = Math.min(w, x0 + cw), y1 = Math.min(h, y0 + ch);
        int r = 4;
        long[] I = integral(g, w, h);
        long ink = 0, n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                double m = boxMean(I, w, h, x, y, r);
                int v = g[y * w + x] & 255;
                if (m > 60 && v < m * 0.75) ink++;
                n++;
            }
        }
        return n == 0 ? 0 : (double) ink / n;
    }

    /** Dörtgen alanı uygunsa iç bölgenin doku (Laplace varyansı) değeri, değilse -1 */
    static double docScore(byte[] g, int w, int h, float[] q) {
        if (q == null) return -1;
        float[][] p = {{q[0] * w, q[1] * h}, {q[2] * w, q[3] * h}, {q[4] * w, q[5] * h}, {q[6] * w, q[7] * h}};
        double area = Math.abs(polyArea(p)) / ((double) w * h);
        if (area < 0.15 || area > 0.9) return -1;
        float minX = w, maxX = 0, minY = h, maxY = 0;
        for (float[] a : p) { minX = Math.min(minX, a[0]); maxX = Math.max(maxX, a[0]); minY = Math.min(minY, a[1]); maxY = Math.max(maxY, a[1]); }
        int bw = (int) ((maxX - minX) * 0.5f), bh = (int) ((maxY - minY) * 0.5f);
        int x0 = (int) (minX + (maxX - minX) * 0.25f), y0 = (int) (minY + (maxY - minY) * 0.25f);
        if (bw < 8 || bh < 8) return -1;
        return lapVar(g, w, h, x0, y0, bw, bh);
    }

    // ---------------- Perspektif düzeltme ----------------
    static double[] homography(double[][] P, double[][] Q) {
        double[][] A = new double[8][8];
        double[] B = new double[8];
        for (int i = 0; i < 4; i++) {
            double u = P[i][0], v = P[i][1], x = Q[i][0], y = Q[i][1];
            A[2 * i] = new double[]{u, v, 1, 0, 0, 0, -u * x, -v * x};
            B[2 * i] = x;
            A[2 * i + 1] = new double[]{0, 0, 0, u, v, 1, -u * y, -v * y};
            B[2 * i + 1] = y;
        }
        int n = 8;
        for (int i = 0; i < n; i++) {
            int p = i;
            for (int r = i + 1; r < n; r++) if (Math.abs(A[r][i]) > Math.abs(A[p][i])) p = r;
            double[] t = A[i]; A[i] = A[p]; A[p] = t;
            double tb = B[i]; B[i] = B[p]; B[p] = tb;
            for (int r = i + 1; r < n; r++) {
                double f = A[r][i] / A[i][i];
                for (int c = i; c < n; c++) A[r][c] -= f * A[i][c];
                B[r] -= f * B[i];
            }
        }
        double[] X = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double s = B[i];
            for (int c = i + 1; c < n; c++) s -= A[i][c] * X[c];
            X[i] = s / A[i][i];
        }
        return X;
    }

    /** q: kaynakta 4 köşe (piksel, sol-üstten saat yönünde). outWH'ye çıktı boyutu yazılır. */
    static int[] warp(int[] src, int sw, int sh, float[][] q, int maxSide, int[] outWH) {
        double ow = Math.max(dist(q[0], q[1]), dist(q[3], q[2])), oh = Math.max(dist(q[0], q[3]), dist(q[1], q[2]));
        double k = Math.min(1.0, maxSide / Math.max(ow, oh));
        int W = Math.max(16, (int) Math.round(ow * k)), H = Math.max(16, (int) Math.round(oh * k));
        double[] Hm = homography(new double[][]{{0, 0}, {W, 0}, {W, H}, {0, H}},
                new double[][]{{q[0][0], q[0][1]}, {q[1][0], q[1][1]}, {q[2][0], q[2][1]}, {q[3][0], q[3][1]}});
        double a = Hm[0], b = Hm[1], c = Hm[2], d = Hm[3], e = Hm[4], f = Hm[5], g = Hm[6], hh = Hm[7];
        int[] out = new int[W * H];
        for (int v = 0; v < H; v++) {
            double vv = v + 0.5;
            double nx = b * vv + c + a * 0.5, ny = e * vv + f + d * 0.5, dn = hh * vv + 1 + g * 0.5;
            int j = v * W;
            for (int u = 0; u < W; u++, j++) {
                double X = nx / dn - 0.5, Y = ny / dn - 0.5;
                nx += a;
                ny += d;
                dn += g;
                int x0 = (int) Math.floor(X), y0 = (int) Math.floor(Y);
                double fx = X - x0, fy = Y - y0;
                if (x0 < 0) { x0 = 0; fx = 0; } else if (x0 > sw - 2) { x0 = sw - 2; fx = 1; }
                if (y0 < 0) { y0 = 0; fy = 0; } else if (y0 > sh - 2) { y0 = sh - 2; fy = 1; }
                int i00 = y0 * sw + x0;
                int p00 = src[i00], p10 = src[i00 + 1], p01 = src[i00 + sw], p11 = src[i00 + sw + 1];
                int r = bil((p00 >> 16) & 255, (p10 >> 16) & 255, (p01 >> 16) & 255, (p11 >> 16) & 255, fx, fy);
                int gg = bil((p00 >> 8) & 255, (p10 >> 8) & 255, (p01 >> 8) & 255, (p11 >> 8) & 255, fx, fy);
                int bb = bil(p00 & 255, p10 & 255, p01 & 255, p11 & 255, fx, fy);
                out[j] = 0xFF000000 | (r << 16) | (gg << 8) | bb;
            }
        }
        outWH[0] = W;
        outWH[1] = H;
        return out;
    }

    private static int bil(int a, int b, int c, int d, double fx, double fy) {
        double v = (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy;
        return clamp((int) Math.round(v), 0, 255);
    }

    private static double dist(float[] a, float[] b) { return Math.hypot(a[0] - b[0], a[1] - b[1]); }

    /** 90° saat yönünde döndür (w×h -> h×w) */
    static int[] rot90(int[] px, int w, int h) {
        int[] o = new int[px.length];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) o[x * h + (h - 1 - y)] = px[row + x];
        }
        return o;
    }

    // ---------------- Belge filtreleri ----------------
    static final String F_ENH = "enh", F_BW = "bw", F_GRAY = "gray", F_ORIG = "orig";

    static void filter(int[] px, int w, int h, String f) {
        if (F_ORIG.equals(f)) return;
        int n = w * h;
        byte[] g = gray(px);
        if (F_GRAY.equals(f)) {
            for (int i = 0; i < n; i++) { int v = g[i] & 255; px[i] = 0xFF000000 | (v << 16) | (v << 8) | v; }
            return;
        }
        if (F_BW.equals(f)) {
            long[] I = integral(g, w, h);
            int r = Math.max(8, Math.round(Math.min(w, h) / 28f));
            for (int y = 0, i = 0; y < h; y++) {
                for (int x = 0; x < w; x++, i++) {
                    double mm = boxMean(I, w, h, x, y, r);
                    int v = g[i] & 255;
                    int o = (v < mm * 0.86 && v < mm - 8) ? 0 : 255;
                    px[i] = 0xFF000000 | (o << 16) | (o << 8) | o;
                }
            }
            return;
        }
        // "Net renk": kâğıt arka planını blok bazında en parlak değerlerden tahmin edip beyaza çeker
        int B = Math.max(8, (int) Math.ceil(Math.min(w, h) / 40.0));
        int gw = (w + B - 1) / B, gh = (h + B - 1) / B;
        float[] G = new float[gw * gh];
        int[] hs = new int[256];
        for (int by = 0; by < gh; by++) {
            for (int bx = 0; bx < gw; bx++) {
                Arrays.fill(hs, 0);
                int cnt = 0;
                for (int y = by * B; y < Math.min(h, by * B + B); y += 2) {
                    for (int x = bx * B; x < Math.min(w, bx * B + B); x += 2) { hs[g[y * w + x] & 255]++; cnt++; }
                }
                G[by * gw + bx] = percentile(hs, cnt, 0.9);
            }
        }
        for (int it = 0; it < 2; it++) {
            float[] T = new float[G.length];
            for (int y = 0; y < gh; y++) {
                for (int x = 0; x < gw; x++) {
                    float s = 0;
                    int c = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int yy = y + dy, xx = x + dx;
                            if (yy >= 0 && yy < gh && xx >= 0 && xx < gw) { s += G[yy * gw + xx]; c++; }
                        }
                    }
                    T[y * gw + x] = s / c;
                }
            }
            G = T;
        }
        for (int y = 0, i = 0; y < h; y++) {
            float gyf = clampf((y + 0.5f) / B - 0.5f, 0, gh - 1);
            int y0 = (int) gyf, y1 = Math.min(gh - 1, y0 + 1);
            float fy = gyf - y0;
            for (int x = 0; x < w; x++, i++) {
                float gxf = clampf((x + 0.5f) / B - 0.5f, 0, gw - 1);
                int x0 = (int) gxf, x1 = Math.min(gw - 1, x0 + 1);
                float fx = gxf - x0;
                float bg = (G[y0 * gw + x0] * (1 - fx) + G[y0 * gw + x1] * fx) * (1 - fy) + (G[y1 * gw + x0] * (1 - fx) + G[y1 * gw + x1] * fx) * fy;
                float k = 255f / Math.max(bg, 50f);
                int c = px[i];
                int r = clamp(Math.round((((c >> 16) & 255) * k - 30) * 255f / 225f), 0, 255);
                int gg = clamp(Math.round((((c >> 8) & 255) * k - 30) * 255f / 225f), 0, 255);
                int bb = clamp(Math.round(((c & 255) * k - 30) * 255f / 225f), 0, 255);
                px[i] = 0xFF000000 | (r << 16) | (gg << 8) | bb;
            }
        }
    }

    private static long[] integral(byte[] g, int w, int h) {
        int W = w + 1;
        long[] I = new long[W * (h + 1)];
        for (int y = 0; y < h; y++) {
            long s = 0;
            for (int x = 0; x < w; x++) {
                s += g[y * w + x] & 255;
                I[(y + 1) * W + x + 1] = I[y * W + x + 1] + s;
            }
        }
        return I;
    }

    private static double boxMean(long[] I, int w, int h, int x, int y, int r) {
        int W = w + 1, x0 = Math.max(0, x - r), x1 = Math.min(w, x + r + 1), y0 = Math.max(0, y - r), y1 = Math.min(h, y + r + 1);
        return (double) (I[y1 * W + x1] - I[y0 * W + x1] - I[y1 * W + x0] + I[y0 * W + x0]) / ((x1 - x0) * (y1 - y0));
    }

    // ---------------- Kare hizalama ----------------
    /** cur'un ref'e göre kaymasını bulur (mutlak fark toplamı en küçük olan). Dönüş {dx, dy, skor} */
    static long[] align(byte[] ref, byte[] cur, int w, int h, int dx0, int dx1, int dy0, int dy1, float margin, int step) {
        int x0 = Math.max(Math.max((int) (w * margin), -dx0), 0), x1 = Math.min(Math.min(w - (int) (w * margin), w - dx1), w);
        int y0 = Math.max(Math.max((int) (h * margin), -dy0), 0), y1 = Math.min(Math.min(h - (int) (h * margin), h - dy1), h);
        long best = Long.MAX_VALUE;
        int bx = 0, by = 0;
        if (x1 <= x0 || y1 <= y0) return new long[]{0, 0, 0};
        for (int dy = dy0; dy <= dy1; dy++) {
            for (int dx = dx0; dx <= dx1; dx++) {
                long s = 0;
                for (int y = y0; y < y1 && s < best; y += step) {
                    int ro = y * w, co = (y + dy) * w + dx;
                    for (int x = x0; x < x1; x += step) {
                        int d = (ref[ro + x] & 255) - (cur[co + x] & 255);
                        s += d < 0 ? -d : d;
                    }
                }
                if (s < best) { best = s; bx = dx; by = dy; }
            }
        }
        return new long[]{bx, by, best};
    }

    // ---------------- Gece birleştirme ----------------
    /** Aynı sahnenin karelerini hizalayıp ortalar: kumlanma azalır, ayrıntı korunur. */
    static final class Merger {
        final int w, h;
        private short[] ar, ag, ab;
        private int count = 0;
        private byte[] refFull, ref2, ref8;
        private int w2, h2, w8, h8;
        int lastDx, lastDy;

        Merger(int w, int h) {
            this.w = w;
            this.h = h;
            ar = new short[w * h];
            ag = new short[w * h];
            ab = new short[w * h];
        }

        int count() { return count; }

        void add(int[] px) {
            if (count >= 8) return; // birikim sınırı (short taşmasın)
            byte[] g = gray(px);
            int[] wh = new int[2];
            byte[] g2 = down(g, w, h, 2, wh);
            int cw2 = wh[0], ch2 = wh[1];
            byte[] g8 = down(g2, cw2, ch2, 4, wh);
            int cw8 = wh[0], ch8 = wh[1];
            int dx = 0, dy = 0;
            if (count == 0) {
                refFull = g; ref2 = g2; ref8 = g8;
                w2 = cw2; h2 = ch2; w8 = cw8; h8 = ch8;
            } else {
                int R = 12;
                long[] c = align(ref8, g8, w8, h8, -R, R, -R, R, 0.15f, 1);
                int gx = (int) c[0] * 4, gy = (int) c[1] * 4;
                long[] f = align(ref2, g2, w2, h2, gx - 5, gx + 5, gy - 5, gy + 5, 0.25f, 2);
                int fx = (int) f[0] * 2, fy = (int) f[1] * 2;
                long[] z = align(refFull, g, w, h, fx - 2, fx + 2, fy - 2, fy + 2, 0.35f, 2);
                dx = (int) z[0];
                dy = (int) z[1];
            }
            lastDx = dx;
            lastDy = dy;
            for (int y = 0; y < h; y++) {
                int sy = clamp(y + dy, 0, h - 1), row = sy * w, o = y * w;
                for (int x = 0; x < w; x++, o++) {
                    int p = px[row + clamp(x + dx, 0, w - 1)];
                    ar[o] += (short) ((p >> 16) & 255);
                    ag[o] += (short) ((p >> 8) & 255);
                    ab[o] += (short) (p & 255);
                }
            }
            count++;
        }

        /** Düz ortalama. Bellek için biriktiricileri bırakır. */
        int[] result() {
            int n = w * h;
            int[] out = new int[n];
            int c = Math.max(1, count), half = c / 2;
            for (int i = 0; i < n; i++) {
                out[i] = 0xFF000000 | (((ar[i] + half) / c) << 16) | (((ag[i] + half) / c) << 8) | ((ab[i] + half) / c);
            }
            ar = ag = ab = null;
            refFull = ref2 = ref8 = null;
            return out;
        }
    }

    // ---------------- Gece son işlem: parlaklık, gürültü azaltma, keskinleştirme ----------------
    /**
     * Parlaklık gürültüsünü tahmin eder: 16x16 bloklarda (piksel - 5x5 ortalama) standart sapması,
     * en düz %20'lik blokların değeri. Telefonun işlemesiyle kümelenmiş gürültüyü de yakalar.
     */
    static float estimateNoise(int[] px, int w, int h) {
        int B = 16, stride = Math.max(24, Math.min(w, h) / 60);
        float[] vals = new float[((w / stride) + 1) * ((h / stride) + 1)];
        int nv = 0;
        for (int by = 2; by + B + 2 < h; by += stride) {
            for (int bx = 2; bx + B + 2 < w; bx += stride) {
                double s = 0, s2 = 0;
                for (int y = by; y < by + B; y++) {
                    for (int x = bx; x < bx + B; x++) {
                        int m = 0;
                        for (int dy = -2; dy <= 2; dy++) {
                            int row = (y + dy) * w;
                            for (int dx = -2; dx <= 2; dx++) m += luma(px[row + x + dx]);
                        }
                        double d = luma(px[y * w + x]) - m / 25.0;
                        s += d;
                        s2 += d * d;
                    }
                }
                int n = B * B;
                double mean = s / n;
                vals[nv++] = (float) Math.sqrt(Math.max(0, s2 / n - mean * mean));
            }
        }
        if (nv == 0) return 0;
        float[] v = Arrays.copyOf(vals, nv);
        Arrays.sort(v);
        return v[(int) (nv * 0.2f)];
    }

    private static int luma(int c) { return (((c >> 16) & 255) * 77 + ((c >> 8) & 255) * 150 + (c & 255) * 29) >> 8; }

    /**
     * expoGain: pozlama kaybı (doğrusal ışık oranı, >=1). Parlaklığı geri verir, parlak yerleri yumuşakça korur,
     * parlaklık gürültüsünü kenarları koruyarak azaltır (yönlendirmeli filtre), renk gürültüsünü siler, hafif keskinleştirir.
     */
    static void finishNight(int[] px, int w, int h, float expoGain) {
        finishNight(px, w, h, expoGain, 2.0f, 0.25f, 2, 4, true);
    }

    static void finishNight(int[] px, int w, int h, float expoGain, float epsK, float sharpen, int chromaR, int R, boolean autoLift) {
        int n = w * h;
        // 1) Parlaklık: ışık oranını gama uzayına çevir; çok karanlıksa medyanı biraz kaldır
        float g = (float) Math.pow(Math.max(1f, Math.min(4f, expoGain)), 1 / 2.2);
        int[] lh = new int[256];
        for (int i = 0; i < n; i += 7) lh[luma(px[i])]++;
        int med = Math.max(1, percentile(lh, (n + 6) / 7, 0.5));
        if (autoLift && med * g < 95) g *= Math.min(1.5f, 95f / (med * g));
        if (g > 1.01f) {
            int[] lut = new int[256];
            for (int v = 0; v < 256; v++) {
                float x = v * g;
                lut[v] = clamp(Math.round(x <= 200 ? x : 200 + 55 * (float) Math.tanh((x - 200) / 55)), 0, 255);
            }
            for (int i = 0; i < n; i++) {
                int c = px[i];
                px[i] = 0xFF000000 | (lut[(c >> 16) & 255] << 16) | (lut[(c >> 8) & 255] << 8) | lut[c & 255];
            }
        }
        // 2) Gürültü azaltma + keskinleştirme (bellek için şeritler hâlinde)
        float sigma = estimateNoise(px, w, h);
        if (sigma < 0.6f || epsK <= 0) return;
        float eps = (epsK * sigma) * (epsK * sigma);
        int RC = Math.max(0, chromaR), M = 2 * R + Math.max(RC, 1) + 2, STRIP = 128;
        for (int y0 = 0; y0 < h; y0 += STRIP) {
            int y1 = Math.min(h, y0 + STRIP), s0 = Math.max(0, y0 - M), s1 = Math.min(h, y1 + M), SH = s1 - s0, SN = w * SH;
            float[] Y = new float[SN], Cb = new float[SN], Cr = new float[SN];
            for (int i = 0; i < SN; i++) {
                int c = px[s0 * w + i];
                float r = (c >> 16) & 255, gg = (c >> 8) & 255, b = c & 255;
                float yy = 0.299f * r + 0.587f * gg + 0.114f * b;
                Y[i] = yy;
                Cb[i] = b - yy;
                Cr[i] = r - yy;
            }
            float[] mI = box(Y, w, SH, R), YY = new float[SN];
            for (int i = 0; i < SN; i++) YY[i] = Y[i] * Y[i];
            float[] mII = box(YY, w, SH, R);
            YY = null;
            float[] A = new float[SN], B = new float[SN];
            for (int i = 0; i < SN; i++) {
                float var = Math.max(0, mII[i] - mI[i] * mI[i]);
                float a = var / (var + eps);
                A[i] = a;
                B[i] = mI[i] * (1 - a);
            }
            mI = mII = null;
            float[] mA = box(A, w, SH, R), mB = box(B, w, SH, R);
            A = B = null;
            float[] Q = new float[SN];
            for (int i = 0; i < SN; i++) Q[i] = mA[i] * Y[i] + mB[i];
            mA = mB = null;
            float[] Qb = sharpen > 0 ? box(Q, w, SH, 1) : Q;
            float[] cb = RC > 0 ? box(Cb, w, SH, RC) : Cb, cr = RC > 0 ? box(Cr, w, SH, RC) : Cr;
            for (int yy = y0; yy < y1; yy++) {
                int si = (yy - s0) * w, di = yy * w;
                for (int x = 0; x < w; x++, si++, di++) {
                    float yo = Q[si] + sharpen * (Q[si] - Qb[si]);
                    float r = yo + cr[si], b = yo + cb[si];
                    float gg = (yo - 0.299f * r - 0.114f * b) / 0.587f;
                    px[di] = 0xFF000000 | (clamp(Math.round(r), 0, 255) << 16) | (clamp(Math.round(gg), 0, 255) << 8) | clamp(Math.round(b), 0, 255);
                }
            }
        }
    }

    /** Kutu ortalaması (kenarlarda pencere kırpılır), integral görüntüyle */
    static float[] box(float[] src, int w, int h, int r) {
        int W = w + 1;
        double[] I = new double[W * (h + 1)];
        for (int y = 0; y < h; y++) {
            double s = 0;
            int row = y * w, ir = (y + 1) * W, pr = y * W;
            for (int x = 0; x < w; x++) {
                s += src[row + x];
                I[ir + x + 1] = I[pr + x + 1] + s;
            }
        }
        float[] o = new float[w * h];
        for (int y = 0; y < h; y++) {
            int ya = Math.max(0, y - r), yb = Math.min(h, y + r + 1);
            for (int x = 0; x < w; x++) {
                int xa = Math.max(0, x - r), xb = Math.min(w, x + r + 1);
                double sum = I[yb * W + xb] - I[ya * W + xb] - I[yb * W + xa] + I[ya * W + xa];
                o[y * w + x] = (float) (sum / ((xb - xa) * (yb - ya)));
            }
        }
        return o;
    }

    // ---------------- PDF (JPEG sayfaları doğrudan gömülü) ----------------
    static byte[] makePdf(List<byte[]> jpegs, List<int[]> sizes) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int N = jpegs.size(), total = 3 + 3 * N;
        long[] offs = new long[total];
        w(o, "%PDF-1.4\n%âãÏÓ\n");
        offs[1] = o.size();
        w(o, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        StringBuilder kids = new StringBuilder();
        for (int k = 0; k < N; k++) kids.append(3 + 3 * k).append(" 0 R ");
        offs[2] = o.size();
        w(o, "2 0 obj\n<< /Type /Pages /Kids [" + kids.toString().trim() + "] /Count " + N + " >>\nendobj\n");
        for (int k = 0; k < N; k++) {
            int pw = sizes.get(k)[0], ph = sizes.get(k)[1];
            double sc = 842.0 / Math.max(pw, ph);
            String W = String.format(Locale.US, "%.2f", pw * sc), H = String.format(Locale.US, "%.2f", ph * sc);
            offs[3 + 3 * k] = o.size();
            w(o, (3 + 3 * k) + " 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + W + " " + H + "] /Resources << /XObject << /Im0 " + (5 + 3 * k) + " 0 R >> >> /Contents " + (4 + 3 * k) + " 0 R >>\nendobj\n");
            String cs = "q " + W + " 0 0 " + H + " 0 0 cm /Im0 Do Q";
            offs[4 + 3 * k] = o.size();
            w(o, (4 + 3 * k) + " 0 obj\n<< /Length " + cs.length() + " >>\nstream\n" + cs + "\nendstream\nendobj\n");
            byte[] j = jpegs.get(k);
            offs[5 + 3 * k] = o.size();
            w(o, (5 + 3 * k) + " 0 obj\n<< /Type /XObject /Subtype /Image /Width " + pw + " /Height " + ph + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length " + j.length + " >>\nstream\n");
            o.write(j, 0, j.length);
            w(o, "\nendstream\nendobj\n");
        }
        long xref = o.size();
        StringBuilder x = new StringBuilder("xref\n0 " + total + "\n0000000000 65535 f \n");
        for (int i = 1; i < total; i++) x.append(String.format(Locale.US, "%010d 00000 n \n", offs[i]));
        x.append("trailer\n<< /Size ").append(total).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        w(o, x.toString());
        return o.toByteArray();
    }

    private static void w(ByteArrayOutputStream o, String s) {
        byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
        o.write(b, 0, b.length);
    }
}
