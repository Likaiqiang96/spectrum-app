package com.spectrum.app.core;

import java.util.concurrent.locks.LockSupport;

/**
 * 验证辅助：**不接任何音频硬件**，直接合成一串"像音乐一样"的频带 dB 往下发。
 *
 * 用途：验证渲染层的显示映射（自动量程 / 动态范围 / 配色 / 峰值）——
 * 不需要放任何声音，也不会受 8-bit 量化底噪干扰，所以能干净地看出
 * "每个频段都接近饱和" 这类映射问题到底是映射写错了还是信号本身如此。
 *
 * 只有 MainActivity 收到 {@code --ez fakespectrum true} 时才会用。
 */
public final class FakeProvider extends SpectrumProvider {

    public static final int BANDS = PcmProvider.BANDS;   // 与真实通路一致，验证截图的密度才对

    private volatile boolean running;
    private Thread thread;

    public FakeProvider() {
        super(BANDS);
    }

    @Override
    public String label() {
        return "假频谱（验证映射用）";
    }

    @Override
    public int bands() {
        return BANDS;
    }

    @Override
    public int sampleRate() {
        return 48000;
    }

    /** 缩放上限（Hz）：假频谱要跟着「缩放」菜单走，否则会误以为缩放没生效。 */
    private volatile float fMaxHz = 16000f;

    @Override
    public void setFMax(float fMax) {
        if (fMax > 0f) fMaxHz = fMax;
    }

    @Override
    public void setAxisMode(boolean logAxis) {
        // 固定形状，不随坐标变化
    }

    @Override
    public boolean start() {
        running = true;
        thread = new Thread(new Runnable() {
            @Override public void run() {
                loop();
            }
        }, "spectrum-fake");
        thread.start();
        return true;
    }

    @Override
    public void stop() {
        running = false;
        Thread t = thread;
        thread = null;
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

    private void loop() {
        long t0 = System.nanoTime();
        while (running) {
            double sec = (System.nanoTime() - t0) / 1e9;
            SpectrumFrame f = nextFrame();
            // 瞬态：每 0.9 秒在一个轮换的频带上打一个 150 ms 的方波脉冲，
            // 用来检验攻击/释放速度，以及峰值点"像雨滴一样匀速下落"。
            double cycle = sec % 0.9;
            int spikeBand = (int) ((sec / 0.9) % 4) * (BANDS / 4) + BANDS / 8;   // 四条等比分布的带轮换
            for (int k = 0; k < BANDS; k++) {
                // 归一化到"当前缩放上限"：fMax 越小，同样的形状就被压缩到左侧，右侧变成尾巴
                double x = (k / (double) (BANDS - 1)) * (fMaxHz / 16000.0);
                // 典型音乐谱：低频最高，往上按 -4.5 dB/oct 滚降，再叠一点起伏
                double db = -18.0 - 42.0 * Math.pow(x, 0.62);
                db += 3.5 * Math.sin(sec * 2.2 + x * 9.0);    // 缓慢起伏
                db += 6.0 * Math.exp(-Math.pow((x - 0.34) / 0.05, 2));   // 一个中频峰
                if (k == spikeBand && cycle < 0.15) {
                    db += 34.0;
                }
                f.bandDb[k] = (float) db;
            }
            f.bands = BANDS;
            for (int k = 0; k < f.wave.length; k++) {
                f.wave[k] = (float) (0.55 * Math.sin(2 * Math.PI * (3.0 * sec + k / (double) f.wave.length * 20)));
            }
            f.waveCount = f.wave.length;
            f.bassDb = f.bandDb[0];
            f.midDb = f.bandDb[BANDS / 2];
            f.trebleDb = f.bandDb[BANDS - 1];
            f.rmsDb = -14f + (float) (3.0 * Math.sin(sec * 2.2));
            f.silent = false;
            publish(f);

            long next = System.nanoTime() + 50_000_000L;      // 20 fps，和音源路一致
            long sleep = next - System.nanoTime();
            if (sleep > 0) LockSupport.parkNanos(sleep);
        }
    }
}
