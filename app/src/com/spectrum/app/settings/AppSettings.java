package com.spectrum.app.settings;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 菜单与设置。索引和参考图顶栏那 8 项一一对应：
 * 模式 样式 缩放 峰值 颜色 范围 歌词 休眠。
 *
 * 触摸在 UI 线程改 sel[]，渲染线程读，所以每个字段都是 volatile 的 int。
 */
public final class AppSettings {

    public static final int MODE = 0;
    public static final int STYLE = 1;
    public static final int COORD = 2;      // 横坐标：对数 / 线性
    public static final int ZOOM = 3;
    public static final int PEAK = 4;
    public static final int COLOR = 5;
    public static final int RANGE = 6;
    public static final int SLEEP = 7;
    public static final int GRAVITY = 8;    // 峰值点的重力（弹道模型）
    public static final int DRAG = 9;       // 峰值点的空气阻力
    public static final int DISPLAY = 10;   // 显示哪些坐标元素（位掩码）
    public static final int TIMEBASE = 11;  // 声波的时间尺度（显示多少毫秒）
    public static final int COUNT = 12;

    public static final String[] MENUS =
            {"模式", "样式", "坐标", "缩放", "峰值", "颜色", "范围", "休眠", "重力", "阻力", "显示", "时基"};

    public static final String[][] OPTIONS = {
            {"麦克风", "音源"},
            {"柱形", "疏线", "三角", "波形", "面积"},
            {"对数", "线性"},
            {"4k", "8k", "16k"},
            {"关", "0s", "0.5s", "1s", "2s"},
            {"青红", "绿黄红", "纯白", "流光溢彩", "单色红", "单色绿", "单色蓝", "单色紫"},
            {"自动", "-60", "-80", "-100"},
            {"关", "30s", "60s", "5min"},
            {"0.2g", "0.5g", "1g", "2g", "4g"},   // 重力
            {"无", "低", "中", "高"},              // 阻力
            {"横坐标", "纵坐标", "刻度线", "内部横线", "声波"},   // 显示（多选，位掩码 1/2/4/8/16）
            {"5ms", "10ms", "20ms"},              // 时基（声波显示多长时间）
    };

    /** 模式取值：0 = 麦克风，1 = 音源（系统混音）。 */
    public static final int MODE_MIC = 0;
    public static final int MODE_MIX = 1;

    private static final int[] DEFAULTS = {0, 0, 0, 2, 1, 0, 0, 0, 2, 1, 15, 1};  // 显示=15（声波关）、时基=10ms

    private static final String PREF = "spectrum";
    /** 菜单项变过（加了「坐标」），旧键不再复用。 */
    private static final String KEY = "v5_sel";     // 样式顺序又变（并删了实心），换 key 避免错位

    public final int[] sel = DEFAULTS.clone();

    /** 手动增益（dB），右侧上下滑动调；正=更满，负=更保守。 */
    public volatile float gainDb;

    private SharedPreferences prefs;

    public AppSettings() {
    }

    public void attach(Context ctx) {
        prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        for (int i = 0; i < COUNT; i++) {
            sel[i] = prefs.getInt(KEY + i, DEFAULTS[i]);
            // 「显示」是位掩码，不能用选项个数做范围检查
            if (i != DISPLAY && (sel[i] < 0 || sel[i] >= OPTIONS[i].length)) sel[i] = DEFAULTS[i];
        }
        gainDb = prefs.getFloat("gain_db", 0f);
        brightness = prefs.getFloat("brightness", 0.6f);
    }

    public String label(int menu) {
        return OPTIONS[menu][sel[menu]];
    }

    public synchronized void set(int menu, int value) {
        if (menu < 0 || menu >= COUNT) return;
        int n = OPTIONS[menu].length;
        if (menu == DISPLAY) {
            if (value < 0 || value > 31) return;   // 位掩码 0..31，五个开关
        } else if (value < 0 || value >= n) {
            return;
        }
        sel[menu] = value;
        if (prefs != null) prefs.edit().putInt(KEY + menu, value).apply();
    }

    /** 只改内存，不写盘（拖动时每帧都会调，写盘会卡）。抬手时调 {@link #persistGain()}。 */
    /** 增益调节范围（±dB）。原 ±24 太窄，用户要求扩大。 */
    public static final float GAIN_LIMIT_DB = 48f;

    public synchronized void setGainDb(float db) {
        if (db < -GAIN_LIMIT_DB) db = -GAIN_LIMIT_DB;
        else if (db > GAIN_LIMIT_DB) db = GAIN_LIMIT_DB;
        gainDb = db;
    }

    public void persistGain() {
        if (prefs != null) prefs.edit().putFloat("gain_db", gainDb).apply();
    }

    /** 屏幕亮度（0.03~1），随设置一起持久化。 */
    public volatile float brightness = 0.6f;

    public void setBrightness(float v) {
        if (v < 0.03f) v = 0.03f;
        else if (v > 1f) v = 1f;
        brightness = v;
    }

    public void persistBrightness() {
        if (prefs != null) prefs.edit().putFloat("brightness", brightness).apply();
    }

    /** 横坐标是否用对数显示。 */
    public boolean logAxis() {
        return sel[COORD] == 0;
    }

    // ── 派生值 ────────────────────────────────────────────────

    public float fMaxHz() {
        switch (sel[ZOOM]) {
            case 0: return 4000f;
            case 1: return 8000f;
            default: return 16000f;
        }
    }

    public boolean peakOn() {
        return sel[PEAK] != 0;
    }

    /** 峰值保持时长。0 = 「0s」档：不保持，立刻下落（下落速度另见 {@link #peakFallPerSec()}）。 */
    public float peakHoldMs() {
        switch (sel[PEAK]) {
            case 1: return 0f;        // 0s
            case 2: return 500f;
            case 4: return 2000f;
            default: return 1000f;
        }
    }

    /** 峰值下落速度（屏高/秒）。弹道模型下只作兜底。 */
    public float peakFallPerSec() {
        return sel[PEAK] == 1 ? 2.8f : 1.6f;
    }

    /** 重力加速度（归一化高度/秒²）。基准 3.0 时，从最高点落回约 0.8 秒。 */
    public float gravityAccel() {
        switch (sel[GRAVITY]) {
            case 0: return 3.0f * 0.2f;
            case 1: return 3.0f * 0.5f;
            case 3: return 3.0f * 2f;
            case 4: return 3.0f * 4f;
            default: return 3.0f;      // 1g
        }
    }

    /** 空气阻力系数（每秒的速度衰减比例）。 */
    public float dragCoef() {
        switch (sel[DRAG]) {
            case 0: return 0f;         // 无
            case 2: return 1.6f;       // 中
            case 3: return 4.0f;       // 高
            default: return 0.6f;      // 低
        }
    }

    public long sleepMs() {
        switch (sel[SLEEP]) {
            case 1: return 30000L;
            case 2: return 60000L;
            case 3: return 300000L;
            default: return 0L;
        }
    }

    public boolean autoRange() {
        return sel[RANGE] == 0;
    }

    public float manualFloorDb() {
        switch (sel[RANGE]) {
            case 1: return -60f;
            case 2: return -80f;
            case 3: return -100f;
            default: return -60f;
        }
    }
}
