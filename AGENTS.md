# 本项目的固定约定

## 安装后直接进 App

每次构建/安装完新的 APK，**不要停在桌面或让 App 处于停止状态**：

```bash
bash tools/build.sh
bash tools/install.sh dist/spectrum-0.1.apk
adb shell am start -n com.spectrum.app/.MainActivity    # ← 直接拉起，留在前台
```

- 调试参数（`--ez fakespectrum` 等）只在验证时用；**交付给用户时用不带参数的那条**，
  这样 App 会沿用用户上次的设置（音源/样式/增益/亮度…）。
- 验证完也不要 `am force-stop`，让它保持运行。

## 设备

- 小米 10（umi，Android 13，1080×2340@90Hz），无线调试：`192.168.1.7:5555`
  （`tools/adb-wifi.sh` 连接/重连；手机重启后需插 USB 跑一次 `--setup`）
- 命令要指定设备：`export ANDROID_SERIAL=192.168.1.7:5555`
