package com.spectrum.app.core;

/**
 * 一帧分析结果。由 provider 线程填、渲染线程读，靠 3 个缓冲轮转 + volatile 发布
 * 做到无锁无分配（单生产者单消费者）。
 */
public final class SpectrumFrame {

    /** 每带的原始电平（dBFS，未平滑、未归一化）。 */
    public final float[] bandDb;

    public long seq;
    /** 音源模式：这一帧是"全 0"（没放歌，或媒体音量为 0）。 */
    public boolean silent;
    public float bassDb;
    public float midDb;
    public float trebleDb;
    public float rmsDb;      // 时域 RMS（VU 表用）
    public int bands;

    /** 时域波形（归一化 −1..1，等距采样），用于「显示 → 声波」叠加。 */
    public final float[] wave;
    public int waveCount;

    public SpectrumFrame(int bands, int wavePoints) {
        this.bands = bands;
        this.bandDb = new float[bands];
        this.wave = new float[wavePoints];
    }
}
