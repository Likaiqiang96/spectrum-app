package com.spectrum.app.core;

/**
 * 渲染线程用的后处理（设计文档 §6.5–6.9）。全部按 vsync 的 dt 推进，
 * 所以 46.9 fps 的分析和 60 fps 的渲染之间不会打架。
 */
public final class Dynamics {

    private Dynamics() {
    }

    /** 快起慢落的一阶平滑（系数按 dt 换算，与帧率无关）。 */
    public static final class Smoother {

        private float[] v = new float[0];

        public void setSize(int n) {
            if (v.length != n) {
                v = new float[n];
            }
        }

        public float[] values() {
            return v;
        }

        public void clear() {
            for (int i = 0; i < v.length; i++) v[i] = 0f;
        }

        public void advance(float[] target, float dtMs, float attackMs, float releaseMs) {
            float aUp = 1f - (float) Math.exp(-dtMs / attackMs);
            float aDown = 1f - (float) Math.exp(-dtMs / releaseMs);
            for (int i = 0; i < v.length; i++) {
                float t = target[i];
                float cur = v[i];
                v[i] = cur + (t - cur) * (t > cur ? aUp : aDown);
            }
        }
    }

    /**
     * 峰值保持 → "雨滴"效果：
     * 一有更高的柱子就立刻顶上去（瞬时上升），短暂悬停后**匀速下落**（不是指数衰减），
     * 落到底再跟柱子贴合。下落速度是满量程的 {@link #FALL_PER_SEC} 倍/秒。
     */
    public static final class PeakHold {

        /** 弹道模型：峰值点被瞬态"蹬"出去后，受重力下落、受阻力削速。 */
        private static final float FALL_PER_SEC = 1.6f;          // 兜底
        private static final float MAX_PEAK = 1.5f;   // 原 3.0 允许飞到屏幕外，太夸张
        private static final float MERGE_GAP = 0.02f;

        private float[] pos = new float[0];     // 显示高度（归一化）
        private float[] vel = new float[0];     // 速度（归一化/秒，向上为正）
        private float[] apexHold = new float[0];
        private float[] disp = new float[0];

        public void setSize(int n) {
            if (pos.length != n) {
                pos = new float[n];
                vel = new float[n];
                apexHold = new float[n];
                disp = new float[n];
            }
        }

        public float[] values() {
            return disp;
        }

        public void clear() {
            for (int i = 0; i < pos.length; i++) {
                pos[i] = 0f;
                vel[i] = 0f;
                apexHold[i] = 0f;
                disp[i] = 0f;
            }
        }

        /**
         * @param v       柱子当前高度
         * @param boost   本次分析帧的"冲高量"（归一化高度，由上升速率算出）
         * @param gravity 重力加速度（归一化/秒²），界面「重力」
         * @param drag    阻力系数（每秒速度衰减比例），界面「阻力」
         * @param holdMs  到顶后悬停时长，界面「峰值」
         */
        public void advance(float[] v, float[] boost, float dtMs, float holdMs,
                            float gravity, float drag, float fallPerSec) {
            float dt = dtMs / 1000f;
            if (dt <= 0f) dt = 0.016f;
            if (gravity <= 0f) gravity = 3f;
            if (drag < 0f) drag = 0f;
            float dragFactor = (float) Math.max(0.0, 1.0 - drag * dt);

            for (int i = 0; i < pos.length; i++) {
                float bar = v[i];

                // ① 只有**已经落回柱子**（贴地）时才允许被新瞬态蹬出去。
                //    早期写法在空中也会重置初速度 —— 表现就是"还没落到柱子上又突然上升"（用户报的 bug）。
                float b = boost != null && i < boost.length ? boost[i] : 0f;
                boolean grounded = pos[i] <= bar + 0.002f && vel[i] <= 0.001f;
                if (b > 0.001f && grounded) {
                    float launch = (float) Math.sqrt(2f * gravity * b);
                    vel[i] = launch;
                    apexHold[i] = holdMs;
                }

                // ② 柱子涨过它 → 被柱子顶上去（跟随）
                if (pos[i] < bar) {
                    pos[i] = bar;
                    if (vel[i] < 0f) vel[i] = 0f;
                }

                // ③ 到顶悬停（峰值菜单的保持时长）
                if (vel[i] <= 0f && apexHold[i] > 0f) {
                    apexHold[i] -= dtMs;
                    vel[i] = 0f;
                } else {
                    // ④ 弹道：重力减速/加速 + 阻力
                    vel[i] -= gravity * dt;
                    vel[i] *= dragFactor;
                    pos[i] += vel[i] * dt;
                }

                // ⑤ 落回柱子就停住（不能在柱子下面）
                if (pos[i] <= bar) {
                    pos[i] = bar;
                    vel[i] = 0f;
                }
                if (pos[i] - bar < MERGE_GAP && vel[i] <= 0f) pos[i] = bar;
                if (pos[i] > MAX_PEAK) pos[i] = MAX_PEAK;
                disp[i] = pos[i];
            }
        }
    }

    /**
     * 自动量程：滚动窗口内全部频带 dB 的 P95（64 桶直方图近似，不排序不分配），
     * 把 P95 映射到显示高度 0.9，下面固定 50 dB 动态范围；
     * P95 本身按 ±6 dB/s 限速移动，防呼吸感。
     */
    public static final class AutoRange {

        private static final int BUCKETS = 64;
        private static final float DB_LO = -100f;
        private static final float DB_HI = 0f;
        /** 显示窗口下限/上限：跟着信号的真实分布走，避免"窄动态范围信号整屏顶满"。 */
        private static final float MIN_SPAN_DB = 22f;
        private static final float MAX_SPAN_DB = 60f;
        /**
         * 绝对电平 → 整体高度。低于 {@link #ABS_LO_DB} 几乎不显示，高于 {@link #ABS_HI_DB} 顶到 0.9。
         * 这是为了修"麦克风稍微一点声音就顶到头"：以前不管多安静都把 P95 钉在 0.9，
         * 现在安静就矮、响就高，再配合用户增益微调。
         */
        private static final float ABS_LO_DB = -90f;
        private static final float ABS_HI_DB = -12f;
        /** 音源路捕获天生低 ~30 dB，用锚点偏移补回来。 */
        private float anchorOffsetDb;

        private final float[] hist = new float[BUCKETS];
        private int frames;
        private float accMs;

        private float p95Db = -60f;
        private float displayTop = -40f;        // 平滑后的"窗口顶部"
        private float floorDb = -90f;
        private float spanDb = 32f;
        private float p10Db = -85f;
        private float p50Db = -70f;
        private float heightFraction = 0.5f;

        public void setAnchorOffsetDb(float db) {
            this.anchorOffsetDb = db;
        }

        public float top() {
            return displayTop;
        }

        public float floor() {
            return floorDb;
        }

        public float span() {
            return spanDb;
        }

        public float p95() {
            return p95Db;
        }

        public float p10() {
            return p10Db;
        }

        public float p50() {
            return p50Db;
        }

        public float heightFraction() {
            return heightFraction;
        }

        public void clear() {
            for (int i = 0; i < BUCKETS; i++) hist[i] = 0f;
            frames = 0;
            accMs = 0f;
            p95Db = -60f;
            displayTop = -40f;
            floorDb = -90f;
            spanDb = 32f;
            heightFraction = 0.5f;
        }

        public void update(float[] bandDb, float dtMs) {
            for (int i = 0; i < bandDb.length; i++) {
                float db = bandDb[i];
                if (db < DB_LO) db = DB_LO;
                else if (db > DB_HI) db = DB_HI;
                int b = (int) ((db - DB_LO) / (DB_HI - DB_LO) * (BUCKETS - 1));
                hist[b] += 1f;
            }
            accMs += dtMs;
            if (++frames < 8) return;
            frames = 0;
            float stepMs = accMs;
            accMs = 0f;

            float total = 0f;
            for (int i = 0; i < BUCKETS; i++) total += hist[i];
            if (total <= 0f) return;

            p95Db = percentile(0.95f);
            p50Db = percentile(0.50f);
            p10Db = percentile(0.10f);

            float want = p95Db - p10Db;
            if (want < MIN_SPAN_DB) want = MIN_SPAN_DB;
            else if (want > MAX_SPAN_DB) want = MAX_SPAN_DB;
            spanDb += (want - spanDb) * 0.25f;

            for (int i = 0; i < BUCKETS; i++) hist[i] *= 0.85f;

            // 目标：让 P95 落在 height 这个高度上（绝对电平决定）
            float anchor = p95Db + anchorOffsetDb;
            if (anchor < ABS_LO_DB) anchor = ABS_LO_DB;
            else if (anchor > ABS_HI_DB) anchor = ABS_HI_DB;
            // 锚点从 0.9 降到 0.72：最响的柱子不再顶到天花板，
            // 上面留出的空间正好给"被抛出去"的峰值点（用户要求峰值点可以高过柱子）。
            float height = 0.72f * (anchor - ABS_LO_DB) / (ABS_HI_DB - ABS_LO_DB);
            if (height < 0.06f) height = 0.06f;
            heightFraction = height;

            float targetTop = p95Db + spanDb - height * spanDb / 0.9f;

            // 窗口位置限速平移（防呼吸感）
            float maxStep = 10f * stepMs / 1000f;
            float diff = targetTop - displayTop;
            if (diff > maxStep) diff = maxStep;
            else if (diff < -maxStep) diff = -maxStep;
            displayTop += diff;
            floorDb = displayTop - spanDb;
        }

        private float percentile(float frac) {
            float total = 0f;
            for (int i = 0; i < BUCKETS; i++) total += hist[i];
            if (total <= 0f) return p95Db;
            float target = total * frac;
            float acc = 0f;
            for (int i = 0; i < BUCKETS; i++) {
                acc += hist[i];
                if (acc >= target) {
                    return DB_LO + (i + 0.5f) / BUCKETS * (DB_HI - DB_LO);
                }
            }
            return DB_HI;
        }

        /** 手动量程：地板固定，上限 0 dBFS。 */
        public void setManual(float floor) {
            this.floorDb = floor;
            this.displayTop = floor + spanDb;
        }

        public float norm(float db) {
            float f = floorDb;
            float top = displayTop;
            if (top - f < 1f) top = f + 1f;
            float v = (db - f) / (top - f) * 0.9f;   // 归一化仍在 0..0.9，绘制时再乘柱高系数
            if (v < 0f) v = 0f;
            else if (v > 1f) v = 1f;
            return v;
        }
    }

    /** VU 表驱动：等 dB 刻度 −45..0 dBFS，平滑在渲染线程做。 */
    public static final class VuMeter {

        public static final int SEGMENTS = 40;      // 用户要求：密一点的竖条电平表
        /** 默认量程下限（麦克风路）。音源路捕获天生低 ~30 dB，渲染器会传 −75。 */
        public static final float FLOOR_DB = -45f;

        private float value;
        private final float[] peakHold = new float[SEGMENTS];
        private final float[] peakTimer = new float[SEGMENTS];

        public float value() {
            return value;
        }

        public float peak(int segment) {
            return peakHold[segment];
        }

        public void clear() {
            value = 0f;
            for (int i = 0; i < SEGMENTS; i++) {
                peakHold[i] = 0f;
                peakTimer[i] = 0f;
            }
        }

        public void advance(float rmsDb, float dtMs, float attackMs, float releaseMs,
                            float floorDb) {
            if (floorDb > -1f) floorDb = -1f;
            float norm = (rmsDb - floorDb) / (0f - floorDb);
            if (norm < 0f) norm = 0f;
            else if (norm > 1f) norm = 1f;
            float aUp = 1f - (float) Math.exp(-dtMs / attackMs);
            float aDown = 1f - (float) Math.exp(-dtMs / releaseMs);
            value += (norm - value) * (norm > value ? aUp : aDown);

            int lit = Math.round(value * SEGMENTS);
            for (int i = 0; i < SEGMENTS; i++) {
                float on = (i + 1) <= lit ? 1f : 0f;
                if (on >= peakHold[i]) {
                    peakHold[i] = on;
                    peakTimer[i] = 800f;
                } else {
                    peakTimer[i] -= dtMs;
                    if (peakTimer[i] <= 0f) {
                        peakHold[i] = Math.max(on, peakHold[i] - 0.20f * dtMs / 1000f);
                    }
                }
            }
        }

        public int litSegments() {
            return Math.round(value * SEGMENTS);
        }

        /** 峰值保持所到的最高段（-1 = 没有）。 */
        public int peakSegment() {
            for (int i = SEGMENTS - 1; i >= 0; i--) {
                if (peakHold[i] > 0.5f) return i;
            }
            return -1;
        }
    }
}
