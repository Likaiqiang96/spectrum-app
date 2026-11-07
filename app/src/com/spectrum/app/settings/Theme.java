package com.spectrum.app.settings;

/**
 * 配色。默认那套「青红」是按参考图取的：色相只跟**频率（横向位置）**走，不跟电平走
 * —— 照片里低频那座大山是青的、右端平尾是红的，就是证据。
 */
public final class Theme {

    /** 每套 5 个色标，对应频谱区横向 0 / 0.25 / 0.5 / 0.75 / 1.0。 */
    private static final int[][] GRADIENTS = {
            {0xFF00E5FF, 0xFF2E6BFF, 0xFF8A5CFF, 0xFFE040C0, 0xFFFF3B30},   // 青红渐变（参考图）
            {0xFF22C55E, 0xFFA3E635, 0xFFF59E0B, 0xFFF97316, 0xFFEF4444},   // 绿黄红
            {0xFFFFFFFF, 0xFFF2F2F2, 0xFFE2E2E2, 0xFFD2D2D2, 0xFFBEBEBE},   // 纯白
            {0xFF00E5FF, 0xFF39C0FF, 0xFF7A9BFF, 0xFFB87CFF, 0xFFE060C0},   // 自定义（暂用冷色）
    };

    public static final int LED_GREEN = 0xFF22C55E;
    public static final int LED_AMBER = 0xFFF59E0B;
    public static final int LED_RED = 0xFFEF4444;
    public static final int BASS_AMBER = 0xFFFFB300;
    /**
     * 峰值点 / VU 峰值标记。
     * 注意不能直接用白色：这台机器的 Flyme 夜间模式会把"实心浅色块"改写成深色
     * （实测白色机身、白色峰值点都被吃掉；文字和细线不受影响）。
     * 用饱和一点的亮青就稳定了。
     */
    public static final int PEAK_DOT = 0xFF8BEAFF;

    public static final int BG = 0xFF000000;
    public static final int BODY = 0xFF000000;      // 深色机身（用户选定深色方向）
    public static final int CARD = 0xFF05070A;
    public static final int BODY_EDGE = 0x2EFFFFFF;  // 机身描边，保证轮廓在深色下也清楚
    public static final int LED_OFF = 0xFF141414;
    public static final int MENU_HL = 0xFFC81E1E;
    public static final int TEXT = 0xFFFFFFFF;
    public static final int TEXT_DIM = 0xFFB08968;

    /** 频谱渐变停留在一个维度上：横向位置 = 频率。 */
    public static final float[] GRADIENT_POS = {0f, 0.25f, 0.5f, 0.75f, 1f};

    private Theme() {
    }

    /** 单色档的取色表（颜色菜单第 4 项起）。 */
    private static final int[] SOLIDS = {0xFFFF3B30, 0xFF22C55E, 0xFF2E6BFF, 0xFFB87CFF};

    public static boolean isRainbow(int kind) {
        return kind == 3;
    }

    public static boolean isSolid(int kind) {
        return kind >= 4;
    }

    public static int solid(int kind) {
        int i = kind - 4;
        if (i < 0) i = 0;
        if (i >= SOLIDS.length) i = SOLIDS.length - 1;
        return SOLIDS[i];
    }

    /**
     * 彩虹流动用的色标：两圈完整色相，配合"两倍宽度 + 平移矩阵"实现无缝滚动。
     */
    public static int[] rainbowColors(int n) {
        // 只做**一圈**（首尾同色），配合 TileMode.REPEAT 无缝平铺 ——
        // 这样把渐变任意平移都不会出现"越界被 CLAMP 成第一个颜色"的死区。
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            float hue = (i / (float) (n - 1)) * 360f;      // 0..360° = 一圈
            out[i] = hsvToRgb(hue % 360f, 0.88f, 1f);      // 略降饱和、提亮，更「溢彩」
        }
        return out;
    }

    private static int hsvToRgb(float h, float s, float v) {
        float c = v * s;
        float x = c * (1f - Math.abs((h / 60f) % 2f - 1f));
        float m = v - c;
        float r;
        float g;
        float b;
        if (h < 60) {
            r = c; g = x; b = 0;
        } else if (h < 120) {
            r = x; g = c; b = 0;
        } else if (h < 180) {
            r = 0; g = c; b = x;
        } else if (h < 240) {
            r = 0; g = x; b = c;
        } else if (h < 300) {
            r = x; g = 0; b = c;
        } else {
            r = c; g = 0; b = x;
        }
        return 0xFF000000
                | ((int) ((r + m) * 255f + 0.5f) << 16)
                | ((int) ((g + m) * 255f + 0.5f) << 8)
                | (int) ((b + m) * 255f + 0.5f);
    }

    public static int[] gradient(int kind) {
        if (kind < 0 || kind >= GRADIENTS.length) return GRADIENTS[0];
        return GRADIENTS[kind];
    }

    /** VU 灯带每段的颜色：下 8 绿、中 3 琥珀、上 3 红。 */
    public static int ledColor(int segment, int total) {
        float t = total <= 1 ? 0f : (float) segment / (total - 1);
        if (t < 0.60f) return LED_GREEN;
        if (t < 0.82f) return LED_AMBER;
        return LED_RED;
    }
}
