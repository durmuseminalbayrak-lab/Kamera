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
    static final int VERSION = 1;

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

    public long startMillis, endMillis;
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

    public static Night read(File f) throws IOException {
        Night n = new Night();
        n.file = f;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            if (in.readInt() != MAGIC) throw new IOException("Uyku dosyası değil");
            int ver = in.readInt();
            if (ver != VERSION) throw new IOException("Bilinmeyen sürüm " + ver);
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
}
