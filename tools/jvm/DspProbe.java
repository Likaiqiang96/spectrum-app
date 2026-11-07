import java.util.Arrays;
import java.util.Random;

/**
 * 频谱仪 DSP 探针 —— 写文档时用来"先验证再下结论"的小程序。
 *
 * 它不依赖任何 Android API，也不需要设备：直接用预置工具链的 JDK 编译运行。
 * 文档 docs/设计文档.md 的 §4.1 表格就是它的输出。
 *
 *   bash tools/jvm/run.sh
 *
 * 检查项：
 *   1) FFT 与朴素 DFT 对拍（同一归一化）
 *   2) 满幅 bin 中心正弦的读数 = 1.0
 *   3) Hann 窗最坏 scalloping
 *   4) 对数频带映射：bin 中心归属不重不漏
 *   5) 对数频带映射：单音定位正确性（≥300 Hz 必须全对）
 *   6) 对数频带映射：单音读数与带宽无关（0.85 ~ 1.0）
 *   7) 平滑器时间常数与帧量化
 *   8) FFT 单帧耗时
 */
public final class DspProbe {

    static final int SR = 48000;
    static final int N = 2048;
    static final int HOP = N / 2;
    static final float WINDOW_GAIN = 0.5f;   // Hann 的相干增益
    static final int BANDS = 60;
    static final float F_MIN = 30f, F_MAX = 16000f;

    static int failures = 0;

    // ─────────────────────────────────────────────────────────── FFT

    /** 基-2 迭代 FFT，预分配、零分配（与文档 §9 的骨架一致）。 */
    static final class Fft {
        final int n, log2n;
        final float[] re, im, cos, sin;
        final int[] rev;

        Fft(int n) {
            this.n = n;
            this.log2n = Integer.numberOfTrailingZeros(n);
            re = new float[n];
            im = new float[n];
            cos = new float[n / 2];
            sin = new float[n / 2];
            for (int i = 0; i < n / 2; i++) {
                double a = -2.0 * Math.PI * i / n;
                cos[i] = (float) Math.cos(a);
                sin[i] = (float) Math.sin(a);
            }
            rev = new int[n];
            for (int i = 0; i < n; i++) rev[i] = Integer.reverse(i) >>> (32 - log2n);
        }

        void magnitude(float[] x, float[] mag) {
            for (int i = 0; i < n; i++) { re[i] = x[rev[i]]; im[i] = 0f; }
            for (int len = 2; len <= n; len <<= 1) {
                int half = len >> 1, step = n / len;
                for (int i = 0; i < n; i += len) {
                    for (int j = 0, k = 0; j < half; j++, k += step) {
                        int a = i + j, b = a + half;
                        float tr = re[b] * cos[k] - im[b] * sin[k];
                        float ti = re[b] * sin[k] + im[b] * cos[k];
                        re[b] = re[a] - tr; im[b] = im[a] - ti;
                        re[a] += tr;        im[a] += ti;
                    }
                }
            }
            final float s = 2f / (n * WINDOW_GAIN), sdc = 1f / (n * WINDOW_GAIN);
            mag[0] = Math.abs(re[0]) * sdc;
            for (int i = 1; i <= n / 2; i++)
                mag[i] = (float) Math.sqrt(re[i] * re[i] + im[i] * im[i]) * s;
        }
    }

    // ───────────────────────────────────────────── 频带取值（§6.3 定稿规则）
    // 1) bin 中心唯一归属: ceil(f0/binHz) <= b < ceil(f1/binHz)
    // 2) 带内取 max（与带宽无关）
    // 3) 带内没有 bin 中心 → 在带的几何中心处对幅度谱线性插值
    static float bandAmp(float[] mag, float f0, float f1, float binHz, int half) {
        int bLo = Math.max(1, (int) Math.ceil(f0 / binHz));
        int bHi = Math.min(half + 1, Math.max(bLo, (int) Math.ceil(f1 / binHz)));
        float m = 0f;
        for (int b = bLo; b < bHi; b++) m = Math.max(m, mag[b]);
        if (m > 0f) return m;
        float fb = (float) Math.sqrt((double) f0 * f1) / binHz;
        int i = Math.max(1, Math.min(half - 1, (int) Math.floor(fb)));
        float t = fb - i;
        if (t < 0f) t = 0f; else if (t > 1f) t = 1f;
        return mag[i] * (1f - t) + mag[i + 1] * t;
    }

    // ─────────────────────────────────────────────────────────── 工具

    static float[] hann(int n) {
        float[] w = new float[n];
        for (int i = 0; i < n; i++) w[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / n));
        return w;
    }

    static void sine(float[] x, float[] w, double f, double amp) {
        for (int i = 0; i < x.length; i++)
            x[i] = (float) (amp * Math.sin(2 * Math.PI * f * i / SR) * w[i]);
    }

    static void check(boolean ok, String what, String detail) {
        System.out.printf("  [%s] %s%s%n", ok ? "PASS" : "FAIL", what,
                detail.isEmpty() ? "" : "  (" + detail + ")");
        if (!ok) failures++;
    }

    // ─────────────────────────────────────────────────────────── 主流程

    public static void main(String[] args) {
        int half = N / 2;
        float binHz = (float) SR / N;
        float[] w = hann(N);
        float[] x = new float[N];
        float[] mag = new float[half + 1];
        Fft fft = new Fft(N);

        System.out.println("=========================================================");
        System.out.println(" 频谱仪 DSP 探针   SR=" + SR + "  N=" + N + "  hop=" + HOP
                + "  bin=" + String.format("%.2f", binHz) + " Hz  B=" + BANDS);
        System.out.println("=========================================================");

        // ---- 1) FFT vs 朴素 DFT
        System.out.println("\n[1] FFT 正确性（与朴素 DFT 对拍，N=256，同一归一化）");
        {
            int n2 = 256;
            Fft f2 = new Fft(n2);
            float[] y = new float[n2], m2 = new float[n2 / 2 + 1];
            Random rnd = new Random(7);
            for (int i = 0; i < n2; i++) y[i] = rnd.nextFloat() * 2 - 1;
            f2.magnitude(y, m2);
            double maxErr = 0;
            for (int k = 1; k <= n2 / 2; k++) {
                double re = 0, im = 0;
                for (int t = 0; t < n2; t++) {
                    double ang = -2 * Math.PI * k * t / n2;
                    re += y[t] * Math.cos(ang);
                    im += y[t] * Math.sin(ang);
                }
                double ref = 2.0 * Math.sqrt(re * re + im * im) / (n2 * WINDOW_GAIN);
                maxErr = Math.max(maxErr, Math.abs(ref - m2[k]));
            }
            check(maxErr < 1e-5, "最大误差 < 1e-5", String.format("%.2e", maxErr));
        }

        // ---- 2) bin 中心满幅正弦
        System.out.println("\n[2] 幅度归一化（满幅正弦读数应为 1.0）");
        {
            int bin = 43;
            double f = bin * (double) SR / N;
            sine(x, w, f, 1.0);
            fft.magnitude(x, mag);
            check(Math.abs(mag[bin] - 1f) < 0.01f, "bin 中心正弦 (" + String.format("%.2f", f) + " Hz)",
                    String.format("mag[%d]=%.5f", bin, mag[bin]));
        }

        // ---- 3) Hann 最坏 scalloping
        System.out.println("\n[3] Hann 窗 scalloping（非 bin 中心单音的高度损失）");
        {
            double worst = 0;
            for (double off = 0; off < 0.5; off += 0.01) {
                double f = (43 + off) * (double) SR / N;
                sine(x, w, f, 1.0);
                fft.magnitude(x, mag);
                worst = Math.min(worst, 20 * Math.log10(mag[43]));
            }
            check(worst > -1.5 && worst < -1.2, "最坏损失在 -1.5 ~ -1.2 dB",
                    String.format("%.2f dB, 读数下限 %.3f", worst, Math.pow(10, worst / 20)));
        }

        // ---- 频带表
        float[] fEdge = new float[BANDS + 1];
        for (int k = 0; k <= BANDS; k++)
            fEdge[k] = (float) (F_MIN * Math.pow(F_MAX / F_MIN, (double) k / BANDS));

        // ---- 4) bin 中心归属不重不漏
        System.out.println("\n[4] 频带映射：bin 中心归属");
        {
            int[] owner = new int[half + 1];
            Arrays.fill(owner, -1);
            for (int k = 0; k < BANDS; k++) {
                int bLo = Math.max(1, (int) Math.ceil(fEdge[k] / binHz));
                int bHi = Math.min(half + 1, Math.max(bLo, (int) Math.ceil(fEdge[k + 1] / binHz)));
                for (int b = bLo; b < bHi; b++) owner[b] = (owner[b] == -1) ? k : -2;
            }
            int dup = 0, miss = 0;
            // 只检查中心频率落在 [fMin, fMax) 内的 bin：区间外的 bin 本来就不参与显示
            int loBin = (int) Math.ceil(F_MIN / binHz);
            int hiBin = (int) Math.floor((F_MAX - 1f) / binHz);
            for (int b = loBin; b <= hiBin; b++) {
                if (owner[b] == -2) dup++;
                if (owner[b] == -1) miss++;
            }
            check(dup == 0 && miss == 0, "fMin..fMax 区间内不重不漏",
                    "重复=" + dup + " 漏=" + miss + "，带 " + loBin + ".." + hiBin
                            + "（" + String.format("%.0f~%.0f Hz", loBin * binHz, hiBin * binHz) + "）");
        }

        // ---- 5) 单音定位
        System.out.println("\n[5] 频带映射：单音定位（≥300 Hz 要求全部命中正确频带）");
        {
            double[] tones = {30, 40, 60, 100, 150, 300, 440, 1000, 3000, 8000, 14000, 15900};
            int lowErr = 0, hiErr = 0;
            for (double f : tones) {
                sine(x, w, f, 1.0);
                fft.magnitude(x, mag);
                int best = -1; float bv = -1;
                for (int k = 0; k < BANDS; k++) {
                    float v = bandAmp(mag, fEdge[k], fEdge[k + 1], binHz, half);
                    if (v > bv) { bv = v; best = k; }
                }
                boolean inBand = f >= fEdge[best] && f < fEdge[best + 1];
                if (!inBand) { if (f >= 300) hiErr++; else lowErr++; }
                System.out.printf("      %6.0f Hz -> 带 %2d [%5.0f,%5.0f)  %.3f%s%n",
                        f, best, fEdge[best], fEdge[best + 1], bv,
                        inBand ? "" : "   <- ±1 bin（低频物理极限）");
            }
            check(hiErr == 0, "≥300 Hz 定位全对", "错 " + hiErr + " 个");
            System.out.println("      <300 Hz 有 " + lowErr + " 个落在相邻带：N=2048 的分辨率是 "
                    + String.format("%.1f Hz", binHz) + "，低频定位精度就是 ±1 bin，属物理极限（见文档 §4.1）");
        }

        // ---- 6) 单音读数与带宽无关
        System.out.println("\n[6] 频带映射：单音读数应与带宽无关（0.85 ~ 1.0）");
        {
            float lo = 9f, hi = 0f;
            for (double f : new double[]{300, 1000, 3000, 8000, 14000}) {
                sine(x, w, f, 1.0);
                fft.magnitude(x, mag);
                float bv = 0f;
                for (int k = 0; k < BANDS; k++)
                    bv = Math.max(bv, bandAmp(mag, fEdge[k], fEdge[k + 1], binHz, half));
                lo = Math.min(lo, bv); hi = Math.max(hi, bv);
            }
            check(lo >= 0.85f && hi <= 1.001f, "读数区间落在 0.85~1.0",
                    String.format("%.3f ~ %.3f（≈±1.4 dB，全部来自加窗）", lo, hi));
        }

        // ---- 7) 平滑器时间常数
        System.out.println("\n[7] 平滑器（§6.5，攻击 τ=15 ms @60 fps）");
        {
            double tau = 15.0, dt = 1000.0 / 60, v = 0;
            int frames = 0;
            while (v < 0.9 && frames < 1000) {
                double aUp = 1 - Math.exp(-dt / tau);
                v += (1 - v) * aUp;
                frames++;
            }
            double ms = frames * dt;
            check(ms > 2.0 * tau && ms < 3.5 * tau, "90% 上升时间 ≈ 2.3τ",
                    String.format("%d 帧 = %.1f ms（理论 %.1f ms，被 16.7 ms 帧长量化）",
                            frames, ms, 2.3 * tau));
        }

        // ---- 8) FFT 耗时
        System.out.println("\n[8] FFT 单帧耗时（JIT 预热后）");
        {
            sine(x, w, 1000, 1.0);
            for (int i = 0; i < 2000; i++) fft.magnitude(x, mag);
            long t0 = System.nanoTime();
            int iters = 5000;
            for (int i = 0; i < iters; i++) fft.magnitude(x, mag);
            double ms = (System.nanoTime() - t0) / 1e6 / iters;
            System.out.printf("      N=%d: %.3f ms/帧（host，%d 帧均值）→ 每帧预算 21.3 ms%n",
                    N, ms, iters);
            System.out.printf("      推算 SDM710（约慢 5~15 倍）: %.2f ~ %.2f ms%n", ms * 5, ms * 15);
        }

        System.out.println("\n=========================================================");
        System.out.println(failures == 0 ? " 全部断言通过" : (" 有 " + failures + " 项断言失败"));
        System.out.println("=========================================================");
        if (failures != 0) System.exit(1);
    }

    private DspProbe() {}
}
