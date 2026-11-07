# 音源（系统混音）模式真机验证 · 2026-10-15

设备：Meizu M1852 / Flyme 8.1.5.0A / Android 8.1.0 / API 27
APK：`dist/spectrum-0.1.apk`（`tools/build.sh` 构建，`tools/install.sh` 安装）

**验证方式**：App 内播一段 8 Hz + 12 Hz 的亚低频（`TonePlayer`，只有 `--ez mixtest true` 时启用），
手机喇叭物理上发不出这两个频率，所以**全程没有声音**；同时它又确实在系统混音里，
足以验证 `Visualizer(session 0)` 这条通路的每一环。

| 截图 | 验的是什么 |
|---|---|
| [`01-mix-mode.png`](01-mix-mode.png) | 音源模式整体画面 + 调试面板：`analysis 20 fps`、`bands 32`、`src 音源 · 系统混音(session 0)` |
| [`02-mix-vu.png`](02-mix-vu.png) | VU 灯带：8 绿 + 3 琥珀 + 1 红 = 12 段（`rms −8.2 dBFS` → `(−8.2+45)/45×14 = 11.4` 段） |
| [`03-volume-zero-hint.png`](03-volume-zero-hint.png) | 媒体音量拉到 0 后，屏上出现**「媒体音量为 0，请调大音量」**（设计文档 §6.10） |
| [`04-peak-dots.png`](04-peak-dots.png) | 峰值点改成饱和亮青后，在夜间模式下不再被改写（见下） |

## 日志逐环节验证

```
SpectrumMix: Visualizer(0) ready, captureSize=1024 maxRate=20000 mHz
Spectrum:    engine started: 音源 · 系统混音(session 0) bands=32 sr=44100
SpectrumMix: first callback: samplingRate=44100 Hz, waveform=1024
SpectrumMix: mapper rebuilt: sr=44100 fMin=90.0 fMax=16000.0 bands=32
SpectrumMix: top bins: #0=0Hz/0.000 #1=43Hz/0.000 #2=86Hz/0.000 ... rms=-150.0dB   ← 播之前：全 0
SpectrumMix: top bins: #0=0Hz/0.117 #1=43Hz/0.035 #2=86Hz/0.017 #4=172Hz/0.011  binHz=43.07 rms=-5.2dB
Spectrum:    mode=音源 fps=60.0 render=2.83ms analysis=19fps bands=32 rms=-9.6dB bass=-0.4dB
```

对上了三件事：

1. **毫赫兹换算**：回调给的 `samplingRate` 是 `44100000`（毫赫兹），除以 1000 = 44.1 kHz；
   `binHz = 44100/1024 = 43.07` 与日志一致。
2. **8-bit FFT 解析**：8 Hz + 12 Hz 的能量落在 bin #0/#1/#2（0/43/86 Hz），与预期吻合。
3. **回调频率**：`analysis 19~20 fps`，正好是 `getMaxCaptureRate() = 20000 mHz` 的上限。
4. **电平补偿**：原始捕获 −35 dB 左右，加 `MIX_GAIN_DB = 30` 后 −5.2 dB，与探针测到的
   "全局混音比轨道级低约 34 dB" 吻合。

## 两个必须写清的结论

**① 媒体音量为 0 时，系统混音捕获全是 0。**
App 检测到连续 3 秒全 0 就在歌词下面提示「媒体音量为 0，请调大音量」。
这不是 bug，是本机实测的硬行为（探针 `verify/2026-10-15-viz-probe/03-volume-zero-blank.log` 也是同一个结论）。

**② Flyme 夜间模式会改写"实心浅色块"。**
`flymelab_flyme_night_mode=1` 时，大面积或成块的浅色会被改写成深色（文字、细线、饱和色不受影响）。
白色机身、白色峰值点都被吃掉过；现在机身直接用深色、峰值点用饱和亮青（`Theme.PEAK_DOT`），
不依赖系统改写。

## ⚠️ 还没验证的一条（重要）

本机 `dumpsys media.audio_policy` 里有 **COMPRESS_OFFLOAD 输出**（output 5/7/8，
flags `0x0031 = DIRECT|COMPRESS_OFFLOAD|NON_BLOCKING`），且 `audio.offload.min.duration.secs=30`。
也就是说**长于 30 秒的压缩音频（普通 MP3/OGG 歌曲）有可能被送去 DSP 硬解**，
PCM 根本不进 AudioFlinger 的混音器 → 挂在混音输出上的 `Visualizer` 可能什么都抓不到。

这条**必须用真实音乐验证**（需要设备出声，所以没有在凌晨做）：
`/sdcard/Music/probe_silent.mp3`（75 秒，只含 40/70 Hz，喇叭发不出来）就是为此准备的，
音量 index 4 播放它，同时看 App 的音源模式有没有数据；如果连它都抓不到，
那就说明 offload 会绕过混音，音源模式对"正常听歌"失效，只能退回麦克风模式
（API 29+ 的机器改用 `AudioPlaybackCapture` 就没这个问题）。

## 复现

```bash
adb shell am start -n com.spectrum.app/.MainActivity --ez mixtest true   # 音源模式 + 听不见的测试音
# 点顶栏「模式」→「音源」（子菜单第 2 项）
adb shell logcat -s Spectrum:I SpectrumMix:I
```
