package com.spectrum.app;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import com.spectrum.app.settings.AppSettings;
import com.spectrum.app.settings.Theme;

/**
 * 顶栏菜单 + 子菜单浮层，复刻参考图那套：
 *   顶栏 8 项白字；点开某一项后在它下面竖排列出选项，
 *   当前值红底白字，其余暗黄字，后面跟一个序号（照片里就是「柱形 2 / 波形 0 …」）。
 *
 * 几何在渲染线程算、触摸在 UI 线程读，用 volatile 的矩形数组传递，允许一帧的偏差。
 */
final class MenuBar {

    private static final long AUTO_CLOSE_MS = 3000L;

    private final AppSettings settings;

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hlPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hlTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hlTopPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private volatile RectF[] itemRects = new RectF[0];
    private volatile RectF[] optionRects = new RectF[0];
    private volatile int openMenu = -1;
    private volatile int openOption = -1;
    private volatile long openAtMs;
    /** 两行布局下，整条菜单栏的底边（子菜单从这里往下展开）。 */
    private volatile float barBottom;

    private float textSize = 40f;
    private float optionTextSize = 38f;
    private float rowH = 46f;

    MenuBar(AppSettings settings) {
        this.settings = settings;
        textPaint.setColor(Theme.TEXT);
        dimPaint.setColor(Theme.TEXT_DIM);
        hlPaint.setColor(Theme.MENU_HL);
        hlTextPaint.setColor(Theme.TEXT);
        hlTopPaint.setColor(Theme.TEXT);
    }

    void setTypeface(android.graphics.Typeface tf) {
        textPaint.setTypeface(tf);
        dimPaint.setTypeface(tf);
        hlTextPaint.setTypeface(tf);
        hlTopPaint.setTypeface(tf);
    }

    void layout(float left, float top, float right, float textSizePx) {
        this.textSize = textSizePx;
        this.optionTextSize = textSizePx * 0.95f;
        this.rowH = textSizePx * 1.15f;
        textPaint.setTextSize(textSize);
        hlTopPaint.setTextSize(textSize);
        dimPaint.setTextSize(optionTextSize);
        hlTextPaint.setTextSize(optionTextSize);

        // 恢复成**一排**（用户 2026-11-07 要求）。按钮高度保持 2.2 倍字高，仍然好点。
        int n = AppSettings.MENUS.length;
        float w = right - left;
        int perRow = n;
        float itemW = w / perRow;
        float itemH = textSize * 2.2f;
        RectF[] rects = new RectF[n];
        for (int i = 0; i < n; i++) {
            int row = i / perRow;
            int col = i % perRow;
            float l = left + col * itemW;
            float t = top + row * itemH;
            rects[i] = new RectF(l, t, l + itemW, t + itemH);
        }
        itemRects = rects;
        barBottom = top + ((n + perRow - 1) / perRow) * itemH;
        rebuildOptions();
    }

    private void rebuildOptions() {
        int menu = openMenu;
        if (menu < 0) {
            optionRects = new RectF[0];
            return;
        }
        RectF[] items = itemRects;
        if (menu >= items.length) {
            optionRects = new RectF[0];
            return;
        }
        String[] opts = AppSettings.OPTIONS[menu];
        float padX = textSize * 0.6f;
        float left = items[menu].left + padX * 0.4f;
        // 子菜单从**整条菜单栏下方**展开，避免遮住第二行的按钮
        float top = barBottom + textSize * 0.25f;
        RectF[] rects = new RectF[opts.length];
        float maxW = 0;
        for (String o : opts) {
            maxW = Math.max(maxW, dimPaint.measureText(o + " 9"));
        }
        float w = maxW + padX * 2f;
        for (int i = 0; i < opts.length; i++) {
            rects[i] = new RectF(left, top + i * rowH, left + w, top + (i + 1) * rowH);
        }
        optionRects = rects;
    }

    boolean isOpen() {
        return openMenu >= 0;
    }

    void close() {
        openMenu = -1;
        openOption = -1;
        optionRects = new RectF[0];
    }

    void tickClose(long nowMs) {
        int m = openMenu;
        if (m >= 0 && nowMs - openAtMs > AUTO_CLOSE_MS) {
            close();
        }
    }

    /**
     * 处理一次点击。
     *
     * @return 0 = 没吃到；1 = 只是开合菜单（界面要刷新）；2 = 改了设置（调用方要重建派生状态）
     */
    int onTouch(float x, float y) {
        RectF[] opts = optionRects;
        int menu = openMenu;
        if (menu >= 0) {
            for (int i = 0; i < opts.length; i++) {
                RectF r = opts[i];
                if (r != null && r.contains(x, y)) {
                    if (menu == AppSettings.DISPLAY) {
                        // 「显示」是多选开关：翻转这一位，菜单保持打开
                        settings.set(menu, settings.sel[menu] ^ (1 << i));
                    } else {
                        settings.set(menu, i);
                    }
                    openAtMs = android.os.SystemClock.elapsedRealtime();
                    return 2;
                }
            }
        }
        RectF[] items = itemRects;
        for (int i = 0; i < items.length; i++) {
            RectF r = items[i];
            if (r != null && r.contains(x, y)) {
                if (openMenu == i) {
                    close();
                } else {
                    openMenu = i;
                    openAtMs = android.os.SystemClock.elapsedRealtime();
                    rebuildOptions();
                }
                return 1;
            }
        }
        if (menu >= 0) {
            close();
            return 1;
        }
        return 0;
    }

    void draw(Canvas c) {
        RectF[] items = itemRects;
        if (items.length == 0) return;
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textOffset = -(fm.ascent + fm.descent) / 2f;   // 垂直居中
        for (int i = 0; i < items.length; i++) {
            RectF r = items[i];
            String label = AppSettings.MENUS[i];
            float tw = textPaint.measureText(label);
            float cx = (r.left + r.right) / 2f - tw / 2f;
            float baseline = r.centerY() + textOffset;
            if (i == openMenu) {
                // 选中的菜单项给一个底色，方便看清当前在看哪一项
                c.drawRoundRect(new RectF(r.left + 6, r.top + 6, r.right - 6, r.bottom - 6),
                        textSize * 0.22f, textSize * 0.22f, hlPaint);
                c.drawText(label, cx, baseline, hlTopPaint);
            } else {
                c.drawText(label, cx, baseline, textPaint);
            }
        }
        if (openMenu < 0) return;

        RectF[] opts = optionRects;
        String[] names = AppSettings.OPTIONS[openMenu];
        Paint.FontMetrics ofm = dimPaint.getFontMetrics();
        for (int i = 0; i < opts.length; i++) {
            RectF r = opts[i];
            boolean on = (openMenu == AppSettings.DISPLAY)
                    ? (settings.sel[openMenu] & (1 << i)) != 0
                    : i == settings.sel[openMenu];
            String text = (openMenu == AppSettings.DISPLAY)
                    ? ((on ? "√ " : "· ") + names[i])
                    : names[i] + " " + i;
            float base = r.top - ofm.ascent + rowH * 0.08f;
            if (on) {
                c.drawRoundRect(new RectF(r.left, r.top + 2, r.right, r.bottom - 2),
                        rowH * 0.12f, rowH * 0.12f, hlPaint);
                c.drawText(text, r.left + rowH * 0.35f, base, hlTextPaint);
            } else {
                c.drawText(text, r.left + rowH * 0.35f, base, dimPaint);
            }
        }
    }
}
