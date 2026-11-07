package com.spectrum.app.core;

/**
 * 对数频带映射。规则和设计文档 §6.3 完全一致（三条一起用，都是实测出来的）：
 *
 *   1. bin 中心唯一归属：bin b 属于带 k ⟺ ceil(f_k/binHz) ≤ b < ceil(f_{k+1}/binHz)
 *   2. 带内取 max（不是能量求和）：纯音柱高与带宽无关
 *   3. 带内一个 bin 中心都没有（低频端带比一个 bin 还窄）时，
 *      在带的几何中心 √(f_k·f_{k+1}) 处对幅度谱线性插值
 *
 * 另外带一个 +3 dB/oct 的高频倾斜补偿：音乐/粉噪的谱大致 −3 dB/oct，
 * 不补的话高频会是一条贴地的线（参考图右端那条粉色尾巴就是这个处理）。
 */
public final class BandMapper {

    private final int fftSize;
    private final int sampleRate;
    private final int bands;
    private final float[] fLow;
    private final float[] fHigh;
    private final int[] lo;
    private final int[] hi;
    private final int[] interpIdx;      // -1 = 带内有 bin，不需要插值
    private final float[] interpT;
    private final float[] centerHz;
    private final float[] tilt;

    public BandMapper(int fftSize, int sampleRate, int bands, float fMin, float fMax) {
        this(fftSize, sampleRate, bands, fMin, fMax, false);
    }

    /**
     * @param linear true = 横坐标线性等分（每根柱子带宽相同）；false = 对数等分（每倍频程同样多柱子）。
     *               线性档下低频会被压缩到最左边，是"专业分析仪"的另一种看法，用户可切。
     */
    public BandMapper(int fftSize, int sampleRate, int bands, float fMin, float fMax, boolean linear) {
        this.fftSize = fftSize;
        this.sampleRate = sampleRate;
        this.bands = bands;
        this.fLow = new float[bands];
        this.fHigh = new float[bands];
        this.lo = new int[bands];
        this.hi = new int[bands];
        this.interpIdx = new int[bands];
        this.interpT = new float[bands];
        this.centerHz = new float[bands];
        this.tilt = new float[bands];

        float binHz = sampleRate / (float) fftSize;
        int half = fftSize / 2;
        for (int k = 0; k < bands; k++) {
            float f0;
            float f1;
            if (linear) {
                // 线性档从 **0 Hz** 开始等分（对数档不可能从 0 开始：log(0) = −∞）
                f0 = fMax * k / bands;
                f1 = fMax * (k + 1) / bands;
            } else {
                f0 = (float) (fMin * Math.pow(fMax / (double) fMin, k / (double) bands));
                f1 = (float) (fMin * Math.pow(fMax / (double) fMin, (k + 1) / (double) bands));
            }
            fLow[k] = f0;
            fHigh[k] = f1;
            // 0 Hz 那根柱子的几何中心是 0，倾斜补偿会算出 -inf，这里兜到 20 Hz
            centerHz[k] = (float) Math.max(20.0, Math.sqrt((double) f0 * f1));

            int bLo = Math.max(1, (int) Math.ceil(f0 / binHz));
            int bHi = Math.min(half + 1, Math.max(bLo, (int) Math.ceil(f1 / binHz)));
            lo[k] = bLo;
            hi[k] = bHi;
            if (bHi <= bLo) {
                float fb = centerHz[k] / binHz;
                int i = Math.max(1, Math.min(half - 1, (int) Math.floor(fb)));
                interpIdx[k] = i;
                float t = fb - i;
                interpT[k] = t < 0f ? 0f : (t > 1f ? 1f : t);
            } else {
                interpIdx[k] = -1;
                interpT[k] = 0f;
            }
            // +3 dB/oct，但**封顶 +15 dB**：16kHz 处原本要抬 24 dB，
            // 那样任何残余底噪都会被高频放大成"越往高频越高"的假谱（用户反馈）。
            tilt[k] = Math.min(5.62f, (float) Math.pow(centerHz[k] / 1000.0, 0.5));
        }
    }

    public int bands() {
        return bands;
    }

    public float fLow(int k) {
        return fLow[k];
    }

    public float fHigh(int k) {
        return fHigh[k];
    }

    public float centerHz(int k) {
        return centerHz[k];
    }

    /** @param mag 长度 fftSize/2+1 的幅度谱；@param outDb 长度 bands 的输出（dBFS） */
    public void compute(float[] mag, float[] outDb) {
        for (int k = 0; k < bands; k++) {
            float m;
            int ii = interpIdx[k];
            if (ii < 0) {
                m = 0f;
                int b = lo[k];
                int e = hi[k];
                for (; b < e; b++) {
                    float v = mag[b];
                    if (v > m) m = v;
                }
            } else {
                float t = interpT[k];
                m = mag[ii] * (1f - t) + mag[ii + 1] * t;
            }
            outDb[k] = 20f * (float) Math.log10(m * tilt[k] + 1e-9f);
        }
    }

    /** 把 [loHz, hiHz) 三段能量合成一个 dB（用于低音/中音/高音读数）。 */
    public static float bandEnergyDb(float[] mag, int fftSize, int sampleRate,
                                     float loHz, float hiHz) {
        float binHz = sampleRate / (float) fftSize;
        int half = fftSize / 2;
        int a = Math.max(1, (int) Math.floor(loHz / binHz));
        int b = Math.min(half, (int) Math.ceil(hiHz / binHz));
        float acc = 0f;
        for (int i = a; i <= b; i++) acc += mag[i] * mag[i];
        return 20f * (float) Math.log10(Math.sqrt(acc) + 1e-9f);
    }
}
