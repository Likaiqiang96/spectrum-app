# App 真机验证记录 · 2026-10-15 凌晨（麦克风采样）

设备：Meizu M1852 / Flyme 8.1.5.0A / Android 8.1.0 / API 27
APK：`dist/spectrum-0.1.apk`（61 KB，`tools/build.sh` 构建，`tools/install.sh` 安装）
**全程没有让设备发出声音**（媒体音量 0；自检信号只走软件通路，不进喇叭）。

| 截图 | 验的是什么 | 结论 |
|---|---|---|
| [`01-mic-hud.png`](01-mic-hud.png) | 麦克风模式 + 调试面板 | `rms -77.7 dBFS`、`analysis 47 fps`、`bands 48`、`fps 60.0 / render 2.77ms`、`src 麦克风 · UNPROCESSED`、`rebuilds 0` |
| [`02-mic.png`](02-mic.png) | 麦克风模式的干净画面（调试面板关） | 深夜房间底噪的频谱形状：低频略高、右端平坦，配色青→红 |
| [`03-selftest-5tones.png`](03-selftest-5tones.png) | **确定性验证**：自检信号 60/250/1k/4k/10k 五音 | 左边合成大山（琥珀低音指示点亮）、1k 紫峰、4k 品红峰、10k 红峰，位置与 `f_k = 60·(16000/60)^(k/48)` 的理论值一致；VU 灯带 **8 绿 + 3 琥珀 = 11 段**，与 `(-9.9+45)/45×14 = 11.1` 吻合 |
| [`04-menu.png`](04-menu.png) | 菜单复刻 | 点开的菜单项红底白字，子项竖排（柱形 0 高亮 / 波形 1 / 三角 2 / 面积 3），和参考图一致 |
| [`05-style-wave.png`](05-style-wave.png) | 样式切换 | 切到「波形」后只剩白色包络线（柱形/面积/峰值按设计隐藏） |

## 性能（真机实测）

| 指标 | 实测 | 设计预算 |
|---|---|---|
| 分析帧率 | **47 fps** | 46.9 fps（48 kHz / hop 1024） |
| 渲染帧率 | **60 fps** | vsync 60 Hz |
| 单帧渲染耗时 | **1.2 ~ 4.8 ms** | < 16.7 ms |
| 麦克风源 | `UNPROCESSED` 可用（无降噪/AGC 污染） | 优先 UNPROCESSED |
| 采集缓冲 | 3840 bytes = 1920 帧 | — |
| 长稳 | 连续运行无渲染异常（`render failed` 计数 0） | — |

## 踩到的一个 Flyme 坑（值得记下来）

`adb install` 会被 `UsbInstallInterception` 拦下，返回 `INSTALL_FAILED_USER_RESTRICTED`。
**Flyme 把"每个包的 USB 安装决定"存在 `Settings.Secure` 里**：

```
usb_install_item_com.spectrum.app=频谱仪:-1     ← -1 = 拒绝，会秒拒且不再弹窗
usb_install_item_switch=switch:1
```

一旦弹出确认框时点了「禁止」（或者在超时 17 秒内没点「允许」），这个包就被记成 `-1`，
之后所有 `adb install` 都会**直接拒绝、连弹窗都不给**。改回来即可：

```bash
adb shell settings put secure usb_install_item_com.spectrum.app '频谱仪:1'
```

另外：Flyme 的**夜间模式会改写大面积浅色**（`flymelab_flyme_night_mode=1`），
我最初设计的白色机身被系统改写成深色。现在机身直接设计成深色（`Theme.BODY = 0xFF171A21`），
不依赖系统改写，换机也是一致的深色。

## 复现

```bash
bash tools/build.sh
bash tools/install.sh dist/spectrum-0.1.apk     # 自动点掉 Flyme 的「允许」
adb shell pm grant com.spectrum.app android.permission.RECORD_AUDIO   # Flyme 还会再弹一次自己的权限框
adb shell am start -n com.spectrum.app/.MainActivity                  # 麦克风模式
adb shell am start -n com.spectrum.app/.MainActivity --ez selftest true  # 自检信号（无声）
adb shell logcat -s Spectrum:I                                        # 每 2 秒一行状态
```

交互：点顶栏菜单开子菜单；长按屏幕 = 调试面板（fps / 分析帧率 / RMS / 量程）。
