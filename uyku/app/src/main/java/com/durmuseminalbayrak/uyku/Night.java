package com.durmuseminalbayrak.uyku;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Bir gecenin 30 sn'lik dönemleri ve olayları; ekleme yapılabilen ikili dosya biçimi. */
public final class Night {
    public static final float EPOCH_SEC = 30f;
    static final int MAGIC = 0x55594B55; // "UYKU"
    static final int VERSION = 2;   // 2: saniyelik tanı kayıtları ('S')

    public static final class Epoch {
        public int idx;
        public float rate = Float.NaN;   // nefes/dk (geçerli saniyelerin medyanı)
        public float quality;            // ortalama periyodiklik 0..1
        public float valid;              // geçerli nefes ölçümü olan saniye oranı
        public float move;               // hareketli saniye sayısı
        public float moveMax;            // en yüksek hareket şiddeti
        public float amp = Float.NaN;    // nefes genliği / olağan genlik (medyan)
        public float cv = Float.NaN;     // soluk aralığı değişkenliği
        public int pauses;               // bu dönemde biten duraklama sayısı
        public float pauseSec;           // bu dönemde başlayan duraklamaların toplam süresi
        public int snores, snoresReg, noises;
        public float level = Float.NaN, levelMax = Float.NaN;
        public int flags;                // 1 = sonar sıfırlandı (kopukluk/rahatsızlık)
    }

    public static final class Event {
        public static final int PAUSE = 1, MOVE = 2, NOISE = 3;
        public int type;
        public float t, dur, val;

        public Event() {}

        public Event(int type, double t, double dur, double val) {
            this.type = type;
            this.t = (float) t;
            this.dur = (float) dur;
            this.val = (float) val;
        }
    }

    /** Saniyelik tanı kaydı (yalnız dışa aktarma için; ses yok, yalnız ölçüm sayıları). */
    public static final class Sec {
        public float t, rate = Float.NaN;
        public int src, flags;                 // flags: 1 hareket, 2 duraklama, 4 sonar geçerli, 8 ivme geçerli, 16 sıfırlama
        public float q, sq, aq;
        public float echoDb = Float.NaN, noiseDb = Float.NaN, snrDb = Float.NaN, outNoiseDb = Float.NaN, directDb = Float.NaN;
        public float sMove, aMove, ratio = Float.NaN, accAmp = Float.NaN, soundDb = Float.NaN;
        public int distCm = -1;
        static final int BYTES = 4 + 4 + 1 + 1 + 3 + 2 * 5 + 2 * 2 + 2 + 2 + 4 + 2;
    }

    public long startMillis, endMillis;
    public final List<Sec> secs = new ArrayList<>();
    public boolean complete;
    public final List<Epoch> epochs = new ArrayList<>();
    public final List<Event> events = new ArrayList<>();
    public File file;

    public double durationSec() {
        if (endMillis > startMillis) return (endMillis - startMillis) / 1000.0;
        return epochs.size() * EPOCH_SEC;
    }

    // ------------------------------------------------------------------ yazma

    public static final class Writer implements Closeable {
        private final DataOutputStream out;

        public Writer(File f, long startMillis) throws IOException {
            out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f), 4096));
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeLong(startMillis);
            out.writeFloat(EPOCH_SEC);
            out.flush();
        }

        public synchronized void epoch(Epoch e) throws IOException {
            out.writeByte('E');
            out.writeInt(e.idx);
            out.writeFloat(e.rate);
            out.writeFloat(e.quality);
            out.writeFloat(e.valid);
            out.writeFloat(e.move);
            out.writeFloat(e.moveMax);
            out.writeFloat(e.amp);
            out.writeFloat(e.cv);
            out.writeByte(Math.min(127, e.pauses));
            out.writeFloat(e.pauseSec);
            out.writeShort(Math.min(32767, e.snores));
            out.writeShort(Math.min(32767, e.snoresReg));
            out.writeShort(Math.min(32767, e.noises));
            out.writeFloat(e.level);
            out.writeFloat(e.levelMax);
            out.writeByte(e.flags);
            out.flush();   // gece yarıda kesilse bile buraya kadarı okunabilir
        }

        /** Saniyelik kayıt: arabelleğe yazılır, dönem kaydıyla birlikte diske iner. */
        public synchronized void sec(Sec c) throws IOException {
            out.writeByte('S');
            out.writeFloat(c.t);
            out.writeFloat(c.rate);
            out.writeByte(c.src);
            out.writeByte(c.flags);
            out.writeByte(q100(c.q));
            out.writeByte(q100(c.sq));
            out.writeByte(q100(c.aq));
            out.writeShort(d10(c.echoDb));
            out.writeShort(d10(c.noiseDb));
            out.writeShort(d10(c.snrDb));
            out.writeShort(d10(c.outNoiseDb));
            out.writeShort(d10(c.directDb));
            out.writeShort(d10(c.sMove));
            out.writeShort(d10(c.aMove));
            out.writeShort(c.ratio == c.ratio ? (int) Math.max(-32767, Math.min(32767, Math.round(c.ratio * 1000))) : -32768);
            out.writeShort(Math.max(-1, Math.min(32767, c.distCm)));
            out.writeFloat(c.accAmp);
            out.writeShort(d10(c.soundDb));
        }

        private static int q100(float v) { return v == v ? Math.max(0, Math.min(100, Math.round(v * 100))) : 255; }

        private static int d10(float v) {
            if (v != v) return -32768;
            return (int) Math.max(-32767, Math.min(32767, Math.round(v * 10)));
        }

        public synchronized void event(Event v) throws IOException {
            out.writeByte('V');
            out.writeByte(v.type);
            out.writeFloat(v.t);
            out.writeFloat(v.dur);
            out.writeFloat(v.val);
            out.flush();
        }

        public synchronized void end(long endMillis) throws IOException {
            out.writeByte('Z');
            out.writeLong(endMillis);
            out.flush();
        }

        @Override
        public synchronized void close() throws IOException { out.close(); }
    }

    // ------------------------------------------------------------------ okuma

    public static Night read(File f) throws IOException { return read(f, false); }

    /** withSecs=false: saniyelik kayıtlar atlanır (liste/rapor için hızlı). */
    public static Night read(File f, boolean withSecs) throws IOException {
        Night n = new Night();
        n.file = f;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            if (in.readInt() != MAGIC) throw new IOException("Uyku dosyası değil");
            int ver = in.readInt();
            if (ver < 1 || ver > VERSION) throw new IOException("Bilinmeyen sürüm " + ver);
            n.version = ver;
            n.startMillis = in.readLong();
            in.readFloat();
            try {
                while (true) {
                    int tag = in.readUnsignedByte();
                    if (tag == 'E') {
                        Epoch e = new Epoch();
                        e.idx = in.readInt();
                        e.rate = in.readFloat();
                        e.quality = in.readFloat();
                        e.valid = in.readFloat();
                        e.move = in.readFloat();
                        e.moveMax = in.readFloat();
                        e.amp = in.readFloat();
                        e.cv = in.readFloat();
                        e.pauses = in.readByte();
                        e.pauseSec = in.readFloat();
                        e.snores = in.readShort();
                        e.snoresReg = in.readShort();
                        e.noises = in.readShort();
                        e.level = in.readFloat();
                        e.levelMax = in.readFloat();
                        e.flags = in.readByte();
                        n.epochs.add(e);
                    } else if (tag == 'V') {
                        Event v = new Event();
                        v.type = in.readByte();
                        v.t = in.readFloat();
                        v.dur = in.readFloat();
                        v.val = in.readFloat();
                        n.events.add(v);
                    } else if (tag == 'S') {
                        if (!withSecs) {
                            in.readFully(SKIP, 0, Sec.BYTES);
                            n.secCount++;
                            continue;
                        }
                        Sec c = new Sec();
                        c.t = in.readFloat();
                        c.rate = in.readFloat();
                        c.src = in.readUnsignedByte();
                        c.flags = in.readUnsignedByte();
                        c.q = uq(in.readUnsignedByte());
                        c.sq = uq(in.readUnsignedByte());
                        c.aq = uq(in.readUnsignedByte());
                        c.echoDb = ud(in.readShort());
                        c.noiseDb = ud(in.readShort());
                        c.snrDb = ud(in.readShort());
                        c.outNoiseDb = ud(in.readShort());
                        c.directDb = ud(in.readShort());
                        c.sMove = ud(in.readShort());
                        c.aMove = ud(in.readShort());
                        short rq = in.readShort();
                        c.ratio = rq == -32768 ? Float.NaN : rq / 1000f;
                        c.distCm = in.readShort();
                        c.accAmp = in.readFloat();
                        c.soundDb = ud(in.readShort());
                        n.secs.add(c);
                        n.secCount++;
                    } else if (tag == 'Z') {
                        n.endMillis = in.readLong();
                        n.complete = true;
                    } else break;
                }
            } catch (EOFException ignored) {
                // yarıda kalan kayıt: o ana kadarki veri geçerli
            }
        }
        if (n.endMillis == 0) n.endMillis = n.startMillis + (long) (n.epochs.size() * EPOCH_SEC * 1000);
        return n;
    }

    public int version, secCount;
    private static final byte[] SKIP = new byte[64];

    private static float uq(int v) { return v == 255 ? Float.NaN : v / 100f; }

    private static float ud(short v) { return v == -32768 ? Float.NaN : v / 10f; }
}
