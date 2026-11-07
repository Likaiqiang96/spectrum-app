#!/usr/bin/env bash
# 在 Flyme 上安装 APK，自动点掉系统的「允许」确认框。
#
# 背景：这台 M1852 每次 adb 安装都会弹
#   「正在通过USB自动安装以下应用 / <App 名> / 禁止 / 允许」
# 17 秒不点就自动拒绝，返回 INSTALL_FAILED_USER_RESTRICTED
# （系统日志：UsbInstallInterception: Stop the application to install through adb!）。
#
# 用法： bash tools/install.sh [apk 路径]
# 环境： ADB   adb 路径
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ADB="${ADB:-adb}"
APK="${1:-$DIR/../dist/spectrum-0.1.apk}"
[ -f "$APK" ] || { echo "no such apk: $APK" >&2; exit 1; }

LOG="$(mktemp)"
XML="$(mktemp)"

"$ADB" install -r "$APK" >"$LOG" 2>&1 &
PID=$!

tapped=0
for _ in $(seq 1 45); do
  kill -0 "$PID" 2>/dev/null || break
  sleep 1
  "$ADB" shell uiautomator dump /sdcard/ui_inst.xml >/dev/null 2>&1 || true
  "$ADB" shell cat /sdcard/ui_inst.xml >"$XML" 2>/dev/null || true
  COORD="$(python3 - "$XML" <<'PY'
import re, sys
try:
    xml = open(sys.argv[1], encoding='utf-8', errors='ignore').read()
except Exception:
    sys.exit(0)
for pat in (r'text="允许"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
            r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*?text="允许"'):
    m = re.search(pat, xml)
    if m:
        x1, y1, x2, y2 = map(int, m.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)"
  if [ -n "${COORD:-}" ]; then
    "$ADB" shell input tap $COORD
    tapped=$((tapped + 1))
    echo "auto-tapped 允许 at ($COORD)"
  fi
done

wait "$PID" 2>/dev/null || true
cat "$LOG"
[ "$tapped" -gt 0 ] && echo "(auto-tapped $tapped time(s))"
rm -f "$LOG" "$XML"
