package com.durmuseminalbayrak.uyku;

/**
 * Sonar + ses ölçeri birleştirir; her saniyenin sonucunu 30 sn'lik dönemlere toplar,
 * duraklama/hareket/gürültü olaylarını üretir. Android bağımlılığı yok.
 */
public final class NightRecorder implements Sonar.Listener, SoundMeter.NoiseSink {
    public interface Out {
        void epoch(Night.Epoch e);
        void event(Night.Event v);
    }

    public final Sonar sonar;
    public final SoundMeter sound;
    private final Out out;

    private int secInEpoch, epochIdx;
    private final float[] rates = new float[40], amps = new float[40];
    private int nRates, nAmps;
    private double qSum;
    private int validSecs, moveSecs;
    private float moveMax;
    private int pausesEnded;
    private float pauseSecs;
    private boolean flagReset;
    private boolean inMove;
    private double moveStart;
    private float moveEvMax;

    // canlı ekran için
    public volatile int totalPauses, totalMoves, totalSnores, epochs;
    public volatile float lastEpochRate = Float.NaN;

    public NightRecorder(Out out) {
        this.out = out;
        sonar = new Sonar(this);
        sound = new SoundMeter(this);
    }

    /** 1024 örneklik mikrofon bloğu. */
    public void process(float[] x) {
        sound.process(x);
        sonar.process(x);
    }

    /** Ses akışında örnek kaybı fark edildi. */
    public void discontinuity() { sonar.reset(); }

    @Override
    public void second(Sonar s) {
        double t = s.timeSec();
        if (s.valid) {
            validSecs++;
            if (nRates < rates.length) rates[nRates++] = s.rate;
        }
        qSum += s.quality;
        if (s.moving) {
            moveSecs++;
            moveMax = Math.max(moveMax, s.moveIntensity);
        }
        float r = s.ratio;
        if (r == r && s.valid && !s.moving && nAmps < amps.length) amps[nAmps++] = r;
        if (s.disturbed) flagReset = true;
        if (s.moving) {
            if (!inMove) {
                inMove = true;
                moveStart = t - 1;
                moveEvMax = 0;
            }
            moveEvMax = Math.max(moveEvMax, s.moveIntensity);
        } else if (inMove) {
            inMove = false;
            totalMoves++;
            out.event(new Night.Event(Night.Event.MOVE, moveStart, t - 1 - moveStart, moveEvMax));
        }
        if (++secInEpoch >= Night.EPOCH_SEC) closeEpoch();
    }

    @Override
    public void pause(double startSec, double durSec, float depth) {
        out.event(new Night.Event(Night.Event.PAUSE, startSec, durSec, depth));
        pausesEnded++;
        pauseSecs += (float) durSec;
        totalPauses++;
    }

    @Override
    public void noise(double tSec, float levelDb) {
        out.event(new Night.Event(Night.Event.NOISE, tSec, 0, levelDb));
    }

    private void closeEpoch() {
        Night.Epoch e = new Night.Epoch();
        e.idx = epochIdx++;
        e.rate = nRates >= 5 ? Sonar.median(rates, nRates) : Float.NaN;
        e.quality = (float) (qSum / secInEpoch);
        e.valid = validSecs / (float) secInEpoch;
        e.move = moveSecs;
        e.moveMax = moveMax;
        e.amp = nAmps >= 5 ? Sonar.median(amps, nAmps) : Float.NaN;
        float[] bs = sonar.breathStats();
        e.cv = bs != null && bs[0] >= 4 ? bs[2] : Float.NaN;
        e.pauses = pausesEnded;
        e.pauseSec = pauseSecs;
        float[] snd = sound.takeEpoch();
        e.snores = (int) snd[0];
        e.snoresReg = (int) snd[1];
        e.noises = (int) snd[2];
        e.level = snd[3];
        e.levelMax = snd[4];
        e.flags = flagReset ? 1 : 0;
        totalSnores += e.snores;
        lastEpochRate = e.rate;
        epochs = epochIdx;
        out.epoch(e);
        secInEpoch = 0;
        nRates = nAmps = 0;
        qSum = 0;
        validSecs = moveSecs = 0;
        moveMax = 0;
        pausesEnded = 0;
        pauseSecs = 0;
        flagReset = false;
    }
}
