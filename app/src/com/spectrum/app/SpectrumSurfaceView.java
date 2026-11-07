package com.spectrum.app;

import android.content.Context;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * 渲染载体：SurfaceView + 专用渲染线程 + Choreographer 对齐 vsync + lockHardwareCanvas()。
 *
 * 不走 UI 线程的 invalidate()，所以不会被 measure/layout/draw 拖累（设计文档 §3.4）。
 * 长按 = 切换调试面板；普通点击 = 交给渲染器（菜单/唤醒）。
 */
public final class SpectrumSurfaceView extends SurfaceView implements SurfaceHolder.Callback {

    private static final long LONG_PRESS_MS = 700L;
    private static final float TOUCH_SLOP = 30f;

    private final SpectrumRenderer renderer;
    private HandlerThread renderThread;
    private Handler renderHandler;
    private Choreographer choreographer;
    private volatile boolean running;

    private long downNs;
    private float downX;
    private float downY;
    private boolean moved;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running) return;
            long dtMs = lastNs == 0 ? 16L : (frameTimeNanos - lastNs) / 1000000L;
            lastNs = frameTimeNanos;
            if (dtMs < 1) dtMs = 1;
            if (dtMs > 100) dtMs = 100;
            long t0 = System.nanoTime();
            SurfaceHolder holder = getHolder();
            Canvas canvas = null;
            try {
                canvas = holder.lockHardwareCanvas();
                if (canvas != null) {
                    renderer.render(canvas, SystemClock.elapsedRealtime(), dtMs);
                }
            } catch (Throwable t) {
                // Surface 正在销毁之类：丢掉这一帧就好，但要留下痕迹
                if (renderErrors++ < 5) {
                    android.util.Log.e("Spectrum", "render failed", t);
                }
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas);
                    } catch (Throwable ignore) {
                    }
                }
            }
            renderer.noteFrame(frameTimeNanos, t0, System.nanoTime());
            if (running) choreographer.postFrameCallback(this);
        }
    };

    private long lastNs;
    private int renderErrors;

    public SpectrumSurfaceView(Context ctx, SpectrumRenderer renderer) {
        super(ctx);
        this.renderer = renderer;
        getHolder().addCallback(this);
        setFocusable(true);
        setClickable(true);
        setKeepScreenOn(true);
    }

    public SpectrumSurfaceView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        throw new UnsupportedOperationException("use SpectrumSurfaceView(Context, SpectrumRenderer)");
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        startRenderThread();
    }

    @Override
    public void surfaceChanged(final SurfaceHolder holder, int format, final int width,
                               final int height) {
        Handler h = renderHandler;
        if (h != null) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    renderer.setSize(width, height);
                }
            });
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopRenderThread();
    }

    private void startRenderThread() {
        if (running) return;
        running = true;
        renderThread = new HandlerThread("spectrum-render", Process.THREAD_PRIORITY_DISPLAY);
        renderThread.start();
        renderHandler = new Handler(renderThread.getLooper());
        renderHandler.post(new Runnable() {
            @Override
            public void run() {
                choreographer = Choreographer.getInstance();
                lastNs = 0;
                choreographer.postFrameCallback(frameCallback);
            }
        });
    }

    private void stopRenderThread() {
        running = false;
        HandlerThread t = renderThread;
        renderThread = null;
        renderHandler = null;
        if (t != null) {
            t.quit();
            try {
                t.join(300);
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downNs = System.nanoTime();
                downX = e.getX();
                downY = e.getY();
                moved = false;
                renderer.onTouchDown(e.getX(), e.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(e.getX() - downX) > TOUCH_SLOP
                        || Math.abs(e.getY() - downY) > TOUCH_SLOP) {
                    moved = true;
                }
                renderer.onTouchMove(e.getX(), e.getY());
                return true;
            case MotionEvent.ACTION_UP: {
                long held = (System.nanoTime() - downNs) / 1000000L;
                if (!moved && held >= LONG_PRESS_MS) {
                    renderer.toggleDebug();     // 长按 = 调试面板，不当作点击
                    return true;
                }
                renderer.onTouchUp(e.getX(), e.getY());
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }
}
