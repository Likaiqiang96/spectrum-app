package com.spectrum.app.core;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import java.util.concurrent.locks.LockSupport;

/**
 * 验证辅助：播一段**手机喇叭发不出来的亚低频**（8 Hz + 12 Hz），
 * 用来在「不发出任何声音」的前提下验证系统混音（Visualizer session 0）这条通路。
 *
 * 只有 MainActivity 收到 {@code --ez mixtest true} 时才会用它，
 * 正常使用不会创建这个对象。
 */
public final class TonePlayer {

    private static final String TAG = "SpectrumTone";
    private static final int SR = 48000;

    private AudioTrack track;
    private Thread thread;
    private volatile boolean running;

    public boolean start() {
        if (running) return true;
        try {
            int min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack(AudioManager.STREAM_MUSIC, SR, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, SR), AudioTrack.MODE_STREAM);
            track.setVolume(0.5f);
            track.play();
        } catch (Throwable t) {
            Log.w(TAG, "AudioTrack failed: " + t);
            stop();
            return false;
        }
        running = true;
        thread = new Thread(new Runnable() {
            @Override public void run() {
                short[] buf = new short[SR];
                for (int i = 0; i < SR; i++) {
                    double s = 0.6 * Math.sin(2 * Math.PI * 8.0 * i / SR)
                            + 0.3 * Math.sin(2 * Math.PI * 12.0 * i / SR);
                    buf[i] = (short) (s * 32767);
                }
                Log.i(TAG, "tone start: 8Hz+12Hz (手机喇叭发不出来), session="
                        + track.getAudioSessionId());
                while (running) {
                    track.write(buf, 0, buf.length);
                }
                Log.i(TAG, "tone stop");
            }
        }, "spectrum-tone");
        thread.start();
        return true;
    }

    public void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            t.interrupt();
            LockSupport.parkNanos(20_000_000L);
        }
        AudioTrack a = track;
        track = null;
        if (a != null) {
            try {
                a.stop();
            } catch (Throwable ignore) {
            }
            try {
                a.release();
            } catch (Throwable ignore) {
            }
        }
    }
}
