package com.spectrum.app.core;

/**
 * 分析器：把连续的 PCM 帧变成 SpectrumFrame 的频带 dB。
 *
 * 窗 2048 / hop 1024（50% 重叠）/ Hann / 自研 FFT / 对数分带（BandMapper）。
 * 全部数组预分配，feed() 里零 new —— 它在采集后的分析线程上跑。
 */
public final class Analyzer {

    private final int sampleRate;
    private final int fftSize;
    private final int hop;
    private final int bands;
    private final Fft fft;
    private final float[] hann;
    private final float[] win;
    private final float[] work;
    private final float[] mag;
    private final float[] bandDb;
    private final float[] metrics = new float[4];   // bass, mid, treble, rms

    private BandMapper mapper;
    private volatile float pendingFMax = -1f;
    private volatile int pendingAxis = -1;      // 1 = 对数, 0 = 线性, -1 = 无变化
    private boolean logAxis = true;
    private float fMin;
    private float fMax;
    private int filled;

    public Analyzer(int sampleRate, int fftSize, int hop, int bands, float fMin, float fMax) {
        this.sampleRate = sampleRate;
        this.fftSize = fftSize;
        this.hop = hop;
        this.bands = bands;
        this.fMin = fMin;
        this.fMax = fMax;
        this.fft = new Fft(fftSize);
        this.hann = Fft.hann(fftSize);
        this.win = new float[fftSize];
        this.work = new float[fftSize];
        this.mag = new float[fftSize / 2 + 1];
        this.bandDb = new float[bands];
        this.mapper = new BandMapper(fftSize, sampleRate, bands, fMin, fMax);
    }

    public int bands() {
        return bands;
    }

    public float fMax() {
        return fMax;
    }

    /** 由 UI 线程调用；真正的重建发生在分析线程里（避免锁）。 */
    public void requestFMax(float newFMax) {
        pendingFMax = newFMax;
    }

    public void requestAxis(boolean logAxis) {
        pendingAxis = logAxis ? 1 : 0;
    }

    /**
     * 追加 len 帧 PCM（−1..1）。凑满一个 hop 就分析一次。
     *
     * @return true 表示这一批数据产出了一帧频谱，调用方可以 fill()
     */
    public boolean feed(float[] in, int off, int len) {
        float want = pendingFMax;
        int axis = pendingAxis;
        boolean axisChanged = axis >= 0 && (axis == 1) != logAxis;
        if (axis >= 0) logAxis = axis == 1;
        if ((want > 0 && want != fMax) || axisChanged) {
            if (want > 0) fMax = want;
            pendingFMax = -1f;
            pendingAxis = -1;
            mapper = new BandMapper(fftSize, sampleRate, bands, fMin, fMax, !logAxis);
        }

        int p = off;
        int remaining = len;
        boolean produced = false;
        while (remaining > 0) {
            int take = Math.min(hop - filled, remaining);
            System.arraycopy(in, p, win, fftSize - hop + filled, take);
            filled += take;
            p += take;
            remaining -= take;
            if (filled == hop) {
                analyze();
                filled = 0;
                produced = true;
            }
        }
        return produced;
    }

    private void analyze() {
        for (int i = 0; i < fftSize; i++) {
            work[i] = win[i] * hann[i];
        }
        fft.magnitude(work, mag);
        mapper.compute(mag, bandDb);

        // 时域 RMS 用的是本轮新进来的 hop（win 的尾部）
        int base = fftSize - hop;
        float acc = 0f;
        for (int i = 0; i < hop; i++) {
            float v = win[base + i];
            acc += v * v;
        }
        float rms = (float) Math.sqrt(acc / hop);
        metrics[3] = 20f * (float) Math.log10(rms + 1e-9f);
        metrics[0] = BandMapper.bandEnergyDb(mag, fftSize, sampleRate, 60f, 250f);
        metrics[1] = BandMapper.bandEnergyDb(mag, fftSize, sampleRate, 250f, 4000f);
        metrics[2] = BandMapper.bandEnergyDb(mag, fftSize, sampleRate, 4000f, 16000f);

        // 时域波形（示波器用）：本轮 hop 整段（1024 点），并**循环移位对齐到上升过零点**
        // —— 不触发的话每帧窗口相位随机，看起来就是"卡顿/乱跳"。
        int wp = Math.min(SpectrumProvider.WAVE_POINTS, hop);
        int trig = 0;
        for (int i = 1; i < hop / 2; i++) {
            if (win[base + i - 1] < 0f && win[base + i] >= 0f) { trig = i; break; }
        }
        for (int k = 0; k < wp; k++) {
            int idx = (trig + k) % hop;
            wave[k] = win[base + idx];
        }

        // 给下一轮腾位置：左移 hop
        System.arraycopy(win, hop, win, 0, fftSize - hop);
    }

    /** 把最近一次分析结果填进 frame（由分析线程调用）。 */
    private final float[] wave = new float[SpectrumProvider.WAVE_POINTS];

    public void fill(SpectrumFrame f) {
        System.arraycopy(bandDb, 0, f.bandDb, 0, bands);
        System.arraycopy(wave, 0, f.wave, 0, Math.min(wave.length, f.wave.length));
        f.waveCount = wave.length;
        f.bands = bands;
        f.bassDb = metrics[0];
        f.midDb = metrics[1];
        f.trebleDb = metrics[2];
        f.rmsDb = metrics[3];
    }
}
