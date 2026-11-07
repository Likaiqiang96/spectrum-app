package com.spectrum.app.core;

/**
 * 基-2 迭代 FFT：预分配、零分配、float。
 * 与 tools/jvm/DspProbe.java 是同一份逻辑（那边和朴素 DFT 对拍过，最大误差 4.2e-08）。
 *
 * 幅度归一化：满幅 bin 中心正弦读数 = 1.0（Hann 相干增益 0.5）。
 */
public final class Fft {

    private final int n;
    private final int log2n;
    private final float[] re;
    private final float[] im;
    private final float[] cos;
    private final float[] sin;
    private final int[] rev;

    public Fft(int n) {
        if (Integer.bitCount(n) != 1) throw new IllegalArgumentException("n must be a power of 2");
        this.n = n;
        this.log2n = Integer.numberOfTrailingZeros(n);
        this.re = new float[n];
        this.im = new float[n];
        this.cos = new float[n / 2];
        this.sin = new float[n / 2];
        for (int i = 0; i < n / 2; i++) {
            double a = -2.0 * Math.PI * i / n;
            cos[i] = (float) Math.cos(a);
            sin[i] = (float) Math.sin(a);
        }
        this.rev = new int[n];
        for (int i = 0; i < n; i++) rev[i] = Integer.reverse(i) >>> (32 - log2n);
    }

    public int size() {
        return n;
    }

    /**
     * @param x   长度 n 的加窗实数序列
     * @param mag 长度 n/2+1 的幅度谱输出
     */
    public void magnitude(float[] x, float[] mag) {
        final int n = this.n;
        for (int i = 0; i < n; i++) {
            re[i] = x[rev[i]];
            im[i] = 0f;
        }
        for (int len = 2; len <= n; len <<= 1) {
            int half = len >> 1;
            int step = n / len;
            for (int i = 0; i < n; i += len) {
                for (int j = 0, k = 0; j < half; j++, k += step) {
                    int a = i + j;
                    int b = a + half;
                    float tr = re[b] * cos[k] - im[b] * sin[k];
                    float ti = re[b] * sin[k] + im[b] * cos[k];
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                }
            }
        }
        final float s = 2f / (n * WINDOW_GAIN);
        final float sdc = 1f / (n * WINDOW_GAIN);
        mag[0] = Math.abs(re[0]) * sdc;
        for (int i = 1; i <= n / 2; i++) {
            mag[i] = (float) Math.sqrt(re[i] * re[i] + im[i] * im[i]) * s;
        }
    }

    /** Hann 窗的相干增益（周期型）。 */
    public static final float WINDOW_GAIN = 0.5f;

    /** 周期型 Hann 窗表：w[i] = 0.5 - 0.5·cos(2πi/n)。 */
    public static float[] hann(int n) {
        float[] w = new float[n];
        for (int i = 0; i < n; i++) {
            w[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / n));
        }
        return w;
    }
}
