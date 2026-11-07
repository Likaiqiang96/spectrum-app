package com.spectrum.app.core;

/**
 * 音频来源的统一接口。两条路（麦克风 / 音源）都实现它，
 * 各自在自己的线程里分析，然后通过 {@link Sink} 把整帧交出去。
 */
public abstract class SpectrumProvider {

    /** 回调发生在 provider 自己的线程上：只允许"存下引用"，不要在里面画界面。 */
    public interface Sink {
        void onSpectrum(SpectrumFrame frame);
    }

    /** 示波器波形的采样点数（所有 provider 统一）：1024 点 ≈ 20ms @48k。 */
    public static final int WAVE_POINTS = 1024;

    private final SpectrumFrame[] pool;
    private int poolIdx;
    private long seq;
    private Sink sink;

    protected SpectrumProvider(int bands) {
        this.pool = new SpectrumFrame[3];       // 3 个缓冲，渲染线程最多握 1 帧，绝对够
        for (int i = 0; i < pool.length; i++) pool[i] = new SpectrumFrame(bands, WAVE_POINTS);
    }

    public void setSink(Sink sink) {
        this.sink = sink;
    }

    /** 取一个写目标（调用方填完必须 publish）。 */
    protected final SpectrumFrame nextFrame() {
        SpectrumFrame f = pool[poolIdx];
        poolIdx = (poolIdx + 1) % pool.length;
        return f;
    }

    protected final void publish(SpectrumFrame f) {
        Sink s = sink;
        if (s == null) return;
        f.seq = ++seq;
        s.onSpectrum(f);
    }

    public abstract boolean start();

    public abstract void stop();

    public abstract void release();

    /** 给 UI 显示"当前真正生效的后端"。 */
    public abstract String label();

    public abstract int bands();

    public abstract int sampleRate();

    /** 频谱显示上限（菜单「缩放」）。 */
    public abstract void setFMax(float fMax);

    /** 横坐标是线性还是对数（菜单「坐标」）。 */
    public abstract void setAxisMode(boolean logAxis);

    /** 频谱下限频率（Hz），画横坐标刻度用。 */
    public float fMin() {
        return 60f;
    }
}
