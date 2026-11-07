package com.spectrum.app.core;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import android.util.Log;

import java.util.concurrent.locks.LockSupport;

/**
 * 麦克风来源：AudioRecord(UNPROCESSED → MIC 回落) → 环形缓冲 → 分析线程。
 *
 * 实测（设计文档 §4.2④）：本机 UNPROCESSED 源存在，minBufferSize = 3840 bytes = 1920 帧，
 * 深夜底噪约 −52.8 dBFS。UNPROCESSED 的意义是绕开 Fluence 降噪/AGC，别把频谱压平。
 */
public final class MicProvider extends PcmProvider {

    private static final String TAG = "SpectrumMic";

    private AudioRecord rec;
    private Thread capThread;
    private final short[] shortBuf = new short[HOP];
    private final float[] floatBuf = new float[HOP];

    private String sourceName = "?";

    @Override
    public String label() {
        return "麦克风 · " + sourceName;
    }

    /** 采集过程中被抢占（ERROR_DEAD_OBJECT）时的重建次数，给调试面板看。 */
    public volatile int rebuilds;

    @Override
    protected boolean startCapture() {
        if (!open()) return false;
        capThread = new Thread(new Runnable() {
            @Override public void run() {
                captureLoop();
            }
        }, "spectrum-capture");
        capThread.start();
        return true;
    }

    @Override
    protected void stopCapture() {
        Thread t = capThread;
        capThread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(300);
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
        }
        close();
    }

    private boolean open() {
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int bufBytes = Math.max(min * 2, HOP * 2 * 4);
        int[] sources = {MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC};
        String[] names = {"UNPROCESSED", "MIC"};
        for (int i = 0; i < sources.length; i++) {
            AudioRecord r = null;
            try {
                r = new AudioRecord(sources[i], SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufBytes);
                if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                    rec = r;
                    sourceName = names[i];
                    r.startRecording();
                    Log.i(TAG, "mic opened: source=" + names[i] + " minBuf=" + min
                            + " bytes, bufBytes=" + bufBytes);
                    return true;
                }
                Log.w(TAG, "source " + names[i] + " not initialized, state=" + r.getState());
                r.release();
            } catch (Throwable t) {
                Log.w(TAG, "source " + names[i] + " failed: " + t);
                if (r != null) {
                    try {
                        r.release();
                    } catch (Throwable ignore) {
                    }
                }
            }
        }
        rec = null;
        return false;
    }

    private void close() {
        AudioRecord r = rec;
        rec = null;
        if (r != null) {
            try {
                r.stop();
            } catch (Throwable ignore) {
            }
            try {
                r.release();
            } catch (Throwable ignore) {
            }
        }
    }

    private void captureLoop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        } catch (Throwable ignore) {
        }
        while (isRunning()) {
            AudioRecord r = rec;
            if (r == null) break;
            int got;
            try {
                got = r.read(shortBuf, 0, HOP);
            } catch (Throwable t) {
                Log.w(TAG, "read failed: " + t);
                got = AudioRecord.ERROR_INVALID_OPERATION;
            }
            if (got > 0) {
                for (int i = 0; i < got; i++) floatBuf[i] = shortBuf[i] / 32768f;
                ring().write(floatBuf, got);
            } else if (got == AudioRecord.ERROR_DEAD_OBJECT
                    || got == AudioRecord.ERROR_INVALID_OPERATION) {
                // 麦克风被别的 App 抢走了：停一下重建（设计文档 P5）
                Log.w(TAG, "read error " + got + ", reopening");
                rebuilds++;
                close();
                LockSupport.parkNanos(200_000_000L);
                if (!isRunning()) break;
                if (!open()) break;
            } else if (got == AudioRecord.ERROR_BAD_VALUE) {
                Log.w(TAG, "read ERROR_BAD_VALUE, stop capture");
                break;
            }
        }
    }
}
