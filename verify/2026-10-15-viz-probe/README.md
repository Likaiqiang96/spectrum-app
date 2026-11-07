# 真机探针记录 · 2026-10-15 凌晨

设备：Meizu M1852 / Flyme 8.1.5.0A / Android 8.1.0 / API 27 / arm64-v8a

**约束**：用户要求"不要让设备发出任何声音"。全程媒体音量 0，唯一的例外是两段
8 Hz + 12 Hz 亚低频（手机喇叭物理上发不出来）用来给 Visualizer 制造信号。
下面的日志是从 `adb logcat -s VizProbe:I` 与 `dumpsys` 抄录的当次输出。

工具：`tools/viz-probe/`（17 KB 的探针 APK，`build.sh` 构建，`install.sh` 自动点掉 Flyme 的安装确认框）。

| 文件 | 验证的是什么 | 结论 |
|---|---|---|
| `01-no-record-audio-permission.log` | 只声明 MODIFY_AUDIO_SETTINGS 时 Visualizer 能不能建 | ❌ 不能：`RECORD_AUDIO` 是必需的 |
| `02-session0-works.log` | 授权后 session 0（全局混音）能不能抓到数据 | ✅ 能，且 AudioFlinger 里能看到挂在 session 0 的 Visualizer 效果链 |
| `03-volume-zero-blank.log` | 媒体音量 0 时数据长什么样 | ⚠️ 全 0（波形全 128、FFT 全 0），必须在 UI 上兜住 |
| `04-mic-ambient-noise.log` | 麦克风通路（只录环境噪声，不播放任何声音） | ✅ `UNPROCESSED` 可用，深夜底噪 −52.8 dBFS，自研 FFT 在真机跑通 |
| `05-audioflinger-effect-chains.txt` | 效果链到底挂在哪个输出上 | 2 条链：session 585（自建轨道）+ session 0（全局混音） |

## 顺带记下的两个环境事实

1. **Flyme 的 USB 安装确认框**：`adb install` 会被 `UsbInstallInterception` 拦下，
   返回 `INSTALL_FAILED_USER_RESTRICTED`。系统日志：
   `W UsbInstallInterception: Stop the application to install through adb!`。
   屏幕上会弹「正在通过USB自动安装以下应用 / 禁止 / 允许」，**17 秒不点就自动拒绝**。
   `install.sh` 用 `uiautomator dump` + `input tap` 自动点「允许」。
2. **探针运行期间必须 force-stop**：它持有 `FLAG_KEEP_SCREEN_ON`，不管它会亮一整夜。
