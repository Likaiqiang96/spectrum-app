#!/usr/bin/env python3
"""生成 App 图标：黑底 + 青→红渐变的频谱柱（和参考图那台设备一个调子）。

用法： python3 tools/make-icons.py app/res
"""
import os
import sys

from PIL import Image, ImageDraw

# 参考图那只屏幕的配色：青 → 蓝 → 紫 → 品红 → 红
STOPS = [
    (0.00, (0x00, 0xE5, 0xFF)),
    (0.25, (0x2E, 0x6B, 0xFF)),
    (0.50, (0x8A, 0x5C, 0xFF)),
    (0.75, (0xE0, 0x40, 0xC0)),
    (1.00, (0xFF, 0x3B, 0x30)),
]

# 每根柱子的相对高度（0..1），像一段音乐频谱
BARS = [0.42, 0.72, 1.00, 0.86, 0.55, 0.34, 0.47, 0.30, 0.22, 0.17]

DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def lerp_color(t):
    t = max(0.0, min(1.0, t))
    for i in range(len(STOPS) - 1):
        t0, c0 = STOPS[i]
        t1, c1 = STOPS[i + 1]
        if t0 <= t <= t1:
            k = 0.0 if t1 == t0 else (t - t0) / (t1 - t0)
            return tuple(int(round(c0[j] + (c1[j] - c0[j]) * k)) for j in range(3))
    return STOPS[-1][1]


def draw_icon(size):
    ss = 4  # 超采样，边缘干净
    w = h = size * ss
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # 圆角深色底
    r = int(w * 0.22)
    d.rounded_rectangle([0, 0, w - 1, h - 1], radius=r, fill=(8, 10, 14, 255))
    # 一圈很淡的边，模拟机身
    d.rounded_rectangle([0, 0, w - 1, h - 1], radius=r, outline=(230, 230, 230, 60),
                        width=max(1, int(w * 0.012)))

    # 频谱柱
    n = len(BARS)
    gap = w * 0.030
    pad = w * 0.13
    bw = (w - 2 * pad - gap * (n - 1)) / n
    base = h - pad
    for i, v in enumerate(BARS):
        x0 = pad + i * (bw + gap)
        bh = (h - 2 * pad) * v
        col = lerp_color(i / (n - 1.0))
        d.rounded_rectangle([x0, base - bh, x0 + bw, base],
                            radius=bw * 0.42, fill=col + (255,))

    return img.resize((size, size), Image.LANCZOS)


def main():
    if len(sys.argv) < 2:
        print("usage: make-icons.py <res-dir>", file=sys.stderr)
        return 2
    res = sys.argv[1]
    for folder, px in DENSITIES.items():
        out_dir = os.path.join(res, folder)
        os.makedirs(out_dir, exist_ok=True)
        icon = draw_icon(px)
        icon.save(os.path.join(out_dir, "ic_launcher.png"))
    print("icons written to", res)
    return 0


if __name__ == "__main__":
    sys.exit(main())
