package com.spectrum.app.core;

import android.os.Process;

import java.util.concurrent.locks.LockSupport;

/**
 * PCM 类音频来源的公共骨架：环形缓冲 + 独立分析线程。
 * 子类只负责"把 PCM 塞进 ring"（麦克风从 AudioRecord 读，自检自己生成）。
 *
 * 线程安排（设计文档 §5）：
 *   采集线程（URGENT_AUDIO）→ FloatRing → 分析线程（DISPLAY）→ SpectrumFrame
 */
public abstract class PcmProvider extends SpectrumProvider {

    public static final int SAMPLE_RATE = 48000;
    public static final int FFT_SIZE = 2048;
    public static final int HOP = 1024;              // 50% 重叠 → 46.9 帧/s
    public static final int BANDS = 144;     // 柱数：96 → 144（用户要求谱线再密一些）
    public static final float F_MIN = 60f;
    public static final float F_MAX_DEFAULT = 16000f;

    private final FloatRing ring = new FloatRing(32768);
    private final Analyzer analyzer =
            new Analyzer(SAMPLE_RATE, FFT_SIZE, HOP, BANDS, F_MIN, F_MAX_DEFAULT);

    private volatile boolean running;
    private Thread anaThread;

    protected PcmProvider() {
        super(BANDS);
    }

    protected final FloatRing ring() {
        return ring;
    }

    protected final boolean isRunning() {
        return running;
    }

    /** 子类实现：开采集并起采集线程；不可用返回 false。 */
    protected abstract boolean startCapture();

    /** 子类实现：停采集线程。 */
    protected abstract void stopCapture();

    @Override
    public final boolean start() {
        if (!startCapture()) return false;
        running = true;
        anaThread = new Thread(new Runnable() {
            @Override public void run() {
                analysisLoop();
            }
        }, "spectrum-analyze");
        anaThread.start();
        return true;
    }

    @Override
    public final void stop() {
        running = false;
        stopCapture();
        Thread t = anaThread;
        anaThread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(300);
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void release() {
        stop();
    }

    @Override
    public final int bands() {
        return BANDS;
    }

    @Override
    public final int sampleRate() {
        return SAMPLE_RATE;
    }

    @Override
    public final void setFMax(float fMax) {
        analyzer.requestFMax(fMax);
    }

    @Override
    public final void setAxisMode(boolean logAxis) {
        analyzer.requestAxis(logAxis);
    }

    private void analysisLoop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
        } catch (Throwable ignore) {
        }
        final float[] hop = new float[HOP];
        while (running) {
            int off = 0;
            while (off < HOP && running) {
                int got = ring.read(hop, off, HOP - off);
                if (got <= 0) {
                    LockSupport.parkNanos(1_000_000L);      // 1 ms 轮询，省得用锁
                } else {
                    off += got;
                }
            }
            if (!running) break;
            if (analyzer.feed(hop, 0, HOP)) {
                SpectrumFrame f = nextFrame();
                analyzer.fill(f);
                publish(f);
            }
        }
    }
}
