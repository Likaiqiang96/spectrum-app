package com.spectrum.vizprobe;

import android.app.Activity;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.Visualizer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * 频谱仪可行性探针（Meizu M1852 / Flyme 8.1.5 / Android 8.1 / API 27）
 *
 * 回答四件事：
 *
 *   [A] 「音源」（其它 App 正在播的歌）这条路在本机是否可行
 *       —— 只能靠 Visualizer 绑 session 0（全局输出混音）
 *   [B] 麦克风这条路是否可行，以及本机真实的底噪/频谱长什么样
 *   [C] 两条路各自的硬参数（回调频率、FFT 点数、数据格式）
 *   [D] 自研的 FFT + 对数分带在真机数据上跑得对不对
 *
 * 默认**不播放任何声音**：只有传了 `--ez tone true` 才会播 8+12 Hz 的亚低频
 * （手机喇叭物理上发不出来）来验证 Visualizer；平时只用环境噪声验证麦克风。
 *
 * logcat tag = VizProbe，屏幕上也同步显示。
 */
public class ProbeActivity extends Activity implements Visualizer.OnDataCaptureListener {

    private static final String TAG = "VizProbe";
    private static final int SR = 48000;
    private static final int N = 2048;         // 自研 FFT 点数
    private static final int BANDS = 48;
    private static final float F_MIN = 60f, F_MAX = 16000f;

    private static final String T_SYS = "A/system-mix(session 0)";
    private static final String T_MIC = "B/microphone";

    private Visualizer vizGlobal, vizOwn;
    private AudioTrack track;
    private AudioRecord mic;

    private final Stats sys = new Stats();
    private boolean playingTone;
    private long t0;

    private TextView tv;
    private final StringBuilder screen = new StringBuilder();
    private final Handler h = new Handler();
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** 自研 DSP：位反转迭代 FFT，预分配零分配（与设计文档 §9 的骨架同一份逻辑） */
    static final class Fft {
        final int n, log2n;
        final float[] re, im, cos, sin;
        final int[] rev;
        Fft(int n) {
            this.n = n; this.log2n = Integer.numberOfTrailingZeros(n);
            re = new float[n]; im = new float[n]; cos = new float[n / 2]; sin = new float[n / 2];
            for (int i = 0; i < n / 2; i++) {
                double a = -2.0 * Math.PI * i / n;
                cos[i] = (float) Math.cos(a); sin[i] = (float) Math.sin(a);
            }
            rev = new int[n];
            for (int i = 0; i < n; i++) rev[i] = Integer.reverse(i) >>> (32 - log2n);
        }
        void magnitude(float[] x, float[] mag) {
            for (int i = 0; i < n; i++) { re[i] = x[rev[i]]; im[i] = 0f; }
            for (int len = 2; len <= n; len <<= 1) {
                int half = len >> 1, step = n / len;
                for (int i = 0; i < n; i += len)
                    for (int j = 0, k = 0; j < half; j++, k += step) {
                        int a = i + j, b = a + half;
                        float tr = re[b] * cos[k] - im[b] * sin[k];
                        float ti = re[b] * sin[k] + im[b] * cos[k];
                        re[b] = re[a] - tr; im[b] = im[a] - ti;
                        re[a] += tr; im[a] += ti;
                    }
            }
            final float s = 2f / (n * 0.5f), sdc = 1f / (n * 0.5f);
            mag[0] = Math.abs(re[0]) * sdc;
            for (int i = 1; i <= n / 2; i++)
                mag[i] = (float) Math.sqrt(re[i] * re[i] + im[i] * im[i]) * s;
        }
    }

    private static final class Stats {
        int wf, fft, lastLen, lastSr, headLogged, gaps;
        long firstMs, lastMs;
        double sumRms, maxRms, sumPeak, sumGapMs;
        int mPeakMb, mRmsMb;
    }

    private final Visualizer.MeasurementPeakRms peakRms = new Visualizer.MeasurementPeakRms();
    private final Fft fft = new Fft(N);
    private final float[] mag = new float[N / 2 + 1];
    private final float[] win = new float[N];
    private final float[] hann = new float[N];
    private final float[] work = new float[N];
    private final float[] bandDbAvg = new float[BANDS];

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        tv = new TextView(this);
        tv.setTextSize(10.5f);
        tv.setTextColor(Color.WHITE);
        tv.setBackgroundColor(Color.BLACK);
        tv.setPadding(20, 20, 20, 20);
        setContentView(tv);

        for (int i = 0; i < N; i++) hann[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / N));

        boolean wantTone = getIntent() != null && getIntent().getBooleanExtra("tone", false);
        t0 = SystemClock.elapsedRealtime();
        say("device=" + android.os.Build.MODEL + " api=" + android.os.Build.VERSION.SDK_INT
                + "  tone=" + wantTone);
        say("Visualizer: maxCaptureRate=" + Visualizer.getMaxCaptureRate() + " mHz = "
                + (Visualizer.getMaxCaptureRate() / 1000.0) + " Hz, captureSizeRange="
                + Visualizer.getCaptureSizeRange()[0] + ".." + Visualizer.getCaptureSizeRange()[1]);
        if (wantTone) startVisualizerTest();
        else say("(没传 tone=true：不播任何声音，只测麦克风)");
        h.postDelayed(micPhase, wantTone ? 17000 : 2000);
        h.postDelayed(tick, 2000);
    }

    // ────────────────────── [A] 系统混音（Visualizer） ──────────────────────

    private void startVisualizerTest() {
        try {
            int min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack(AudioManager.STREAM_MUSIC, SR, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, SR), AudioTrack.MODE_STREAM);
            track.setVolume(0.5f);
            track.play();
            say("tone track: state=" + track.getState() + " playState=" + track.getPlayState()
                    + " session=" + track.getAudioSessionId());
        } catch (Throwable t) {
            say("tone track FAILED: " + t);
            return;
        }
        vizGlobal = makeViz(0, T_SYS);
        vizOwn = makeViz(track.getAudioSessionId(), "A/own-track(session "
                + track.getAudioSessionId() + ")");
        new Thread(new Runnable() {
            @Override public void run() {
                short[] buf = new short[SR];
                for (int i = 0; i < SR; i++)
                    buf[i] = (short) ((0.6 * Math.sin(2 * Math.PI * 8.0 * i / SR)
                            + 0.3 * Math.sin(2 * Math.PI * 12.0 * i / SR)) * 32767);
                playingTone = true;
                say("tone start: 8Hz+12Hz (手机喇叭发不出来), 14s");
                long end = SystemClock.elapsedRealtime() + 14000;
                while (SystemClock.elapsedRealtime() < end) track.write(buf, 0, SR);
                playingTone = false;
                say("tone stop");
                try { track.stop(); track.release(); } catch (Throwable ignore) {}
                track = null;
            }
        }, "tone").start();
    }

    private Visualizer makeViz(int session, String label) {
        try {
            Visualizer v = new Visualizer(session);
            v.setCaptureSize(1024);
            v.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
            v.setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS);
            v.setDataCaptureListener(this, Visualizer.getMaxCaptureRate(), true, true);
            int rc = v.setEnabled(true);
            say(label + ": setEnabled rc=" + rc + " enabled=" + v.getEnabled()
                    + " captureSize=" + v.getCaptureSize() + " scaling=" + v.getScalingMode());
            return v;
        } catch (Throwable t) {
            say(label + ": EXCEPTION " + t);
            return null;
        }
    }

    @Override
    public void onWaveFormDataCapture(Visualizer v, byte[] wf, int sr) {
        if (v != vizGlobal) return;
        Stats c = sys;
        note(c, wf.length, sr);
        double acc = 0;
        for (byte x : wf) { double y = (x & 0xFF) - 128; acc += y * y; }
        double rms = Math.sqrt(acc / wf.length) / 128.0;
        c.sumRms += rms;
        if (rms > c.maxRms) c.maxRms = rms;
        if (c.headLogged++ < 2) {
            StringBuilder sb = new StringBuilder(T_SYS + " wf#" + c.wf + " len=" + wf.length
                    + " sr=" + sr + " rms=" + String.format("%.5f", rms)
                    + " tone=" + playingTone + " head=");
            for (int i = 0; i < Math.min(16, wf.length); i++) sb.append(wf[i] & 0xFF).append(' ');
            say(sb.toString());
        }
    }

    @Override
    public void onFftDataCapture(Visualizer v, byte[] fftBytes, int sr) {
        if (v != vizGlobal) return;
        Stats c = sys;
        note(c, fftBytes.length, sr);
        double peak = 0; int peakBin = -1;
        for (int i = 0; i < fftBytes.length / 2; i++) {
            int re = fftBytes[2 * i], im = fftBytes[2 * i + 1];
            double m = Math.sqrt((double) re * re + (double) im * im);
            if (m > peak) { peak = m; peakBin = i; }
        }
        c.sumPeak += peak;
        if (c.headLogged++ < 3) {
            StringBuilder sb = new StringBuilder(T_SYS + " fft#" + c.fft + " len=" + fftBytes.length
                    + " sr=" + sr + " peakBin=" + peakBin + " peak=" + String.format("%.1f", peak)
                    + " tone=" + playingTone + " raw[0..7]=");
            for (int i = 0; i < Math.min(8, fftBytes.length); i++) sb.append(fftBytes[i]).append(' ');
            say(sb.toString());
        }
    }

    private void note(Stats c, int len, int sr) {
        long now = SystemClock.elapsedRealtime();
        if (c.lastMs != 0) { c.gaps++; c.sumGapMs += (now - c.lastMs); }
        c.lastMs = now;
        if (c.firstMs == 0) c.firstMs = now;
        c.wf++;
        c.lastLen = len; c.lastSr = sr;
    }

    // ────────────────────── [B] 麦克风（环境噪声） ──────────────────────

    private final Runnable micPhase = new Runnable() {
        @Override public void run() {
            new Thread(new Runnable() { @Override public void run() { micTest(); } }, "mic").start();
        }
    };

    private void micTest() {
        int minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        say(T_MIC + ": minBufferSize=" + minBuf + " bytes = " + (minBuf / 2) + " frames");
        AudioRecord r = null;
        for (int src : new int[]{MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC}) {
            try {
                r = new AudioRecord(src, SR, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf * 2, 8192));
                if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                    say(T_MIC + ": AudioSource=" + srcName(src) + " OK");
                    break;
                }
                say(T_MIC + ": AudioSource=" + srcName(src) + " state=" + r.getState());
                r.release(); r = null;
            } catch (Throwable t) {
                say(T_MIC + ": AudioSource=" + srcName(src) + " -> " + t);
                r = null;
            }
        }
        if (r == null) { say(T_MIC + ": 没有可用的录音源"); return; }
        mic = r;
        r.startRecording();

        short[] buf = new short[1024];
        long end = SystemClock.elapsedRealtime() + 8000;
        long nFrames = 0; double sumSq = 0, maxRms = 0; int chunks = 0, ffts = 0;
        while (SystemClock.elapsedRealtime() < end) {
            int got = r.read(buf, 0, buf.length);
            if (got <= 0) continue;
            chunks++;
            double s = 0;
            for (int i = 0; i < got; i++) { double x = buf[i] / 32768.0; s += x * x; }
            sumSq += s; nFrames += got;
            double rms = Math.sqrt(s / got);
            if (rms > maxRms) maxRms = rms;

            // 滑动窗（hop = 1024 = N/2），每 10 块做一次自研 FFT + 对数分带
            System.arraycopy(win, got, win, 0, N - got);
            for (int i = 0; i < got && i < N; i++) win[N - got + i] = (float) (buf[i] / 32768.0);
            if (chunks % 10 == 0) {
                ffts++;
                for (int i = 0; i < N; i++) work[i] = win[i] * hann[i];
                fft.magnitude(work, mag);
                accumulateBands();
                if (ffts == 1) say(topBins());
            }
        }
        r.stop(); r.release();
        mic = null;

        double meanRms = Math.sqrt(sumSq / Math.max(1, nFrames));
        say(String.format(T_MIC + " 结果: chunks=%d frames=%d ffts=%d", chunks, nFrames, ffts));
        say(String.format(T_MIC + " 电平: meanRms=%.5f (%.1f dBFS)  maxRms=%.5f (%.1f dBFS)",
                meanRms, db(meanRms), maxRms, db(maxRms)));
        say(bandLine());
    }

    private void accumulateBands() {
        float binHz = SR / (float) N;
        for (int k = 0; k < BANDS; k++) {
            float f0 = (float) (F_MIN * Math.pow(F_MAX / F_MIN, (double) k / BANDS));
            float f1 = (float) (F_MIN * Math.pow(F_MAX / F_MIN, (double) (k + 1) / BANDS));
            int lo = Math.max(1, (int) Math.ceil(f0 / binHz));
            int hi = Math.min(N / 2 + 1, Math.max(lo, (int) Math.ceil(f1 / binHz)));
            float m = 0;
            for (int b = lo; b < hi; b++) m = Math.max(m, mag[b]);
            if (m == 0f) {   // 带比一个 bin 还窄：在带的几何中心插值（文档 §6.3 规则 3）
                float fb = (float) Math.sqrt((double) f0 * f1) / binHz;
                int i = Math.max(1, Math.min(N / 2 - 1, (int) Math.floor(fb)));
                float t = Math.min(1f, Math.max(0f, fb - i));
                m = mag[i] * (1 - t) + mag[i + 1] * t;
            }
            bandDbAvg[k] += (db(m) - bandDbAvg[k]) * 0.2f;
        }
    }

    private String topBins() {
        StringBuilder sb = new StringBuilder(T_MIC + " 频谱 Top5 (bin→Hz / dB): ");
        float binHz = SR / (float) N;
        boolean[] used = new boolean[N / 2 + 1];
        for (int n = 0; n < 5; n++) {
            int best = -1; float bv = -1;
            for (int i = 1; i <= N / 2; i++) if (!used[i] && mag[i] > bv) { bv = mag[i]; best = i; }
            if (best < 0) break;
            used[best] = true;
            sb.append(String.format("#%d=%.0fHz/%.1fdB ", best, best * binHz, db(bv)));
        }
        return sb.toString();
    }

    private String bandLine() {
        StringBuilder sb = new StringBuilder(T_MIC + " 分带(每 4 带取一, dBFS): ");
        for (int k = 0; k < BANDS; k += 4) {
            float f = (float) (F_MIN * Math.pow(F_MAX / F_MIN, (double) k / BANDS));
            sb.append(String.format("%.0fHz:%.0f ", f, bandDbAvg[k]));
        }
        return sb.toString();
    }

    private static double db(double amp) { return 20 * Math.log10(amp + 1e-9); }

    private static String srcName(int src) {
        switch (src) {
            case MediaRecorder.AudioSource.UNPROCESSED: return "UNPROCESSED";
            case MediaRecorder.AudioSource.MIC: return "MIC";
            default: return String.valueOf(src);
        }
    }

    // ────────────────────── 定时汇总 ──────────────────────

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            double sec = (SystemClock.elapsedRealtime() - t0) / 1000.0;
            if (sys.wf > 0) {
                say(String.format("t=%.0fs [系统混音] wf=%d fft=%d sr=%d gap=%.1fms meanRms=%.5f maxRms=%.5f",
                        sec, sys.wf, sys.fft, sys.lastSr,
                        sys.gaps == 0 ? 0 : sys.sumGapMs / sys.gaps,
                        sys.wf == 0 ? 0 : sys.sumRms / sys.wf, sys.maxRms));
            }
            h.postDelayed(this, 4000);
        }
    };

    private void say(final String s) {
        Log.i(TAG, s);
        ui.post(new Runnable() {
            @Override public void run() {
                screen.append(s).append('\n');
                if (screen.length() > 4000) screen.delete(0, screen.length() - 4000);
                tv.setText(screen);
            }
        });
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        for (Visualizer v : new Visualizer[]{vizGlobal, vizOwn}) {
            try { if (v != null) { v.setEnabled(false); v.release(); } } catch (Throwable ignore) {}
        }
        try { if (track != null) { track.stop(); track.release(); } } catch (Throwable ignore) {}
        try { if (mic != null) { mic.stop(); mic.release(); } } catch (Throwable ignore) {}
        super.onDestroy();
    }
}
