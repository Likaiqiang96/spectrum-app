package com.spectrum.app.core;

import java.util.concurrent.locks.LockSupport;

/**
 * 自检信号源：不碰任何音频硬件，自己合成 PCM 走同一条分析链路。
 *
 * 用途（设计文档 R10）：模拟器 `-no-audio` 时麦克风只有静音，
 * 有了它就能在没有任何真实声音的情况下验收渲染、配色和分带是否正确。
 *
 * 信号是一段确定性的"五级阶梯"：60 / 250 / 1k / 4k / 10k Hz，幅度依次递减。
 * 在对数频率轴 + +3 dB/oct 倾斜补偿之后，屏幕上应该是一条**自左向右缓降的阶梯**，
 * 每一级的频率位置可以按 f_k = 60·(16000/60)^(k/48) 反推核对。
 */
public final class SelfTestProvider extends PcmProvider {

    private static final double[] FREQ = {60, 250, 1000, 4000, 10000};
    private static final double[] AMP = {0.30, 0.25, 0.20, 0.15, 0.10};

    private Thread genThread;

    @Override
    public String label() {
        return "自检信号（60/250/1k/4k/10k）";
    }

    @Override
    protected boolean startCapture() {
        genThread = new Thread(new Runnable() {
            @Override public void run() {
                generateLoop();
            }
        }, "spectrum-selftest");
        genThread.start();
        return true;
    }

    @Override
    protected void stopCapture() {
        Thread t = genThread;
        genThread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(300);
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void generateLoop() {
        final float[] buf = new float[HOP];
        final double[] phase = new double[FREQ.length];
        final double[] step = new double[FREQ.length];
        for (int i = 0; i < FREQ.length; i++) {
            step[i] = 2.0 * Math.PI * FREQ[i] / SAMPLE_RATE;
        }
        final long frameNanos = (long) (1e9 * HOP / SAMPLE_RATE);   // 21.33 ms
        long next = System.nanoTime();
        while (isRunning()) {
            for (int i = 0; i < HOP; i++) {
                double s = 0;
                for (int j = 0; j < FREQ.length; j++) {
                    s += AMP[j] * Math.sin(phase[j]);
                    phase[j] += step[j];
                }
                buf[i] = (float) s;
            }
            ring().write(buf, HOP);

            next += frameNanos;
            long sleep = next - System.nanoTime();
            if (sleep > 0) {
                LockSupport.parkNanos(sleep);
            } else {
                next = System.nanoTime();
            }
        }
    }
}
