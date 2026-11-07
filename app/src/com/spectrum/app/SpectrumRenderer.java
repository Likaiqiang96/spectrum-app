package com.spectrum.app;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;

import com.spectrum.app.core.Dynamics;
import com.spectrum.app.core.SpectrumFrame;
import com.spectrum.app.settings.AppSettings;
import com.spectrum.app.settings.Theme;

/**
 * 画面：横向「设备复刻」——黑底上一台白色机身，机身里是深色屏幕卡片，
 * 卡片内是（顶栏菜单 / 歌词 / 频谱），卡片下方是 VU 灯带。
 *
 * 参考图里的四层是叠在一起的（实心面积 + 柱形 + 白色包络线 + 柱顶峰值点），
 * 所以「柱形」这一档就画这四层；波形/三角/面积是各自的单层视图。
 *
 * 所有后处理（平滑、峰值、自动量程、VU）都在这里按 vsync 的 dt 推进（设计文档 §5）。
 */
public final class SpectrumRenderer {

    public interface Listener {
        void onFMaxChanged(float hz);

        void onSleepRequested();

        void onWakeRequested();

        void onModeChanged();

        /** 左侧上下滑动改屏幕亮度（0.03~1）。 */
        void onBrightnessChanged(float value);

        /** 「坐标」菜单：对数 / 线性。 */
        void onAxisChanged(boolean logAxis);
    }

    /**
     * 平滑时间常数。
     * 音源路只有 20 fps（50 ms 一帧），攻击不能太快否则能看到台阶；麦克风路 47 fps 可以更跟手。
     * 释放比原来（250 ms）快得多 —— 原来那套太"黏"，用户实测反馈"变化得太慢"。
     */
    private static final float ATTACK_MS_MIC = 8f;
    private static final float RELEASE_MS_MIC = 90f;
    private static final float ATTACK_MS_MIX = 22f;
    private static final float RELEASE_MS_MIX = 130f;

    private float attackMs() {
        return modeApplied == AppSettings.MODE_MIX ? ATTACK_MS_MIX : ATTACK_MS_MIC;
    }

    private float releaseMs() {
        return modeApplied == AppSettings.MODE_MIX ? RELEASE_MS_MIX : RELEASE_MS_MIC;
    }

    private final AppSettings settings;
    private final MenuBar menu;

    private final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bodyEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint peakPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vuPanel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vuOn = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vuOff = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vuPeak = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lyricPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path path = new Path();
    private final RectF bodyRect = new RectF();
    private final RectF cardRect = new RectF();
    private final RectF specRect = new RectF();
    private final RectF vuRect = new RectF();
    private final RectF tmp = new RectF();

    private final Dynamics.Smoother smoother = new Dynamics.Smoother();
    private final Dynamics.PeakHold peak = new Dynamics.PeakHold();
    private final Dynamics.AutoRange range = new Dynamics.AutoRange();
    private final Dynamics.VuMeter vu = new Dynamics.VuMeter();

    /**
     * 峰值点冲高：按**原始 dB 的变化速率**算（不是归一化值 —— 否则自动量程窗口一动，
     * 所有柱子一起涨，会被误判成"快速上升"，峰值点就被顶得满天飞）。
     * 600 dB/s 的瞬态 → 约 0.30 屏高的冲高。
     */
    private static final float BOOST_PER_DB_RATE = 0.0022f;   // 用户要求「抛得更高」，从 0.0009 提到 0.0022
    /** 抛出量上限（归一化）。用户要求"可以被甩出屏幕"，所以放到 0.85 —— 峰值点可飞出上边缘。 */
    private static final float MAX_BOOST = 0.85f;
    /** 死区：低于这个变化速率一律不算瞬态（环境噪声的逐帧抖动大约 15 dB/s，会被挡掉）。 */
    /**
     * 死区（dB/s）。**这个值很关键**：麦克风底噪的逐帧抖动换算成包络速率大约 30~55 dB/s，
     * 死区设 20 时几乎每帧都在触发冲高 → 峰值帽永远浮在柱顶上方，屏幕上就成了"两层峰值"。
     * 提到 70：真实打击乐瞬态（一帧跳 10~40 dB → 110~440 dB/s）照样能过，底噪被挡掉。
     */
    private static final float BOOST_DEADBAND_DB_PER_S = 70f;
    /**
     * 绝对幅度门槛：一帧（分析帧）内包络至少涨这么多 dB 才算瞬态。
     * 只卡速率是不够的 —— 96 条频带里总有几条在噪声下每帧跳几 dB（速率也能到 200+），
     * 于是帽子被随机频带不断踢起来，屏幕上就是"上面一层帽子"。真实打击乐一帧跳 15~40 dB。
     */
    private static final float BOOST_MIN_RISE_DB = 6f;
    /** 先把原始 dB 过一个 80 ms 的包络再算速率，避免"噪声抖一下峰值点就飞"。 */
    private static final float RATE_ENV_SEC = 0.08f;
    /**
     * 冲高必须"锁存"：速率是在瞬态**刚开始**最大的，而柱子要过几十~上百毫秒才涨到顶。
     * 如果直接把瞬时速率加到当前值上，等柱子涨到顶时冲高早就衰减没了 —— 峰值点最终只会等于柱高。
     * 所以升完再保持 {@link #BOOST_HOLD_SEC} 才按 {@link #BOOST_DECAY_SEC} 衰减。
     */
    private static final float BOOST_HOLD_SEC = 0.06f;
    private static final float BOOST_DECAY_SEC = 0.12f;
    private float[] boostHold = new float[0];

    private float[] rawDb = new float[AppSettings.COUNT * 6];   // 先给个够用的初值，首帧按实际带宽重建
    private float[] envDb = new float[0];
    private float[] prevEnvDb = new float[0];
    private float[] peakBoost = new float[0];
    private long lastFrameMs;
    private float[] normTarget = new float[rawDb.length];
    private int bands;
    private long lastSeq = -1L;
    private float lastRmsDb = -120f;
    private float lastBassDb = -120f;
    private boolean lastSilent;

    private LinearGradient gradient;
    private Shader specShader;
    private LinearGradient rainbowShader;
    private final android.graphics.Matrix shaderMatrix = new android.graphics.Matrix();
    private float rainbowOffset;
    private float fMinHz = 60f;
    private float[] waveBuf = new float[0];
    private float[] areaY = new float[0];
    private final android.graphics.Path areaTopPath = new android.graphics.Path();
    private float[] areaM = new float[0];
    private int waveLen;
    /** 彩虹流动速度（像素/秒）。 */
    private static final float RAINBOW_PX_PER_SEC = 320f;     // 实测约 6 秒滚完一圈（相机测速：260→7.7s, 420→4.8s）
    private static final float GLEAM_W_FRACTION = 0.18f;      // 流光高光带宽度 = 频谱宽 18%
    private static final float GLEAM_PERIOD_SEC = 3.5f;       // 高光扫过一遍的时间
    private final Paint gleamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float gleamOffset;
    private float gleamLeft;
    /** 高光最亮处的 alpha。 */
    private static final int GLEAM_MAX_ALPHA = 0x58;          // 更明显一点，才看得出「流光」
    private int colorKind = -1;
    private float fMaxApplied = -1f;
    private int styleApplied = -1;
    private int modeApplied = -1;
    private float silentMs;
    private final Paint warnPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint peakLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 峰值点提亮用的滤镜（渐变/彩虹配色时让它比填充亮一档）。 */
    private final android.graphics.ColorFilter peakLighter =
            new android.graphics.LightingColorFilter(0xFFFFFFFF, 0x505050);
    private final Paint hintBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean peakOnApplied = true;

    private float curFloor = -90f;
    private float curDenom = 50f;

    private volatile SpectrumFrame latest;
    private Listener listener;

    private int width;
    private int height;
    private volatile long lastTouchMs = SystemClock.elapsedRealtime();
    private static final long MENU_IDLE_HIDE_MS = 4000L;

    // 触摸分区：左 25% 调亮度、右 25% 调增益、中间点菜单
    private int touchRegion;
    private boolean adjusting;
    private float touchStartY;
    private float touchStartValue;
    private long adjustShownMs;
    /** 抬手后提示条还要显示一会儿，所以单独记一个"最近调过哪一侧"。 */
    private int hintRegion;

    private boolean menuVisible = true;
    private long lastMenuTouchMs = SystemClock.elapsedRealtime();
    private volatile float brightness = 0.6f;
    private int coordApplied = -1;
    private volatile boolean sleeping;
    private volatile boolean debug;
    private volatile String providerLabel = "";
    private volatile int rebuilds;

    private long lastNs;
    private final long[] frameTimes = new long[120];
    private int frameIdx;
    private int frameCount;
    private float fps;
    private float renderMs;
    private int analysisFrames;
    private int analysisFps;
    private long analysisWindowStart;

    private int touchCount;

    public SpectrumRenderer(AppSettings settings) {
        this.settings = settings;
        this.menu = new MenuBar(settings);

        bodyPaint.setColor(Theme.BODY);
        cardPaint.setColor(Theme.CARD);
        cardEdge.setStyle(Paint.Style.STROKE);
        cardEdge.setColor(0x33FFFFFF);
        cardEdge.setStrokeWidth(2f);
        bodyEdge.setStyle(Paint.Style.STROKE);
        bodyEdge.setColor(Theme.BODY_EDGE);
        bodyEdge.setStrokeWidth(3f);
        barPaint.setStyle(Paint.Style.FILL);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.5f);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setColor(0xE6FFFFFF);
        peakPaint.setColor(com.spectrum.app.settings.Theme.PEAK_DOT);
        vuPanel.setColor(0xFF0B0D10);
        vuOff.setColor(Theme.LED_OFF);
        vuPeak.setColor(com.spectrum.app.settings.Theme.PEAK_DOT);
        bassPaint.setColor(Theme.BASS_AMBER);
        hudBg.setColor(0xCC000000);
        hudPaint.setColor(0xFF7CFF9B);
        hudPaint.setTextSize(26f);
        hudPaint.setTypeface(Typeface.MONOSPACE);
        lyricPaint.setColor(Theme.TEXT);
        lyricPaint.setTextAlign(Paint.Align.CENTER);
        peakLinePaint.setColor(Theme.PEAK_DOT);
        peakLinePaint.setStyle(Paint.Style.STROKE);
        peakLinePaint.setStrokeCap(Paint.Cap.ROUND);
        peakLinePaint.setStrokeJoin(Paint.Join.ROUND);
        wavePaint.setColor(0xCC7DF9FF);      // 淡青色的示波器波形
        wavePaint.setStyle(Paint.Style.STROKE);
        wavePaint.setStrokeWidth(2f);
        wavePaint.setStrokeJoin(Paint.Join.ROUND);
        gridPaint.setColor(0x24FFFFFF);      // 很淡的横向网格线
        axisBgPaint.setColor(0xB3000000);
        axisPaint.setColor(0xE6FFFFFF);      // 更亮
        axisPaint.setFakeBoldText(true);     // 加粗：坐标数字要看得清
        axisPaint.setTextAlign(Paint.Align.CENTER);
        hintBg.setColor(0xCC000000);
        hintPaint.setColor(Theme.TEXT);
        hintPaint.setTextAlign(Paint.Align.CENTER);
        warnPaint.setColor(Theme.BASS_AMBER);
        warnPaint.setTextAlign(Paint.Align.CENTER);

        modeApplied = settings.sel[AppSettings.MODE];
        Typeface mono = Typeface.MONOSPACE;
        menu.setTypeface(mono);
        lyricPaint.setTypeface(mono);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setProviderLabel(String label) {
        providerLabel = label;
    }

    public void setRebuilds(int n) {
        rebuilds = n;
    }

    /** provider 线程调用：只存引用（3 缓冲轮转，渲染线程下一次绘制就会取走）。 */
    public void submit(SpectrumFrame f) {
        latest = f;
    }

    public boolean isSleeping() {
        return sleeping;
    }

    public void wake() {
        sleeping = false;
        lastTouchMs = SystemClock.elapsedRealtime();
    }

    public void toggleDebug() {
        debug = !debug;
    }

    public boolean isDebug() {
        return debug;
    }

    // ────────────────────────── 布局 ──────────────────────────

    public void setSize(int w, int h) {
        if (w == width && h == height) return;
        width = w;
        height = h;

        // 用户反馈「黑框和无效区域太多」：外框、内边距、条带间距全部收窄，
        // 频谱区从"卡片 38% 以下"提到"菜单行正下方"，可用高度从 414px 涨到 ~717px。
        float margin = Math.min(w, h) * 0.012f;
        bodyRect.set(margin, margin, w - margin, h - margin);

        float pad = bodyRect.height() * 0.025f;
        float vuH = bodyRect.height() * 0.10f;
        float gap = bodyRect.height() * 0.025f;
        float cardL = bodyRect.left + pad;
        float cardR = bodyRect.right - pad;
        float cardT = bodyRect.top + pad;
        float cardB = bodyRect.bottom - pad - vuH - gap;
        cardRect.set(cardL, cardT, cardR, cardB);

        float cardPadX = cardRect.width() * 0.008f;
        float yAxisW = cardRect.width() * 0.028f;      // 左侧纵坐标刻度带（放得下加粗的字）
        // 顶栏改成两行后，频谱要整体下移让位；底部再留一条横坐标刻度的位置
        // 菜单恢复一排 → 频谱上沿可以大幅上提；顶部只留一条很细的刻度条
        float specTop = cardRect.top + cardRect.height() * 0.105f;
        float specBottom = cardRect.bottom - cardRect.height() * 0.062f;
        specRect.set(cardRect.left + cardPadX + yAxisW, specTop,
                cardRect.right - cardPadX, specBottom);

        vuRect.set(cardL, cardB + gap, cardR, cardB + gap + vuH);

        float menuText = cardRect.width() * 0.021f;
        menu.layout(cardRect.left + cardPadX, cardRect.top + cardRect.height() * 0.035f,
                cardRect.right - cardPadX, menuText);
        lyricPaint.setTextSize(cardRect.width() * 0.027f);

        colorKind = -1;      // 尺寸变了，渐变要重建
        fMaxApplied = -1f;
    }

    // ────────────────────────── 每帧 ──────────────────────────

    public void render(Canvas c, long nowMs, float dtMs) {
        if (sleeping) {
            c.drawColor(Theme.BG);
            return;
        }
        if (specRect.width() <= 1f) return;      // 还没 layout（surfaceChanged 之前）
        applySettings(dtMs);
        menu.tickClose(nowMs);
        if (menuVisible && nowMs - lastMenuTouchMs > MENU_IDLE_HIDE_MS) {
            menuVisible = false;
            menu.close();
        }

        SpectrumFrame f = latest;
        if (f != null && f.seq != lastSeq) {
            lastSeq = f.seq;
            if (f.waveCount > 0) {
                if (waveBuf.length != f.wave.length) waveBuf = new float[f.wave.length];
                System.arraycopy(f.wave, 0, waveBuf, 0, f.waveCount);
                waveLen = f.waveCount;
            }
            if (bands != f.bands || rawDb.length != f.bandDb.length) {
                bands = f.bands;
                rawDb = new float[bands];
                normTarget = new float[bands];
                envDb = new float[bands];
                prevEnvDb = new float[bands];
                peakBoost = new float[bands];
                boostHold = new float[bands];
                smoother.setSize(bands);
                peak.setSize(bands);
                range.clear();
            }
            // 冲高：用原始 dB 的变化速率（每分析帧算一次）
            long nowMs0 = SystemClock.elapsedRealtime();
            float dtFrameSec = lastFrameMs == 0 ? 0.05f : (nowMs0 - lastFrameMs) / 1000f;
            lastFrameMs = nowMs0;
            if (dtFrameSec < 0.005f) dtFrameSec = 0.005f;
            float envAlpha = 1f - (float) Math.exp(-dtFrameSec / RATE_ENV_SEC);
            for (int i = 0; i < bands; i++) {
                float raw = f.bandDb[i];
                envDb[i] += (raw - envDb[i]) * envAlpha;                      // 80 ms 包络
                float riseDb = envDb[i] - prevEnvDb[i];                       // 本帧涨了多少 dB
                float rateDb = riseDb / dtFrameSec;                           // dB/s
                prevEnvDb[i] = envDb[i];
                boolean isTone = riseDb >= BOOST_MIN_RISE_DB
                        && rateDb > BOOST_DEADBAND_DB_PER_S;
                float b = isTone
                        ? (rateDb - BOOST_DEADBAND_DB_PER_S) * BOOST_PER_DB_RATE : 0f;
                if (b > MAX_BOOST) b = MAX_BOOST;
                // 锁存 + 保持 + 慢衰减
                if (b >= boostHold[i]) {
                    boostHold[i] = b;
                } else {
                    float d = (float) Math.exp(-dtFrameSec / BOOST_DECAY_SEC);
                    boostHold[i] *= d;
                    if (boostHold[i] < 0.002f) boostHold[i] = 0f;
                }
                peakBoost[i] = boostHold[i];
            }
            System.arraycopy(f.bandDb, 0, rawDb, 0, bands);
            lastRmsDb = f.rmsDb;
            lastBassDb = f.bassDb;
            lastSilent = f.silent;
            range.update(rawDb, dtMs);
            analysisFrames++;
        }
        long now = SystemClock.elapsedRealtime();
        if (analysisWindowStart == 0) analysisWindowStart = now;
        if (now - analysisWindowStart >= 1000) {
            analysisFps = analysisFrames;
            analysisFrames = 0;
            analysisWindowStart = now;
        }

        // 增益：把整个显示窗口上下平移（正 = 更满，负 = 更保守）
        float gain = settings.gainDb;
        float floor;
        float top;
        if (settings.autoRange()) {
            floor = range.floor() - gain;
            top = range.top() - gain;
        } else {
            floor = settings.manualFloorDb() - gain;
            top = 0f - gain;
        }
        float denom = Math.max(1f, top - floor);
        curFloor = floor;
        curDenom = denom;
        for (int i = 0; i < bands; i++) {
            float v = (rawDb[i] - floor) / denom * 0.9f;
            normTarget[i] = v < 0f ? 0f : (v > 1f ? 1f : v);
        }
        if (lastSilent) {
            silentMs += dtMs;
        } else {
            silentMs = 0f;
        }
        smoother.advance(normTarget, dtMs, attackMs(), releaseMs());
        peak.advance(smoother.values(), peakBoost, dtMs, settings.peakHoldMs(),
                settings.gravityAccel(), settings.dragCoef(), settings.peakFallPerSec());
        // VU 表量程按模式给：麦克风路 −45 dBFS，音源路捕获天生低约 30 dB → −75 dBFS。
        // 不加这个区分的话，音源路会恒满（用户反馈过）。
        float vuFloor = modeApplied == AppSettings.MODE_MIX ? -75f : -45f;
        vu.advance(lastRmsDb + settings.gainDb, dtMs, 12f, 220f, vuFloor);

        // 熄屏判定（菜单「休眠」）
        long sleepMs = settings.sleepMs();
        if (sleepMs > 0 && now - lastTouchMs > sleepMs && !sleeping) {
            sleeping = true;
            Listener l = listener;
            if (l != null) l.onSleepRequested();
            c.drawColor(Theme.BG);
            return;
        }

        drawBody(c);
        drawCard(c);
        if (menuVisible) menu.draw(c);
        drawSilenceHint(c);
        drawSpectrum(c);
        drawVu(c);
        drawAdjustHint(c, nowMs);
        drawHud(c);
    }

    private void applySettings(float dtMs) {
        int ck = settings.sel[AppSettings.COLOR];
        if (ck != colorKind) {
            colorKind = ck;
            if (Theme.isRainbow(ck)) {
                // 一个周期（频谱宽度）+ REPEAT 平铺，再用平移矩阵滚动。
                // ⚠️ 之前是"两倍宽度两圈 + CLAMP"：平移后左侧采样点跑到渐变范围之外，
                // 被夹成第一个色标 → 低频端永远是红色，看起来像"低频不会变色"。
                float w = Math.max(1f, specRect.width());
                int[] cols = Theme.rainbowColors(64);
                float[] pos = new float[64];
                for (int i = 0; i < pos.length; i++) pos[i] = i / (float) (pos.length - 1);
                rainbowShader = new LinearGradient(specRect.left, 0, specRect.left + w, 0,
                        cols, pos, Shader.TileMode.REPEAT);
                rainbowOffset = 0f;
                shaderMatrix.reset();
                rainbowShader.setLocalMatrix(shaderMatrix);
                barPaint.setShader(rainbowShader);
                gleamPaint.setShader(null);
                gleamPaint.setColor(0xFFFFFFFF);
            } else if (Theme.isSolid(ck)) {
                barPaint.setShader(null);
                barPaint.setColor(Theme.solid(ck));
            } else {
                int[] cols = Theme.gradient(ck);
                gradient = new LinearGradient(specRect.left, 0, specRect.right, 0, cols,
                        Theme.GRADIENT_POS, Shader.TileMode.CLAMP);
                specShader = gradient;
                barPaint.setShader(specShader);
            }
        }
        // 彩虹流动：平移 shader（复用同一个 Matrix，不分配）
        if (Theme.isRainbow(ck) && rainbowShader != null) {
            float w = Math.max(1f, specRect.width());
            rainbowOffset += RAINBOW_PX_PER_SEC * dtMs / 1000f;
            if (rainbowOffset >= w) rainbowOffset -= w;
            shaderMatrix.setTranslate(rainbowOffset, 0f);   // 正平移 → 由低频向高频流动
            rainbowShader.setLocalMatrix(shaderMatrix);
            float gw = Math.max(8f, w * GLEAM_W_FRACTION);
            float total = w + gw;
            gleamOffset += total / GLEAM_PERIOD_SEC * dtMs / 1000f;
            if (gleamOffset >= total) gleamOffset -= total;
            gleamLeft = specRect.left - gw + gleamOffset;
        }
        float wantF = settings.fMaxHz();
        if (wantF != fMaxApplied) {
            fMaxApplied = wantF;
            Listener l = listener;
            if (l != null) l.onFMaxChanged(wantF);
        }
        styleApplied = settings.sel[AppSettings.STYLE];
        peakOnApplied = settings.peakOn();
        int coord = settings.sel[AppSettings.COORD];
        if (coord != coordApplied) {
            coordApplied = coord;
            Listener l = listener;
            if (l != null) l.onAxisChanged(coord == 0);
        }
        int mode = settings.sel[AppSettings.MODE];
        if (mode != modeApplied) {
            modeApplied = mode;
            Listener l = listener;
            if (l != null) l.onModeChanged();
        }
    }

    // ────────────────────────── 绘制 ──────────────────────────

    private void drawBody(Canvas c) {
        c.drawColor(Theme.BG);
        float r = bodyRect.height() * 0.06f;
        c.drawRoundRect(bodyRect, r, r, bodyPaint);
        c.drawRoundRect(bodyRect, r, r, bodyEdge);
    }

    private void drawCard(Canvas c) {
        float r = cardRect.height() * 0.03f;
        c.drawRoundRect(cardRect, r, r, cardPaint);
        c.drawRoundRect(cardRect, r, r, cardEdge);
    }

    /**
     * 音源模式的静音兜底提示：连续 3 秒全 0 就告诉用户"不是 App 坏了"。
     * （歌词功能已按用户要求整体删除，这里只留这条提示。）
     */
    private void drawSilenceHint(Canvas c) {
        if (settings.sel[AppSettings.MODE] != AppSettings.MODE_MIX || silentMs <= 3000f) return;
        warnPaint.setTextSize(cardRect.width() * 0.019f);
        c.drawText("媒体音量为 0，请调大音量", cardRect.centerX(),
                specRect.top + specRect.height() * 0.5f, warnPaint);
    }

    /** 横坐标下限（Hz），由 provider 报上来。 */
    public void setFMin(float hz) {
        if (hz > 0f && hz != fMinHz) {
            fMinHz = hz;
        }
    }

    /**
     * 画横纵坐标刻度：横轴频率（按当前对数/线性映射）、纵轴电平 dB
     * （按当前量程与柱高映射算，所以和柱子高度严格对应）。
     */
    private void drawAxes(Canvas c, float barSpanH) {
        float left = specRect.left;
        float right = specRect.right;
        float base = specRect.bottom;
        float spanH = specRect.height();
        float fMax = settings.fMaxHz();
        boolean log = settings.logAxis();
        int dispMask = settings.sel[AppSettings.DISPLAY];
        boolean showX = (dispMask & 1) != 0;      // 横坐标数字
        boolean showY = (dispMask & 2) != 0;      // 纵坐标数字
        boolean showTick = (dispMask & 4) != 0;   // 刻度线
        boolean showGrid = (dispMask & 8) != 0;   // 图像内部的横线（最底层）
        peakLinePaint.setStrokeWidth(Math.max(2f, (right - left) / 144f * 0.14f));
        float fs = cardRect.width() * 0.0148f;   // 字号也放大一点
        axisPaint.setTextSize(fs);

        // ── 横轴：图像**下方**一行刻度 ──
        float tickY = base + fs * 2.05f;
        for (int i = 0; i <= 5; i++) {
            float t = i / 5f;
            // 对数档下限是 fMin（不可能到 0）；线性档从 0 Hz 起
            float f = log
                    ? (float) (fMinHz * Math.pow(fMax / (double) fMinHz, t))
                    : fMax * t;
            float x = left + t * (right - left);
            if (showTick) {
                tmp.set(x - 1f, base + fs * 0.30f, x + 1f, base + fs * 1.05f);
                c.drawRect(tmp, axisPaint);
            }
            if (!showX) continue;
            String label = f >= 1000f
                    ? String.format(java.util.Locale.US, "%.0fk", f / 1000f)
                    : String.format(java.util.Locale.US, "%.0f", f);
            c.drawText(label, x, tickY, axisPaint);
        }

        // ── 纵轴：5 个电平刻度（贴着频谱左缘）──
        float denom = Math.max(1f, curDenom);
        float floorDb = curFloor;
        float topDb = curFloor + curDenom;
        for (int i = 0; i <= 4; i++) {
            float db = floorDb + (topDb - floorDb) * i / 4f;
            float norm = (db - floorDb) / denom * 0.9f;
            float y = base - norm * barSpanH;
            if (y < specRect.top || y > base) continue;
            if (showTick) {
                tmp.set(left - fs * 0.18f, y, left, y + 1.6f);
                c.drawRect(tmp, axisPaint);
            }
            if (!showY) continue;
            String label = String.format(java.util.Locale.US, "%.0f", db);
            // 右对齐到频谱左缘外侧，完全不压图像；只留一个很短的空隙
            axisPaint.setTextAlign(Paint.Align.RIGHT);
            c.drawText(label, left - fs * 0.25f, y + fs * 0.34f, axisPaint);
            axisPaint.setTextAlign(Paint.Align.CENTER);
        }
    }

    /**
     * 图像内部的横线（纵坐标刻度对应的贯穿线），**画在所有内容之前**，
     * 所以柱子/面积会盖在它上面 —— 也就是位于图层最底部（用户要求）。
     */
    private void drawGrid(Canvas c, float barSpanH) {
        float left = specRect.left;
        float right = specRect.right;
        float base = specRect.bottom;
        float denom = Math.max(1f, curDenom);
        float floorDb = curFloor;
        float topDb = curFloor + curDenom;
        for (int i = 0; i <= 4; i++) {
            float db = floorDb + (topDb - floorDb) * i / 4f;
            float norm = (db - floorDb) / denom * 0.9f;
            float y = base - norm * barSpanH;
            if (y < specRect.top || y > base) continue;
            tmp.set(left, y - 0.8f, right, y + 0.8f);
            c.drawRect(tmp, gridPaint);
        }
    }

    /** 时域波形叠加：以频谱区域中线为 0 线，按峰值自动放大（上限 40 倍）。 */
    private void drawWaveOverlay(Canvas c) {
        float left = specRect.left;
        float right = specRect.right;
        // 画在**屏幕顶部区域**（频谱上沿那条空带，也就是纵坐标最上面那条刻度线以上）
        float bandH = specRect.height() * 0.20f;
        float cy = specRect.top + bandH * 0.5f;
        float amp = bandH * 0.46f;
        // 时基：5ms / 10ms / 20ms → 点数（按 48k 估算；两个音源差别只有 8%）
        int ms;
        switch (settings.sel[AppSettings.TIMEBASE]) {
            case 0:  ms = 5;  break;
            case 2:  ms = 20; break;
            default: ms = 10; break;
        }
        int want = Math.min(waveLen, Math.max(32, ms * 48));
        int from = waveLen - want;              // 显示**最新**的一段（滚动示波器）
        float peak = 0f;
        for (int i = from; i < waveLen; i++) {
            float a = Math.abs(waveBuf[i]);
            if (a > peak) peak = a;
        }
        float gain = peak > 1e-4f ? Math.min(40f, 0.85f / peak) : 1f;
        path.reset();
        for (int i = from; i < waveLen; i++) {
            float x = left + (right - left) * (i - from) / (float) Math.max(1, want - 1);
            float y = cy - waveBuf[i] * gain * amp;
            if (i == from) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        c.drawPath(path, wavePaint);
    }

    /** 面积填充那条单调三次曲线在段 i 上、参数 t∈[0,1] 处的高度。 */
    private float areaCurveY(int i, float t, float dx) {
        if (areaY.length == 0 || i < 0 || i >= areaY.length - 1) return 0f;
        float y0 = areaY[i];
        float y1 = areaY[i + 1];
        float m0 = areaM[i];
        float m1 = areaM[i + 1];
        float t2 = t * t;
        float t3 = t2 * t;
        return (2 * t3 - 3 * t2 + 1) * y0
             + (t3 - 2 * t2 + t) * m0 * dx
             + (-2 * t3 + 3 * t2) * y1
             + (t3 - t2) * m1 * dx;
    }

    private void drawSpectrum(Canvas c) {
        if (bands <= 0) return;
        float[] v = smoother.values();
        float[] p = peak.values();

        float left = specRect.left;
        float right = specRect.right;
        float base = specRect.bottom;
        float spanH = specRect.height();
        // 柱形/面积/包络线只占 86% 高度，顶部 14% 留给"被抛出"的峰值点 ——
        // 否则柱子一顶到天花板，峰值点就没地方去了（用户反馈：峰值等于柱高，没有被抛出的感觉）。
        float barSpanH = spanH * 0.86f;
        float bandW = (right - left) / bands;
        int style = styleApplied;

        // 样式分层：
        //   0 柱形 = 面积 + 柱（**无包络线**，用户 2026-11-07 要求去掉）  1 波形 = 只包络线
        //   2 三角 = 三角柱                  3 面积 = 面积 + 包络线
        //   4 密线 = 只密集谱线，无包络      5 实心 = 只实心包络，无谱线
        //   6 疏线 = 只稀疏谱线，无包络
        // 序号（2026-11-07 最终顺序：柱形 疏线 三角 波形 面积，实心已删）：
        //   0 柱形 = 密集谱线（无面积、无包络）   1 疏线 = 稀疏细谱线
        //   2 三角 = 三角柱                       3 波形 = 只包络线
        //   4 面积 = 面积 + 包络线
        boolean drawArea = style == 4;
        boolean drawBars = style == 0 || style == 1;
        boolean drawTri = style == 2;
        boolean drawLine = style == 3;
        float barFactor = style == 0 ? 0.94f : (style == 1 ? 0.26f : 0.62f);
        float barW = bandW * barFactor;

        // ⓪ 图像内部横线：最先画 → 位于图层最底部，会被柱子/面积/曲线覆盖
        if ((settings.sel[AppSettings.DISPLAY] & 8) != 0) {
            drawGrid(c, barSpanH);
        }
        float radius = style == 0 ? barW * 0.12f : barW * 0.28f;
        int areaAlpha = 215;

        // ① 面积填充（「柱形」「面积」「实心」）
        if (drawArea) {
            path.reset();
            path.moveTo(left, base);
            float prevX = left;
            float prevY = base - v[0] * barSpanH;
            path.lineTo(left, prevY);
            // 面积上沿用**单调三次插值**（Fritsch–Carlson）：
            //   · 圆滑（不像直线那样尖尖的）
            //   · 不会过冲（曲线永远不高于相邻两个 band 顶点）→ 峰值点不会被包进填充里
            if (areaY.length != bands) areaY = new float[bands];
            if (areaM.length != bands) areaM = new float[bands];
            for (int i = 0; i < bands; i++) areaY[i] = base - v[i] * barSpanH;
            for (int i = 0; i < bands; i++) {
                float dm = (i == 0) ? 0f : (areaY[i] - areaY[i - 1]) / bandW;
                float dp = (i == bands - 1) ? 0f : (areaY[i + 1] - areaY[i]) / bandW;
                if (dm * dp <= 0f) {
                    areaM[i] = 0f;                       // 极值点/方向反转 → 水平切线
                } else {
                    float m = (dm + dp) * 0.5f;
                    float lim = 3f * Math.min(Math.abs(dm), Math.abs(dp));
                    if (Math.abs(m) > lim) m = Math.signum(m) * lim;
                    areaM[i] = m;
                }
            }
            for (int i = 0; i < bands - 1; i++) {
                float x0 = left + (i + 0.5f) * bandW;
                float x1 = left + (i + 1.5f) * bandW;
                float y0 = areaY[i];
                float y1 = areaY[i + 1];
                float third = (x1 - x0) / 3f;
                path.cubicTo(x0 + third, y0 + areaM[i] * third,
                        x1 - third, y1 - areaM[i + 1] * third, x1, y1);
            }
            // 顶部曲线单独存一条 Path：填充用它闭合成面，描边（包络线）也直接用它
            areaTopPath.reset();
            areaTopPath.moveTo(left, areaY[0]);
            for (int i = 0; i < bands - 1; i++) {
                float x0 = left + (i + 0.5f) * bandW;
                float x1 = left + (i + 1.5f) * bandW;
                float y0 = areaY[i];
                float y1 = areaY[i + 1];
                float third = (x1 - x0) / 3f;
                areaTopPath.cubicTo(x0 + third, y0 + areaM[i] * third,
                        x1 - third, y1 - areaM[i + 1] * third, x1, y1);
            }
            areaTopPath.lineTo(right, areaY[bands - 1]);

            path.reset();
            path.addPath(areaTopPath);
            path.lineTo(right, base);
            path.lineTo(left, base);
            path.close();
            barPaint.setAlpha(areaAlpha);
            c.drawPath(path, barPaint);
            barPaint.setAlpha(255);
            if (style == 4) {
                c.drawPath(areaTopPath, linePaint);   // 面积档也画包络线（和波形档一致）
            }
        }

        // ② 柱形 / 谱线（「柱形」「密线」「疏线」）
        if (drawBars) {
            for (int i = 0; i < bands; i++) {
                float h = v[i] * barSpanH;
                if (h < 1f) continue;
                float x = left + i * bandW + (bandW - barW) / 2f;
                tmp.set(x, base - h, x + barW, base);
                c.drawRoundRect(tmp, radius, radius, barPaint);
            }
        }

        // ③ 三角
        if (drawTri) {
            for (int i = 0; i < bands; i++) {
                float h = v[i] * barSpanH;
                if (h < 1f) continue;
                float cx = left + (i + 0.5f) * bandW;
                path.reset();
                path.moveTo(cx, base - h);
                path.lineTo(cx - barW * 0.75f, base);
                path.lineTo(cx + barW * 0.75f, base);
                path.close();
                c.drawPath(path, barPaint);
            }
        }

        // ④ 白色包络线（「柱形」「波形」「面积」；「密线/实心/疏线」不画）
        if (drawLine) {
            path.reset();
            float prevX = left;
            float prevY = base - v[0] * barSpanH;
            path.moveTo(left, prevY);
            for (int i = 1; i < bands; i++) {
                float x = left + (i + 0.5f) * bandW;
                float y = base - v[i] * barSpanH;
                float mx = (prevX + x) / 2f;
                float my = (prevY + y) / 2f;
                path.quadTo(prevX, prevY, mx, my);
                prevX = x;
                prevY = y;
            }
            path.lineTo(right - bandW * 0.5f, base - v[bands - 1] * barSpanH);
            c.drawPath(path, linePaint);
        }

        // ④′ 流光：一道柔光扫过。用"每根柱子按位置算透明度"实现 —— 不做路径裁剪
        //（硬件层上 clipPath 复杂路径很贵，实测会把渲染拉到 16 ms / 掉到 49 fps）。
        if (drawBars && Theme.isRainbow(settings.sel[AppSettings.COLOR])) {
            float gw = Math.max(8f, (right - left) * GLEAM_W_FRACTION);
            for (int i = 0; i < bands; i++) {
                float h = v[i] * barSpanH;
                if (h < 1f) continue;
                float x = left + i * bandW + (bandW - barW) / 2f;
                float u = (x + barW / 2f - gleamLeft) / gw;
                if (u <= 0f || u >= 1f) continue;
                gleamPaint.setAlpha((int) (GLEAM_MAX_ALPHA * Math.sin(Math.PI * u)));
                tmp.set(x, base - h, x + barW, base);
                c.drawRoundRect(tmp, radius, radius, gleamPaint);
            }
        }

        // ⑥ 横纵坐标刻度
        drawAxes(c, barSpanH);

        // ⑦ 当前声波（时域波形），直接叠加在频谱图层之上
        if ((settings.sel[AppSettings.DISPLAY] & 16) != 0 && waveLen > 1) {
            drawWaveOverlay(c);
        }

        // ⑤ 峰值点。形状按样式走（用户 2026-10-17 要求）：
        //   · 柱形/密线/疏线：**和下方谱线一样宽的小矩形**（像给柱子戴个帽）
        //   · 波形：包络是连续的，点也**加密一倍**（相邻带之间插值），看起来才连贯
        //   · 其它（三角/面积/实心）：小方块
        if (peakOnApplied) {
            // 峰值颜色**跟随频谱配色**：直接复用柱子/面积用的同一个 shader（含流光溢彩的滚动）
            peakPaint.setShader(barPaint.getShader());
            peakLinePaint.setShader(barPaint.getShader());
            // 单色配色时没有 shader，得把颜色也同步过来，否则峰值还是那个固定的淡蓝色
            if (barPaint.getShader() != null) {
                // 渐变/彩虹：用滤镜整体提亮，保证峰值点从填充里跳出来
                peakPaint.setColorFilter(peakLighter);
                peakLinePaint.setColorFilter(peakLighter);
            } else {
                peakPaint.setColorFilter(null);
                peakLinePaint.setColorFilter(null);
            }
            if (barPaint.getShader() == null) {
                // 单色配色：颜色跟频谱一致，但**提亮 55%**，否则和半透明填充糊在一起看不见
                int pk = barPaint.getColor();
                int r = (pk >> 16) & 0xFF, g = (pk >> 8) & 0xFF, bl = pk & 0xFF;
                r += (255 - r) * 55 / 100;
                g += (255 - g) * 55 / 100;
                bl += (255 - bl) * 55 / 100;
                int lit = 0xFF000000 | (r << 16) | (g << 8) | bl;
                peakPaint.setColor(lit);
                peakLinePaint.setColor(lit);
            }
            if (drawBars) {
                // 柱形/疏线：**每带一个、和谱线等宽的矩形**（保留最初的表示，用户 2026-11-07 要求恢复）。
                // 只在确实抛离柱子时才画，否则帽贴着柱顶会看成"同一频率两个点"。
                float capH = Math.max(5f, bandW * 0.12f);
                for (int i = 0; i < bands; i++) {
                    float y = base - p[i] * barSpanH;
                    if (y >= base - 1f) continue;
                    float barTop = base - v[i] * barSpanH;
                    if (barTop - y < capH * 1.1f) continue;        // 没抛起来就不画
                    float x = left + i * bandW + (bandW - barW) / 2f;
                    tmp.set(x, y - capH / 2f, x + barW, y + capH / 2f);
                    c.drawRect(tmp, peakPaint);
                }
            } else if (style == 3) {   // 波形档：峰值画连续曲线
                // 波形档：峰值画成**连续曲线**（用户 2026-11-07 要求），用与包络线同样的圆滑连接
                if (bands > 0) {
                    path.reset();
                    float px = left;
                    float py = base - p[0] * barSpanH;
                    path.moveTo(px, py);
                    for (int i = 1; i < bands; i++) {
                        float x = left + i * bandW;
                        float y = base - p[i] * barSpanH;
                        float mx = (px + x) / 2f;
                        float my = (py + y) / 2f;
                        path.quadTo(px, py, mx, my);
                        px = x;
                        py = y;
                    }
                    path.lineTo(px, py);
                    c.drawPath(path, peakLinePaint);
                }
            } else if (style == 4) {   // 面积档：峰值与波形档一样画**连续曲线**
                if (bands > 0) {
                    path.reset();
                    float px = left;
                    float py = base - p[0] * barSpanH;
                    path.moveTo(px, py);
                    for (int i = 1; i < bands; i++) {
                        float x = left + i * bandW;
                        float y = base - p[i] * barSpanH;
                        float mx = (px + x) / 2f;
                        float my = (py + y) / 2f;
                        path.quadTo(px, py, mx, my);
                        px = x;
                        py = y;
                    }
                    path.lineTo(px, py);
                    c.drawPath(path, peakLinePaint);
                }
            } else if (false) {   // 旧的点阵分支（已停用）
                // 面积档：峰值标记**与频谱一一对应**（每带 1 个），宽度就取一根柱子的宽度，
                // 高度固定 —— 不再自定义点的大小（用户 2026-11-07 要求）
                float capW = Math.max(3f, bandW * barFactor);
                float capH = Math.max(4f, bandW * 0.22f);
                for (int i = 0; i < bands; i++) {
                    float yRaw = base - p[i] * barSpanH;
                    if (style == 4) {
                        float fy = areaCurveY(i, 0.5f, bandW);
                        if (fy > 0f && fy < yRaw) yRaw = fy;
                    }
                    float y = yRaw - capH;          // 整个标记压在峰值之上
                    if (y >= base - 1f) continue;
                    float x = left + (i + 0.5f) * bandW;
                    tmp.set(x - capW / 2f, y, x + capW / 2f, y + capH);
                    c.drawRect(tmp, peakPaint);
                }
            } else {
                // 三角 / 实心：**每带插 3 个**小方块，连成细点线。
                // （用户要求：面积档的峰值点密度要跟波形一致 —— 原来这里是每带 1 个，太稀。）
                float s = Math.max(2.5f, bandW * 0.16f);
                int sub = 3;
                for (int i = 0; i < bands; i++) {
                    float pv = p[i];
                    float pn = p[i + 1 < bands ? i + 1 : i];
                    for (int k = 0; k < sub; k++) {
                        float t = k / (float) sub;
                        float val = pv + (pn - pv) * t;
                        float y = base - val * barSpanH;
                        if (y >= base - 1f) continue;
                        float x = left + (i + t) * bandW;
                        if (x > right - s) continue;                 // 别画到频谱右边界之外
                        tmp.set(x - s / 2f, y - s / 2f, x + s / 2f, y + s / 2f);
                        c.drawRect(tmp, peakPaint);
                    }
                }
            }
        }

    }

    /** 屏下 LED 电平表：密集的**竖条**并排（用户要求）。 */
    private void drawVu(Canvas c) {
        float r = vuRect.height() * 0.22f;
        c.drawRoundRect(vuRect, r, r, vuPanel);
        int n = Dynamics.VuMeter.SEGMENTS;
        float padX = vuRect.width() * 0.010f;
        float slot = (vuRect.width() - padX * 2) / n;
        float barW = slot * 0.66f;
        float top = vuRect.top + vuRect.height() * 0.10f;
        float bottom = vuRect.bottom - vuRect.height() * 0.10f;
        int lit = vu.litSegments();
        int peakIdx = vu.peakSegment();
        for (int i = 0; i < n; i++) {
            float x = vuRect.left + padX + i * slot + (slot - barW) / 2f;
            tmp.set(x, top, x + barW, bottom);
            boolean on = i < lit;
            vuOn.setColor(Theme.ledColor(i, n));
            c.drawRoundRect(tmp, barW * 0.30f, barW * 0.30f, on ? vuOn : vuOff);
            if (on) {
                vuOn.setAlpha(70);
                RectF g = new RectF(tmp);
                g.inset(-barW * 0.22f, -barW * 0.22f);
                c.drawRoundRect(g, barW * 0.4f, barW * 0.4f, vuOn);
                vuOn.setAlpha(255);
            }
        }
        // 峰值：在最高处那一段上叠一根更亮的细条
        if (peakIdx >= 0 && peakIdx < n) {
            float x = vuRect.left + padX + peakIdx * slot + (slot - barW) / 2f;
            tmp.set(x, top, x + barW, bottom);
            c.drawRoundRect(tmp, barW * 0.30f, barW * 0.30f, vuPeak);
        }
    }

    private void drawHud(Canvas c) {
        if (!debug) return;
        float lh = 32f;
        float x = cardRect.left + 30f;
        float y = cardRect.top + cardRect.height() * 0.33f;
        // 调试面板垫一层半透明底；放在菜单和频谱之间的空白处，别挡菜单
        tmp.set(x - 16f, y - lh, x + 1150f, y + lh * 3f + 10f);
        Paint bg = hudBg;
        c.drawRoundRect(tmp, 10f, 10f, bg);
        c.drawText(String.format(java.util.Locale.US, "fps %.1f  render %.2fms  frames %d",
                fps, renderMs, frameCount), x, y, hudPaint);
        y += lh;
        c.drawText(String.format(java.util.Locale.US,
                "analysis %d fps  bands %d  fMax %.0fHz", analysisFps, bands, fMaxApplied),
                x, y, hudPaint);
        y += lh;
        c.drawText(String.format(java.util.Locale.US,
                "rms %.1f dBFS  bass %.1f dB  floor %.0f  p95 %.0f  span %.0f  p10 %.0f",
                lastRmsDb, lastBassDb, range.floor(), range.p95(), range.span(),
                range.p10()), x, y, hudPaint);
        y += lh;
        c.drawText(String.format(java.util.Locale.US,
                "src %s  gain %+.0fdB  bright %.0f%%  axis %s  silent %.1fs",
                providerLabel, settings.gainDb, brightness * 100,
                settings.logAxis() ? "log" : "lin", silentMs / 1000f), x, y, hudPaint);
    }

    // ────────────────────────── 触摸 ──────────────────────────

    /** 手指按下：先让菜单显形（隐藏状态下第一下只负责唤醒菜单）。 */
    public void onTouchDown(float x, float y) {
        lastTouchMs = SystemClock.elapsedRealtime();
        touchCount++;
        if (sleeping) {
            sleeping = false;
            Listener l = listener;
            if (l != null) l.onWakeRequested();
            touchRegion = 0;
            return;
        }
        touchStartY = y;
        adjusting = false;
        if (!menuVisible) {
            menuVisible = true;      // 顺手把菜单唤醒，但**不影响**左右两侧的滑动分区
        }
        lastMenuTouchMs = SystemClock.elapsedRealtime();
        // 菜单行那一条、以及子菜单展开时，都优先给菜单
        // （否则左侧滑动区会把「模式 / 样式」和子菜单选项盖掉，点不开）
        float menuStripBottom = cardRect.top + cardRect.height() * 0.17f;
        if (y < menuStripBottom || menu.isOpen()) {
            touchRegion = 0;
        } else if (x > width * 0.08f && x < width * 0.32f) {
            touchRegion = 1;                       // 左侧：亮度
        } else if (x > width * 0.68f && x < width * 0.92f) {
            touchRegion = 2;                       // 右侧：增益
        } else {
            // 注意：左右各留 8% 不放手势区 —— 否则滑动会同时被系统的边缘手势
            //（Flyme 的侧边返回）识别，App 收到事件的同时系统把 Activity 送回后台。
            touchRegion = 0;
        }
    }

    /** 手指移动：左右两侧上下滑动分别调亮度 / 增益。 */
    public void onTouchMove(float x, float y) {
        if (touchRegion == 0) return;
        float dy = touchStartY - y;
        if (!adjusting) {
            if (Math.abs(dy) < height * 0.02f) return;
            adjusting = true;
            touchStartValue = (touchRegion == 1) ? brightness : settings.gainDb;
            touchStartY = y;
            return;
        }
        // 注意：必须**在当前值上累加**本帧增量。之前写成 touchStartValue + 增量，
        // 每次 MOVE 都在同一个基准上覆盖，等于只有最后一小段位移生效 —— 这就是"不灵敏"的根因。
        float step = (touchStartY - y) / (height * 0.6f);
        touchStartY = y;
        if (touchRegion == 1) {
            float v = brightness + step;
            if (v < 0.03f) v = 0.03f;
            else if (v > 1f) v = 1f;
            brightness = v;
            settings.setBrightness(v);          // 记到设置里（内存），抬手时落盘
            Listener l = listener;
            if (l != null) l.onBrightnessChanged(v);
        } else {
            settings.setGainDb(settings.gainDb + step * 48f);
        }
        adjustShownMs = SystemClock.elapsedRealtime();
        hintRegion = touchRegion;
    }

    /** 手指抬起：只有中间区域、且没有滑动过，才算点菜单。 */
    public void onTouchUp(float x, float y) {
        if (!adjusting && touchRegion == 0) {
            menu.onTouch(x, y);
            fMaxApplied = -1f;      // 触发一次 setFMax 同步
        }
        if (adjusting && touchRegion == 2) {
            settings.persistGain();        // 拖动过程只改内存，抬手才落盘
        }
        if (adjusting && touchRegion == 1) {
            settings.persistBrightness();
        }
        adjusting = false;
        touchRegion = 0;
        lastMenuTouchMs = SystemClock.elapsedRealtime();
    }

    public void setBrightness(float v) {
        brightness = v;
    }

    public float brightness() {
        return brightness;
    }

    /** 滑动调节时在屏幕上给个提示条。 */
    private void drawAdjustHint(Canvas c, long nowMs) {
        long age = nowMs - adjustShownMs;
        if (age < 0 || age > 2500 || hintRegion == 0) return;
        float alpha = age > 2100 ? Math.max(0f, (2500 - age) / 400f) : 1f;
        float w = cardRect.width() * 0.30f;
        float h = cardRect.height() * 0.10f;
        float left = cardRect.left + (cardRect.width() - w) / 2f;
        float top = cardRect.top + cardRect.height() * 0.19f;   // 挪到菜单行下面
        tmp.set(left, top, left + w, top + h);
        hintBg.setAlpha((int) (200 * alpha));
        c.drawRoundRect(tmp, 12f, 12f, hintBg);
        boolean isGain = hintRegion == 2;
        String label = isGain
                ? String.format(java.util.Locale.US, "增益 %+.1f dB", settings.gainDb)
                : String.format(java.util.Locale.US, "亮度 %.0f%%", brightness * 100);
        hintPaint.setAlpha((int) (255 * alpha));
        hintPaint.setTextSize(h * 0.42f);
        c.drawText(label, tmp.centerX(), tmp.centerY() + h * 0.14f, hintPaint);
        // 进度条
        float barW = w * 0.82f;
        float barH = h * 0.16f;
        float bl = tmp.centerX() - barW / 2f;
        float bt = tmp.bottom - h * 0.30f;
        tmp.set(bl, bt, bl + barW, bt + barH);
        c.drawRoundRect(tmp, barH / 2f, barH / 2f, hintBg);
        float frac;
        if (isGain) {
            frac = (settings.gainDb + 24f) / 48f;
        } else {
            frac = (brightness - 0.03f) / 0.97f;
        }
        if (frac < 0f) frac = 0f;
        else if (frac > 1f) frac = 1f;
        tmp.set(bl, bt, bl + barW * frac, bt + barH);
        c.drawRoundRect(tmp, barH / 2f, barH / 2f, hintPaint);
    }

    // ────────────────────────── 统计 ──────────────────────────

    public void noteFrame(long nowNs, long drawStartNs, long drawEndNs) {
        if (lastNs != 0) {
            long dt = nowNs - lastNs;
            frameTimes[frameIdx] = dt;
            frameIdx = (frameIdx + 1) % frameTimes.length;
            if (frameCount < frameTimes.length) frameCount++;
            long sum = 0;
            for (int i = 0; i < frameCount; i++) sum += frameTimes[i];
            if (sum > 0) fps = (float) (frameCount * 1e9 / sum);
        }
        lastNs = nowNs;
        renderMs = (drawEndNs - drawStartNs) / 1e6f;
    }

    public float fps() {
        return fps;
    }

    /** 一行状态，给 logcat 和调试面板用。 */
    public String status() {
        return String.format(java.util.Locale.US,
                "fps=%.1f render=%.2fms analysis=%dfps bands=%d rms=%.1fdB bass=%.1fdB"
                        + " floor=%.0f p95=%.0f gain=%+.1fdB bright=%.0f%% axis=%s"
                        + " src=%s rebuilds=%d touches=%d sleeping=%s debug=%s",
                fps, renderMs, analysisFps, bands, lastRmsDb, lastBassDb,
                range.floor(), range.p95(), settings.gainDb, brightness * 100,
                settings.logAxis() ? "log" : "lin",
                providerLabel, rebuilds, touchCount, sleeping, debug);
    }
}
