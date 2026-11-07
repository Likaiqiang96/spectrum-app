# 样式调整：删黄线 / LED 竖条加密 / 颜色可选 + 彩虹流动 · 2026-10-17

## 1. 删掉频谱最左边的黄线

那条是早期按参考图猜的「低音能量指示」（`BassIndicator`，琥珀色竖条）。
参考图没这个语义、用户也不要，**已整段删除**。

## 2. 屏下 LED 电平表：14 段宽方块 → **40 段密集竖条**

- 段数 **14 → 40**（更密）；
- 每段从"扁圆角方块"改成 **高 > 宽 的竖圆角矩形**，段与段紧邻；
- 峰值不再用每段小点，改成**在峰值段上叠一根更亮的竖条**；
- **增益同时作用到 LED 表**（否则调增益它不动）：`vu.advance(lastRmsDb + gainDb, …)`。

见 [`02-led-vertical-dense.png`](02-led-vertical-dense.png)（绿 → 琥珀，蓝色为峰值段）。

## 3. 颜色可选 + 彩虹流动

颜色菜单从 4 档扩到 **8 档**：

| 档 | 效果 |
|---|---|
| 青红（默认） | 青→蓝→紫→品红→红，按频率渐变 |
| 绿黄红 | 低绿高红 |
| 纯白 | 单色白 |
| **彩虹** | **整条频谱是完整色相环，并且横向流动** |
| 单色红 / 绿 / 蓝 / 紫 | 纯单色 |

**彩虹流动的实现**：铺一条「两倍频谱宽、含两圈色相」的 `LinearGradient`，
每帧只更新一个复用的 `Matrix` 做平移（`setLocalMatrix`）——**每帧零分配**，不影响 60 fps。
速度 130 px/s（一个循环约 15 秒）。

**验证它是真的在动**：相隔数秒的两张截图，按色相匹配求最佳横向位移 =
**240 px，匹配度 1.0**（零位移匹配度 0.0）→ 图样确实整体平移。
见 [`01-rainbow-full.png`](01-rainbow-full.png)、[`03-solid-green.png`](03-solid-green.png)、
[`04-color-menu-8.png`](04-color-menu-8.png)、[`05-default-qinghong.png`](05-default-qinghong.png)。

## 复现

```bash
adb shell am start -n com.spectrum.app/.MainActivity
# 点屏幕中间唤出菜单 → 「颜色」→ 选「彩虹 3」/「单色绿 5」
adb shell logcat -s Spectrum:I
```
