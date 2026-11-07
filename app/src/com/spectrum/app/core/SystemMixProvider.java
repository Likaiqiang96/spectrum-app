package com.spectrum.app.core;

import android.media.audiofx.Visualizer;
import android.util.Log;

/**
 * 音源（系统混音）来源：抓「其它 App 正在播的歌」。
 *
 * 本机（Android 8.1）没有 AudioPlaybackCapture（要 API 29），唯一可行的路是
 * {@link Visualizer} 绑 <b>session 0</b>（全局输出混音）。这条路的能力边界都是真机实测出来的
 * （见 verify/2026-10-15-viz-probe/）：
 *
 *   - 回调频率上限 {@code getMaxCaptureRate()} = 20000 mHz = <b>20 Hz</b>
 *   - FFT 点数上限 {@code getCaptureSizeRange()} = [128, 1024] → 取 1024
 *   - 回调里的 samplingRate 单位是<b>毫赫兹</b>（44100000 = 44.1 kHz）
 *   - 波形是 8-bit <b>无符号</b>（静音 = 128）；FFT 是 8-bit <b>有符号</b> real/imag 交错
 *   - 媒体音量为 0 / 静音时数据<b>全 0</b> → 交给 SilenceWatchdog 提示用户
 *   - 全局混音捕获比同音源的轨道级捕获<b>低约 34 dB</b> → 用 {@link #MIX_GAIN_DB} 补偿
 *   - 需要 RECORD_AUDIO 权限（这台 Flyme 把 P 的要求提前到了 8.1，缺了直接 error -3）
 */
public final class SystemMixProvider extends SpectrumProvider {

    private static final String TAG = "SpectrumMix";

    /** 实测：44.1 kHz 下 1024 点 → 43.07 Hz/bin，所以 fMin 提到 90 Hz、柱数降到 32。 */
    /** 柱数与麦克风路保持一致（用户反馈：音源模式谱线密度低了一截）。
     *  captureSize 上限 1024 → 512 个频点、43Hz/bin，比高频带宽窄的频带由分带表的
     *  几何中心插值补出来（不会变成"一根柱子一个 bin"的台阶）。 */
    public static final int BANDS = PcmProvider.BANDS;
    public static final float F_MIN = 90f;
    public static final float F_MAX_DEFAULT = 16000f;

    /**
     * 实测：全局混音捕获比轨道级低约 34 dB，这里补 30 dB。
     * **只补在 VU 表（绝对刻度）上**：频带值保持原始 dB，
     * 否则响的频带会超过 0 dBFS，被自动量程直方图的 [−100, 0] 夹住 → 全频段贴顶。
     */
    private static final float MIX_GAIN_DB = 30f;

    /**
     * 底噪门限（dBFS）。系统混音是 8-bit 量化，静音时仍有 ~−48 dB 的 LSB 噪声；
     * 低于这个门限就当成没声音，避免倾斜补偿把高频噪声放大成一片"假频谱"。
     */
    private static final float MIX_NOISE_GATE_DB = -42f;
    private static final int CAPTURE_SIZE = 1024;
    private final float[] waveBuf = new float[SpectrumProvider.WAVE_POINTS];
    private volatile boolean waveReady;

    private Visualizer viz;
    private BandMapper mapper;
    private float[] mag = new float[CAPTURE_SIZE / 2 + 1];
    private float[] bandDb = new float[BANDS];

    private int sampleRate;
    private volatile float pendingFMax = -1f;
    private volatile int pendingAxis = -1;
    private boolean logAxis = true;
    private float fMax = F_MAX_DEFAULT;

    private volatile float lastRmsDb = -120f;
    private volatile boolean sawAnyData;
    private volatile String error;

    public SystemMixProvider() {
        super(BANDS);
    }

    private int wfCallbacks;
    private int fftCallbacks;
    private long firstCallbackMs;

    @Override
    public String label() {
        return "音源 · 系统混音(session 0)";
    }

    @Override
    public int bands() {
        return BANDS;
    }

    @Override
    public int sampleRate() {
        return sampleRate == 0 ? 44100 : sampleRate;
    }

    public String error() {
        return error;
    }

    public int callbacks() {
        return fftCallbacks;
    }

    @Override
    public void setFMax(float newFMax) {
        pendingFMax = newFMax;
    }

    @Override
    public float fMin() {
        return F_MIN;
    }

    @Override
    public void setAxisMode(boolean logAxis) {
        pendingAxis = logAxis ? 1 : 0;
    }

    @Override
    public boolean start() {
        if (viz != null) return true;
        try {
            Visualizer v = new Visualizer(0);
            int cs = Math.min(CAPTURE_SIZE, Visualizer.getCaptureSizeRange()[1]);
            int rc = v.setCaptureSize(cs);
            if (rc != Visualizer.SUCCESS) Log.w(TAG, "setCaptureSize rc=" + rc);
            v.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
            v.setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS);
            rc = v.setDataCaptureListener(new Listener(), Visualizer.getMaxCaptureRate(),
                    true, true);
            if (rc != Visualizer.SUCCESS) {
                Log.w(TAG, "setDataCaptureListener rc=" + rc);
                v.release();
                error = "setDataCaptureListener rc=" + rc;
                return false;
            }
            rc = v.setEnabled(true);
            if (rc != Visualizer.SUCCESS) {
                Log.w(TAG, "setEnabled rc=" + rc);
                v.release();
                error = "setEnabled rc=" + rc;
                return false;
            }
            viz = v;
            Log.i(TAG, "Visualizer(0) ready, captureSize=" + v.getCaptureSize()
                    + " maxRate=" + Visualizer.getMaxCaptureRate() + " mHz");
            return true;
        } catch (Throwable t) {
            // 最常见的是缺 RECORD_AUDIO：RuntimeException: Cannot initialize Visualizer engine, error: -3
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "Visualizer(0) failed: " + error);
            return false;
        }
    }

    @Override
    public void stop() {
        Visualizer v = viz;
        viz = null;
        if (v != null) {
            try {
                v.setEnabled(false);
            } catch (Throwable ignore) {
            }
            try {
                v.release();
            } catch (Throwable ignore) {
            }
            Log.i(TAG, "Visualizer(0) released, wf=" + wfCallbacks + " fft=" + fftCallbacks);
        }
    }

    @Override
    public void release() {
        stop();
    }

    /** 回调有几路（波形 / FFT），都在这一个监听器里。 */
    private final class Listener implements Visualizer.OnDataCaptureListener {

        @Override
        public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int rateMilliHz) {
            int sr = rateMilliHz / 1000;            // 单位是毫赫兹！
            if (sr > 0 && sr != sampleRate) sampleRate = sr;
            wfCallbacks++;
            if (firstCallbackMs == 0) {
                firstCallbackMs = android.os.SystemClock.elapsedRealtime();
                Log.i(TAG, "first callback: samplingRate=" + sr + " Hz, waveform=" + waveform.length);
            }
            double acc = 0;
            for (byte b : waveform) {
                double y = (b & 0xFF) - 128;        // 8-bit 无符号，中心 128
                acc += y * y;
            }
            double rms = Math.sqrt(acc / waveform.length) / 128.0;
            // 注意：这里**不加** MIX_GAIN_DB。VU 表是绝对刻度，加了补偿后会恒满
            //（用户反馈"音源模式 LED 一直满、不随增益变化"）。量程由渲染器按模式给：
            // 麦克风路 −45 dBFS、音源路 −75 dBFS。
            lastRmsDb = (float) (20 * Math.log10(rms + 1e-9));
            // 抽一份时域波形给示波器用（8-bit，中心 128）
            int wp = Math.min(SpectrumProvider.WAVE_POINTS, waveform.length);
            int trig = 0;
            for (int i = 1; i < waveform.length / 2; i++) {
                int prev = (waveform[i - 1] & 0xFF) - 128;
                int cur = (waveform[i] & 0xFF) - 128;
                if (prev < 0 && cur >= 0) { trig = i; break; }
            }
            for (int k = 0; k < wp; k++) {
                int idx = (trig + k) % waveform.length;
                waveBuf[k] = ((waveform[idx] & 0xFF) - 128) / 128f;
            }
            waveReady = true;
        }

        @Override
        public void onFftDataCapture(Visualizer visualizer, byte[] fft, int rateMilliHz) {
            int sr = rateMilliHz / 1000;
            if (sr <= 0) return;
            int axis = pendingAxis;
            if (sr != sampleRate || mapper == null || axis >= 0) {
                if (axis >= 0) {
                    logAxis = axis == 1;
                    pendingAxis = -1;
                }
                sampleRate = sr;
                rebuild(sr);
            }
            fftCallbacks++;

            int n = fft.length / 2;
            if (mag.length < n + 1) mag = new float[n + 1];
            boolean allZero = true;
            for (int i = 0; i < n; i++) {
                int re = fft[2 * i];
                int im = fft[2 * i + 1];
                if (re != 0 || im != 0) allZero = false;
                mag[i] = (float) Math.sqrt((double) re * re + (double) im * im) / 128f;
            }
            mag[n] = 0f;
            if (!allZero) sawAnyData = true;

            mapper.compute(mag, bandDb);
            if (fftCallbacks <= 2) logTopBins(sr);

            // 底噪门限：8-bit 量化的 LSB 噪声大约在 −48 dBFS 量级，再叠上 +3 dB/oct 的倾斜补偿，
            // 高频会被抬得比低频高 20+ dB —— 增益一大就能看到"没声音但高频很高、越往高频越高"。
            // 所以整体电平低于这个门限时直接按静音处理，把频带清零。
            if (lastRmsDb < MIX_NOISE_GATE_DB) {
                for (int i = 0; i < BANDS; i++) bandDb[i] = -160f;
            }

            SpectrumFrame f = nextFrame();
            for (int i = 0; i < BANDS; i++) {
                f.bandDb[i] = bandDb[i];          // 原始 dB：交给自动量程（它是相对映射）
            }
            f.bands = BANDS;
            if (waveReady) {
                System.arraycopy(waveBuf, 0, f.wave, 0, Math.min(waveBuf.length, f.wave.length));
                f.waveCount = Math.min(waveBuf.length, f.wave.length);
            } else {
                f.waveCount = 0;
            }
            f.rmsDb = lastRmsDb;
            f.bassDb = BandMapper.bandEnergyDb(mag, CAPTURE_SIZE, sampleRate, F_MIN, 250f);
            f.midDb = BandMapper.bandEnergyDb(mag, CAPTURE_SIZE, sampleRate, 250f, 4000f);
            f.trebleDb = BandMapper.bandEnergyDb(mag, CAPTURE_SIZE, sampleRate, 4000f, F_MAX_DEFAULT);
            // 全 0 = 还没放歌，或者媒体音量为 0（本机实测：音量 0 时数据全 0）
            f.silent = !sawAnyData || (allZero && lastRmsDb <= -60f);
            publish(f);
        }
    }

    /** 头两次回调把最强的几个 bin 打出来：核对 8-bit 解析、毫赫兹换算、bin→Hz 映射。 */
    private void logTopBins(int sr) {
        float binHz = sr / (float) CAPTURE_SIZE;
        int n = mag.length - 1;
        StringBuilder sb = new StringBuilder("top bins: ");
        boolean[] used = new boolean[n + 1];
        for (int k = 0; k < 4; k++) {
            int best = -1;
            float bv = -1;
            for (int i = 0; i <= n; i++) {
                if (!used[i] && mag[i] > bv) {
                    bv = mag[i];
                    best = i;
                }
            }
            if (best < 0) break;
            used[best] = true;
            sb.append(String.format(java.util.Locale.US, "#%d=%.0fHz/%.3f ",
                    best, best * binHz, bv));
        }
        sb.append(String.format(java.util.Locale.US, " binHz=%.2f rms=%.1fdB",
                binHz, lastRmsDb));
        Log.i(TAG, sb.toString());
    }

    private void rebuild(int sr) {
        float want = pendingFMax > 0 ? pendingFMax : fMax;
        fMax = want;
        pendingFMax = -1f;
        mapper = new BandMapper(CAPTURE_SIZE, sr, BANDS, F_MIN, fMax, !logAxis);
        mag = new float[CAPTURE_SIZE / 2 + 1];
        bandDb = new float[BANDS];
        Log.i(TAG, "mapper rebuilt: sr=" + sr + " fMin=" + F_MIN + " fMax=" + fMax
                + " bands=" + BANDS);
    }
}
