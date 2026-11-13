package com.durmuseminalbayrak.uyku;

/**
 * Sonar + ses ölçeri birleştirir; her saniyenin sonucunu 30 sn'lik dönemlere toplar,
 * duraklama/hareket/gürültü olaylarını üretir. Android bağımlılığı yok.
 */
public final class NightRecorder implements Sonar.Listener, SoundMeter.NoiseSink {
    public interface Out {
        void epoch(Night.Epoch e);
        void event(Night.Event v);
        /** Saniyelik tanı kaydı (isteğe bağlı). */
        default void sec(Night.Sec c) { }
    }

    public final Sonar sonar;
    public final SoundMeter sound;
    public final Accel accel;
    private final Out out;
    private double curT, lastSonarValidT = -1e9;
    private int accelSecs;

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

    // canlı ekran için (sonar + ivmeölçer birleşik)
    public volatile int totalPauses, totalMoves, totalSnores, epochs;
    public volatile float lastEpochRate = Float.NaN;
    public volatile float rate = Float.NaN, quality;
    public volatile boolean valid, moving, inPause;
    /** 0 = ölçüm yok, 1 = sonar, 2 = ivmeölçer (telefon göğüste/yatakta). */
    public volatile int activeSource;

    public NightRecorder(Out out) {
        this.out = out;
        sonar = new Sonar(this);
        sound = new SoundMeter(this);
        accel = new Accel(this::accelPause);
    }

    /** İvmeölçer örneği (sensör iş parçacığından). */
    public void addAccel(long tNs, float x, float y, float z) { accel.add(tNs, x, y, z); }

    private void accelPause(double startSec, double durSec, float depth) {
        // sonar nefesi ölçüyorsa duraklamayı o bildirir; ivmeölçerinki ancak sonar sustuysa sayılır
        if (curT - lastSonarValidT > 60) pause(startSec, durSec, depth);
    }

    /** Canlı ekranda gösterilecek dalga (o an kullanılan kaynaktan). */
    public float[] wave() { return activeSource == 2 ? accel.waveOut : sonar.waveOut; }

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
        curT = t;
        accel.second(t);
        boolean sv = s.valid, av = accel.valid;
        if (sv) lastSonarValidT = t;
        boolean useA = av && (!sv || accel.quality > s.quality + 0.05f);
        activeSource = useA ? 2 : sv ? 1 : 0;
        float r = useA ? accel.rate : sv ? s.rate : Float.NaN;
        boolean v = sv || av;
        boolean mv = s.moving || accel.moving;
        float mi = Math.max(s.moveIntensity, accel.moveIntensity);
        rate = r;
        quality = Math.max(s.quality, av ? accel.quality : 0f);
        valid = v;
        moving = mv;
        inPause = useA ? accel.inPause : s.inPause;
        if (v) {
            validSecs++;
            if (nRates < rates.length) rates[nRates++] = r;
        }
        if (useA) accelSecs++;
        qSum += quality;
        if (mv) {
            moveSecs++;
            moveMax = Math.max(moveMax, mi);
        }
        float ra = useA ? accel.ratio : s.ratio;
        if (ra == ra && v && !mv && nAmps < amps.length) amps[nAmps++] = ra;
        if (s.disturbed) flagReset = true;
        if (mv) {
            if (!inMove) {
                inMove = true;
                moveStart = t - 1;
                moveEvMax = 0;
            }
            moveEvMax = Math.max(moveEvMax, mi);
        } else if (inMove) {
            inMove = false;
            totalMoves++;
            out.event(new Night.Event(Night.Event.MOVE, moveStart, t - 1 - moveStart, moveEvMax));
        }
        Night.Sec c = new Night.Sec();
        c.t = (float) t;
        c.rate = r;
        c.src = activeSource;
        c.flags = (mv ? 1 : 0) | (inPause ? 2 : 0) | (sv ? 4 : 0) | (av ? 8 : 0) | (s.disturbed ? 16 : 0);
        c.q = quality;
        c.sq = s.quality;
        c.aq = accel.quality;
        c.echoDb = s.echoDb;
        c.noiseDb = s.noiseDb;
        c.snrDb = s.bandSnrDb;
        c.outNoiseDb = s.outNoiseDb;
        c.directDb = s.directDb;
        c.sMove = s.moveIntensity;
        c.aMove = accel.moveIntensity;
        c.ratio = s.ratio;
        c.distCm = s.distanceCm();
        c.accAmp = accel.ampRms;
        c.soundDb = sound.levelDb;
        out.sec(c);
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
        float[] bs = accelSecs * 2 > secInEpoch ? accel.breathStats() : sonar.breathStats();
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
        accelSecs = 0;
    }
}
