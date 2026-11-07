# 峰值弹道 bug 修复 + 构建环境重建 · 2026-11-07

## 1. 修掉「峰值在半空中突然上升」

用户反馈：**峰值点还没落到柱子上，就在半空中又突然升上去**。

根因在弹道模型的「瞬态给初速度」那一步 —— 它**没有判断峰值点是否已经落地**：

```java
// ❌ 修前：只要来新瞬态就重置向上初速度（空中也照做）
if (b > 0.001f) {
    float launch = (float) Math.sqrt(2f * gravity * b);
    if (launch > vel[i]) vel[i] = launch;      // 空中的下落被打断 → 突然上升
    apexHold[i] = holdMs;
}

// ✅ 修后：只有已经落回柱子（贴地）才允许被再次蹬出
boolean grounded = pos[i] <= bar + 0.002f && vel[i] <= 0.001f;
if (b > 0.001f && grounded) {
    vel[i] = (float) Math.sqrt(2f * gravity * b);
    apexHold[i] = holdMs;
}
```

**验证**（临时日志采样 353 帧，假频谱每 0.9s 一次瞬态 —— 比飞行时间还短，正是最容易触发该 bug 的场景）：

```
半空中被重新蹬起的次数: 0
峰值点最高飞出柱子上方: 0.618 屏高
```

修前在这个场景下，每次下落中的峰值点都会被新瞬态重新顶起来。

## 2. 构建环境重建（机器被重置，工具链全没了）

`~/.cache/dsh-apk` 里只剩空的 `dl/`，JDK / SDK 全丢。重建过程：

| 项 | 做法 |
|---|---|
| JDK | 用系统的 `/usr/lib/jvm/java-21-openjdk-amd64`，软链到 `~/.cache/dsh-apk/jdk` |
| build-tools | 下载 `build-tools_r35_linux.zip` → `sdk/build-tools/35.0.0` |
| platform | API 34 现在只发 `platform-34-ext7_r03.zip`（`platform-34_r03.zip` 已 404）→ 解出 `android-34/android.jar` |
| adb | 系统 `/usr/local/bin/adb`；`tools/install.sh` 里的硬编码路径改成 `ADB="${ADB:-adb}"` |

**顺带修掉一个坑**：build-tools **34.0.0 自带的 R8 8.2.2-dev 会崩** —— 在 `MicProvider$1.class` 上报
`NullPointerException: Cannot invoke "String.length()" because "<parameter1>" is null`。
试过 `javac --release 8` 无效，换 **build-tools 35.0.0 的 d8 就正常**了 → `tools/build.sh` 默认版本改为 35.0.0。

## 3. 内部音频 + 完全静音

按用户要求切到**音源（session 0 系统混音）**，媒体音量置 0：

```
mode=音源  src=音源 · 系统混音(session 0)  bands=96  fps=89.9  render=0.83ms
rms=-180.0dB     ← 静音
```

⚠️ **注意**：`Visualizer` 拿的是**音量之后**的混音，所以媒体音量为 0 时抓到的就是静音（rms −180dB），
屏幕上会是平的 + 弹出「媒体音量为 0，请调大音量」。
要保持"手机不出声"又想看到内部音频，有两个办法：

1. **插耳机 / 连蓝牙**，再把媒体音量调上去（外放不出声，混音仍有内容）；
2. 或者接受静音状态只看峰值/UI 表现（用 `--ez fakespectrum true` 可以喂假频谱，完全不碰音频硬件）。


---

## 追加：去掉「柱形」样式的包络线（2026-11-07）

用户：**「样式 柱形 现在有个包络，去掉」**。

柱形（style 0）原来是「面积 + 柱 + 白色包络线」三层，现在只保留前两层：

```java
// 修前
boolean drawLine = style == 0 || style == 1 || style == 3;
// 修后：柱形不再画包络线，只有「波形」「面积」保留
boolean drawLine = style == 1 || style == 3;
```

| 样式 | 面积 | 谱线 | 包络线 |
|---|---|---|---|
| 柱形 0 | ✅ | ✅ | **—**（去掉）|
| 波形 1 | — | — | ✅ |
| 面积 3 | ✅ | — | ✅ |
| 密线 4 / 实心 5 / 疏线 6 | 按原设计 | | — |
