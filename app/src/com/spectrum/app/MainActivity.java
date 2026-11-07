package com.spectrum.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.spectrum.app.core.FakeProvider;
import com.spectrum.app.core.MicProvider;
import com.spectrum.app.core.SelfTestProvider;
import com.spectrum.app.core.SystemMixProvider;
import com.spectrum.app.core.TonePlayer;
import com.spectrum.app.core.SpectrumFrame;
import com.spectrum.app.core.SpectrumProvider;
import com.spectrum.app.settings.AppSettings;

/**
 * 一屏搞定：沉浸全屏 + 横屏 + 屏幕常亮，画面全交给 {@link SpectrumSurfaceView}。
 *
 * 这一版只做「麦克风」这一条路（用户要求先做麦克风）。
 * 调试：
 *   adb shell am start -n com.spectrum.app/.MainActivity --ez selftest true   # 自检信号，不需要声音
 *   长按屏幕 = 调试面板（fps / 分析帧率 / RMS / 量程）
 */
public final class MainActivity extends Activity implements SpectrumRenderer.Listener {

    private static final String TAG = "Spectrum";
    private static final int REQ_AUDIO = 1;

    private AppSettings settings;
    private SpectrumRenderer renderer;
    private SpectrumSurfaceView surface;
    private SpectrumProvider provider;
    private TextView message;
    private boolean selftest;
    private boolean mixTest;
    private boolean fakeSpectrum;
    private TonePlayer tone;

    private final Handler handler = new Handler();
    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            if (provider != null) {
                renderer.setProviderLabel(provider.label());
                if (provider instanceof MicProvider) {
                    renderer.setRebuilds(((MicProvider) provider).rebuilds);
                }
            }
            Log.i(TAG, "mode=" + settings.label(AppSettings.MODE) + " " + renderer.status());
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // 本机在横屏下窗口被系统偏移（surface 落在 x=51、宽 2169），左边会留一条 ~51px 纯黑。
        // 允许窗口越界布局，逼它铺满整屏（2220×1080），把那条黑边吃掉。
        // 关键：让窗口按"屏幕"而非"应用区"布局。本机横屏时系统给导航栏留了 96px
        //（dumpsys: app=2124x1080），不加重算会在边上留黑条。
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR);

        settings = new AppSettings();
        settings.attach(this);
        selftest = getIntent() != null && getIntent().getBooleanExtra("selftest", false);
        mixTest = getIntent() != null && getIntent().getBooleanExtra("mixtest", false);
        // --ez mixmode true：直接强制音源模式（换机验证用 —— MIUI 默认禁止 adb 注入触摸，
        // 点不了菜单，只能靠这个参数切模式）
        if (getIntent() != null && getIntent().getBooleanExtra("mixmode", false)) {
            settings.set(AppSettings.MODE, AppSettings.MODE_MIX);
        }
        // --ez micmode true：强制回麦克风
        if (getIntent() != null && getIntent().getBooleanExtra("micmode", false)) {
            settings.set(AppSettings.MODE, AppSettings.MODE_MIC);
        }
        applyDebugExtras(getIntent());
        fakeSpectrum = getIntent() != null && getIntent().getBooleanExtra("fakespectrum", false);

        applyImmersive();          // 尽早设，让第一遍布局就按全屏算
        renderer = new SpectrumRenderer(settings);
        renderer.setListener(this);
        WindowManager.LayoutParams initLp = getWindow().getAttributes();
        // 亮度跟其它设置一样持久化：上次退出时多少，这次启动就多少
        float savedBright = settings.brightness;
        renderer.setBrightness(savedBright);
        WindowManager.LayoutParams blp = getWindow().getAttributes();
        blp.screenBrightness = savedBright;
        getWindow().setAttributes(blp);

        surface = new SpectrumSurfaceView(this, renderer);

        FrameLayout root = new FrameLayout(this);
        root.addView(surface, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        message = new TextView(this);
        message.setTextColor(0xFFFFFFFF);
        message.setTextSize(18f);
        message.setPadding(48, 48, 48, 48);
        message.setVisibility(View.GONE);
        root.addView(message, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive();          // 有些 ROM 会在切换应用后把状态栏/导航栏放回来，回来时再压一次
        // 沉浸标志生效后窗口会重新布局，延迟再压一次并触发一次尺寸重算
        getWindow().getDecorView().postDelayed(new Runnable() {
            @Override
            public void run() {
                applyImmersive();
                getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT);
            }
        }, 400L);
        if (selftest) {
            startEngine();
        } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startEngine();
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        }
        handler.removeCallbacks(statusTick);
        handler.postDelayed(statusTick, 2000);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(statusTick);
        stopEngine();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQ_AUDIO) {
            boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            if (ok) {
                startEngine();
            } else {
                showMessage("需要录音权限才能采集麦克风");
            }
        }
    }

    private void startEngine() {
        if (provider != null) return;
        int mode = settings.sel[AppSettings.MODE];
        SpectrumProvider p;
        if (fakeSpectrum) {
            p = new FakeProvider();          // 只验证渲染映射，不碰音频
        } else if (selftest) {
            p = new SelfTestProvider();
        } else if (mode == AppSettings.MODE_MIX) {
            p = new SystemMixProvider();
        } else {
            p = new MicProvider();
        }
        if (!attachAndStart(p)) {
            p.release();
            if (mode == AppSettings.MODE_MIX && !selftest) {
                // 系统混音用不了（最常见：Flyme 拒绝 session 0，或缺 RECORD_AUDIO）→ 回退麦克风
                String err = (p instanceof SystemMixProvider)
                        ? ((SystemMixProvider) p).error() : null;
                Log.w(TAG, "system mix unavailable: " + err + " -> fallback to mic");
                settings.set(AppSettings.MODE, AppSettings.MODE_MIC);
                showMessage("系统混音不可用（" + err + "），已切回麦克风");
                SpectrumProvider mic = new MicProvider();
                attachAndStart(mic);
            } else {
                showMessage("麦克风打不开（可能被其它 App 占用）");
            }
        }
        if (mixTest && provider instanceof SystemMixProvider) startMixTestTone();
    }

    /** 把 sink 接上并启动；成功才把 provider 记下来。 */
    private boolean attachAndStart(SpectrumProvider p) {
        p.setSink(new SpectrumProvider.Sink() {
            @Override
            public void onSpectrum(SpectrumFrame frame) {
                renderer.submit(frame);       // 只存引用：provider 线程绝不做界面
            }
        });
        if (!p.start()) return false;
        provider = p;
        p.setFMax(settings.fMaxHz());
        p.setAxisMode(settings.logAxis());
        renderer.setProviderLabel(p.label());
        renderer.setFMin(p.fMin());
        hideMessage();
        Log.i(TAG, "engine started: " + p.label() + " bands=" + p.bands()
                + " sr=" + p.sampleRate());
        return true;
    }

    /** 只在 --ez mixtest true 时用：播一段听不见的亚低频，让系统混音里有东西可抓。 */
    private void startMixTestTone() {
        if (tone != null) return;
        tone = new TonePlayer();
        if (tone.start()) {
            Log.i(TAG, "mixtest tone started (inaudible 8+12Hz)");
        } else {
            tone = null;
            Log.w(TAG, "mixtest tone failed to start");
        }
    }

    private void stopEngine() {
        SpectrumProvider p = provider;
        provider = null;
        if (p != null) {
            p.release();
            Log.i(TAG, "engine stopped");
        }
        TonePlayer t = tone;
        tone = null;
        if (t != null) t.stop();
    }

    /**
     * 调试用：允许完全用启动参数设置各项菜单（换机验证时若 ROM 禁止 adb 注入触摸，
     * 就没法点菜单，只能靠这个）。例：
     *   adb shell am start -n com.spectrum.app/.MainActivity \
     *       --ez mixmode true --ei style 4 --ei color 3 --ei gain 12
     */
    private void applyDebugExtras(android.content.Intent it) {
        if (it == null) return;
        if (it.hasExtra("style")) settings.set(AppSettings.STYLE, it.getIntExtra("style", 0));
        if (it.hasExtra("color")) settings.set(AppSettings.COLOR, it.getIntExtra("color", 0));
        if (it.hasExtra("peak")) settings.set(AppSettings.PEAK, it.getIntExtra("peak", 2));
        if (it.hasExtra("coord")) settings.set(AppSettings.COORD, it.getIntExtra("coord", 0));
        if (it.hasExtra("zoom")) settings.set(AppSettings.ZOOM, it.getIntExtra("zoom", 2));
        if (it.hasExtra("range")) settings.set(AppSettings.RANGE, it.getIntExtra("range", 0));
        if (it.hasExtra("sleep")) settings.set(AppSettings.SLEEP, it.getIntExtra("sleep", 0));
        if (it.hasExtra("gain")) {
            settings.setGainDb(it.getFloatExtra("gain", 0f));
            settings.persistGain();
        }
        if (it.hasExtra("timebase")) {
            settings.set(AppSettings.TIMEBASE, it.getIntExtra("timebase", 1));
        }
        if (it.hasExtra("display")) {
            settings.set(AppSettings.DISPLAY, it.getIntExtra("display", 7));
        }
        if (it.hasExtra("bright")) {
            // 走设置这条路，才会和其它设置一起持久化（调试参数也遵守同样的语义）
            settings.setBrightness(it.getFloatExtra("bright", 0.6f));
            settings.persistBrightness();
        }
    }

    private void applyImmersive() {
        // Android 11+ 用正式 API：让内容按整屏布局并隐藏系统栏。
        // 否则窗口会被系统 inset 缩进（实测小米 10：app=2250x1036 而不是 2340x1080，
        // 左右各留约 45px 黑边，App 画不进去）。
        try {
            // 先取 decor：onCreate 里 setContentView 之前 mDecor 还是 null，
            // 直接 getInsetsController() 会 NPE（在小米 10 上崩过一次）。
            View decor = getWindow().getDecorView();
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                getWindow().setDecorFitsSystemWindows(false);
                android.view.WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    c.hide(android.view.WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            }
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } catch (Throwable t) {
            android.util.Log.w("Spectrum", "applyImmersive failed", t);
        }
    }

    private void showMessage(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                message.setText(text);
                message.setVisibility(View.VISIBLE);
            }
        });
    }

    private void hideMessage() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                message.setVisibility(View.GONE);
            }
        });
    }

    // ── SpectrumRenderer.Listener（可能从渲染线程回调）──

    @Override
    public void onModeChanged() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "mode changed -> restart engine");
                stopEngine();
                startEngine();
            }
        });
    }

    @Override
    public void onBrightnessChanged(final float value) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = value;      // 只影响本 App 的窗口，不需要系统权限
                getWindow().setAttributes(lp);
            }
        });
    }

    @Override
    public void onAxisChanged(final boolean logAxis) {
        final SpectrumProvider p = provider;
        if (p != null) p.setAxisMode(logAxis);
    }

    @Override
    public void onFMaxChanged(final float hz) {
        final SpectrumProvider p = provider;
        if (p != null) p.setFMax(hz);
    }

    @Override
    public void onSleepRequested() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                stopEngine();
                Log.i(TAG, "sleep: engine stopped");
            }
        });
    }

    @Override
    public void onWakeRequested() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                startEngine();
                Log.i(TAG, "wake: engine restarted");
            }
        });
    }
}
