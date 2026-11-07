# 记住音源 / 增益 / 亮度等设置 · 2026-11-07

用户：**「每次 APP 退出 记住当前使用的音源 以及增益 和亮度 等设置」**。

## 现状盘点

| 设置 | 之前 | 现在 |
|---|---|---|
| **音源（模式）** | ✅ 已持久化（`sel[0]` → SharedPreferences）| ✅ |
| **增益** | ✅ 已持久化（拖动中只改内存，抬手 `persistGain()`）| ✅ |
| **亮度** | ❌ 只在窗口属性里，**每次启动都回到 60%** | ✅ **落盘并恢复** |
| 样式 / 坐标 / 缩放 / 峰值 / 颜色 / 范围 / 休眠 / 重力 / 阻力 | ✅ 都在 `sel[]` 里持久化 | ✅ |

## 亮度的存法

`AppSettings` 新增（与增益同样的"内存 + 落盘"两段式）：

```java
public volatile float brightness = 0.6f;          // 内存值，拖动时每帧更新
public void setBrightness(float v) { ... }        // 只改内存
public void persistBrightness() { ... }           // 抬手才写盘
```

接线：
- 亮度手势滑动 → `settings.setBrightness(v)`；抬手 → `persistBrightness()`；
- 启动时 `MainActivity` 读 `settings.brightness` 同时设给渲染器和**窗口的 screenBrightness**，
  所以上一次退出时多亮，这次启动就多亮。

## 实测（强杀进程后无参启动）

```
启动参数：--ez mixmode true --ef gain 20 --ef bright 0.8
  → mode=音源  gain=+20.0dB  bright=80%

adb shell am force-stop com.spectrum.app && 普通启动
  → mode=音源  gain=+20.0dB  bright=80%   ✓ 三项全部记住
再强杀一次 → 仍然 mode=音源  bright=80%    ✓ 稳定
```

## 备注

之前几轮改菜单结构时，偏好键从 `sel` 一路换到 `v5_sel` —— **每次换键都等于把设置清空一次**，
所以你之前可能感觉"记不住"。现在菜单结构已定稿，键固定为 `v5_sel`，不会再重置。
