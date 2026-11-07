# spectrum-app · 音频频谱仪（Android）

把参考图里那台手持式音频频谱显示设备搬进手机的 Android App。

**当前状态：两种输入模式都已在真机验证 —— 麦克风采样（v0.1）+ 音源 / 系统混音（v0.2）。**

| | |
|---|---|
| 参考图 | B 站视频截图（UP：Given晚椿），一台带屏幕 + LED 灯带的手持频谱仪 |
| 目标设备 | Meizu M1852 · Flyme 8.1.5.0A · Android 8.1.0 / API 27 / arm64-v8a / SDM710 / 1080×2220 @60 Hz |
| 构建方式 | aapt2 + javac + d8 + zipalign + apksigner（无 Gradle / 无 AndroidX / 无第三方库 / 无 NDK） |
| 产物 | [`dist/spectrum-0.1.apk`](dist/spectrum-0.1.apk)（61 KB，已签名） |
| 设计文档 | [`docs/设计文档.md`](docs/设计文档.md) |
| 真机实测 | 麦克风 [`verify/2026-10-15-app-mic/`](verify/2026-10-15-app-mic/README.md) · 音源 [`verify/2026-10-15-app-mix/`](verify/2026-10-15-app-mix/README.md) · 音频通路探针 [`verify/2026-10-15-viz-probe/`](verify/2026-10-15-viz-probe/README.md) |

## 现在能做什么

- **麦克风采样**：`AudioRecord(UNPROCESSED → MIC)` → 48 kHz / hop 1024 / 自研 2048 点 FFT →
  **144 条**对数频带（60 Hz–16 kHz，含 +3 dB/oct 倾斜补偿）→ 实测 47 fps
- **音源（系统混音）**：`Visualizer` 绑 session 0 抓其它 App 正在播的歌 →
  系统 1024 点 FFT / 44.1 kHz / **20 fps** / 32 条对数频带（90 Hz–16 kHz）；
  含 8-bit 波形+FFT 解析、+30 dB 电平补偿、音量 0 时的「请调大音量」提示、失败自动回退麦克风
- **画面**：黑底 + 居中「设备」机身 + 深色屏幕卡片 + 下方 14 段 VU 灯带；
  频谱样式 7 档（柱形 / 波形 / 三角 / 面积 / 密线 / 实心 / 疏线，见 docs §7.2），配色 8 档可选（青红 / 绿黄红 / 纯白 /
  **流光溢彩**（彩虹由低频向高频滚动，约 4.6 秒一圈 + 一道柔光扫过）/ 单色红绿蓝紫）；
  屏下 LED 电平表为 **40 段密集竖条**
- **模式切换**：菜单里「模式」选麦克风 / 音源，即时重建音频链路并落盘（下次启动保持）
- **菜单**：模式 / 样式 / 缩放 / 峰值 / 颜色 / 范围 / 歌词 / 休眠，点开是红底高亮的子菜单（复刻参考图）
- **样式**：柱形（默认，四层叠加，对齐参考图）/ 波形 / 三角 / 面积
- **自动量程**：显示窗口 `clamp(P95−P10, 22, 60) dB`；**整体高度由绝对电平决定**（−90 dBFS 以下几乎不显示、
  −12 dBFS 顶到 0.9），再加上右侧滑动的用户增益 —— 所以"安静就是矮、响就是高"，不会一点声音就顶满
  （踩过的三个坑写在设计文档 §6.7 与 `verify/2026-10-15-autorange-fix/`）
- **自检信号源**：`--ez selftest true`，不需要任何真实声音就能验收整条链路
- **交互手势**：右侧上下滑动 = 增益（±24 dB）；左侧上下滑动 = 屏幕亮度；中间点菜单、长按出调试面板；
  顶栏菜单 4 秒无操作自动隐藏
- **坐标**：横坐标可切对数 / 线性（菜单第 3 项）
- **动态节奏**：柱形攻击 8 ms / 释放 90 ms（音源路 22/130 ms）；峰值点「雨滴」式匀速下落（1.6 屏/秒）
- **调试面板**：长按屏幕 → fps / 分析帧率 / 渲染耗时 / RMS / floor·p95·span·p10 / 当前音频后端
- **验证辅助**（都不出声）：`--ez selftest true` 自检五音、`--ez fakespectrum true` 假频谱（只验渲染映射）、
  `--ez mixtest true` 音源模式 + 听不见的亚低频测试音

真机实测：**麦克风路分析 47 fps / 音源路 20 fps（系统上限）、渲染 60 fps、单帧 1.2~4.8 ms**（预算 16.7 ms）。

## 跑起来

```bash
bash tools/build.sh                            # 构建（会自动生成图标）
bash tools/install.sh dist/spectrum-0.1.apk    # 安装（自动点掉 Flyme 的「允许」确认框）
adb shell pm grant com.spectrum.app android.permission.RECORD_AUDIO
adb shell am start -n com.spectrum.app/.MainActivity                    # 麦克风模式
adb shell am start -n com.spectrum.app/.MainActivity --ez selftest true # 自检信号（无声）
# 音源模式：点顶栏「模式」→「音源」；调试可用下面这条（App 会播一段听不见的亚低频来验证通路）
adb shell am start -n com.spectrum.app/.MainActivity --ez mixtest true
adb shell logcat -s Spectrum:I                 # 每 2 秒一行状态
```

### 无线调试（不用一直插线）

```bash
tools/adb-wifi.sh --setup    # 手机还插着 USB 时跑一次：adbd 切 TCP 5555 并连上
tools/adb-wifi.sh            # 之后（或换机器）直接连：adb connect 192.168.1.7:5555
```

- 切过 TCP 之后**拔掉 USB 也能继续用**；
- **手机重启后 TCP 模式会复位**，需要插一次 USB 再跑 `tools/adb-wifi.sh --setup`；
- IP 变了就把新 IP 传给脚本（`tools/adb-wifi.sh 192.168.1.x`），或改 `ADB_WIFI_HOST` 环境变量。

**Flyme 的两个坑**（都写在 `verify/2026-10-15-app-mic/README.md` 里）：

1. `adb install` 的确认框一旦被点「禁止」或超时，Flyme 会把该包记成
   `Settings.Secure: usb_install_item_<包名>=标签:-1`，之后**连弹窗都不给**；
   改回 `'标签:1'` 即可。
2. Flyme 夜间模式会改写大面积浅色，所以机身直接设计成深色，不依赖系统改写。

## 还没做 / 待验证

- **真实音乐下的观感**：自动量程已按"窄动态范围信号"修过（三个 bug），请在真机上放首歌再确认一次

- ⚠️ **真实音乐 + offload 待验证**：本机有 `COMPRESS_OFFLOAD` 输出且 `offload.min.duration=30s`，
  长于 30 秒的 MP3 可能被送给 DSP 硬解、绕过混音 → `Visualizer` 抓不到。
  用 `/sdcard/Music/probe_silent.mp3`（75 s，只含 40/70 Hz，静音）放一遍即可判定。
- 歌词（需要「通知使用权」读系统媒体会话）
- 「休眠」「缩放」目前能点能存，但还没接进渲染行为

## 目录

```
app/                             App 源码（Java / framework only）
  src/com/spectrum/app/          MainActivity · SpectrumSurfaceView · SpectrumRenderer · MenuBar
  src/com/spectrum/app/core/     SpectrumProvider · MicProvider · SystemMixProvider
                                 PcmProvider · SelfTestProvider · TonePlayer · Analyzer
                                 Fft · BandMapper · FloatRing · Dynamics · SpectrumFrame
  src/com/spectrum/app/settings/ AppSettings · Theme
docs/设计文档.md                 设计文档 v0.2（14 节）
verify/                          真机验证记录 + 截图（麦克风 / 音源 / 量程 / 动态 / 交互 各一份）
tools/build.sh · install.sh      构建 / 安装（含 Flyme 允许弹窗自动点击）
tools/make-icons.py              生成 App 图标
tools/jvm/                       离线 DSP 探针（8 项断言）
tools/viz-probe/                 真机音频通路探针（Visualizer / AudioRecord）
```
