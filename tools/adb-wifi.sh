#!/usr/bin/env bash
# 无线（Wi-Fi）adb 连接 / 重连。
#
# 背景：
#   `adb tcpip 5555` 之后 adbd 会以 TCP 模式监听 5555，拔掉 USB 也能继续用；
#   但**手机重启后 TCP 模式会复位**，需要重新用 USB 跑一次 `adb tcpip 5555`。
#
# 用法：
#   tools/adb-wifi.sh                # 连默认 192.168.1.7:5555（Mi 10）
#   tools/adb-wifi.sh 192.168.1.9    # 连指定 IP
#   tools/adb-wifi.sh --setup        # 手机还插着 USB 时：切 TCP 模式并连接
#
# 环境变量：ADB（默认 adb）、ADB_WIFI_HOST、ADB_WIFI_PORT
set -euo pipefail

ADB="${ADB:-adb}"
HOST="${ADB_WIFI_HOST:-192.168.1.7}"
PORT="${ADB_WIFI_PORT:-5555}"

if [ "${1:-}" = "--setup" ]; then
    echo "== 让 adbd 切到 TCP 模式（需要 USB 连着）"
    "$ADB" tcpip "$PORT"
    sleep 2
else
    [ -n "${1:-}" ] && HOST="$1"
    [ -n "${2:-}" ] && PORT="$2"
fi

echo "== adb connect $HOST:$PORT"
"$ADB" connect "$HOST:$PORT" || {
    echo "连接失败：确认手机 Wi-Fi 开着、和本机同网段、IP 没变。" >&2
    echo "  查 IP：手机上 设置→WLAN→当前网络；或 USB 连着时 adb shell ip -f inet addr show wlan0" >&2
    echo "  手机刚重启过？先插 USB 跑：tools/adb-wifi.sh --setup" >&2
    exit 1
}

echo "== 当前设备"
"$ADB" devices -l
