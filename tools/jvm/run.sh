#!/usr/bin/env bash
# 跑 DSP 探针（纯 Java，不需要设备，也不需要 Android SDK）。
#
# 文档 docs/设计文档.md §4.1 的表格就是这条命令的输出。
# 环境变量：DSH_APK_TOOLCHAIN 工具链根目录（默认 ~/.cache/dsh-apk）
set -euo pipefail

ROOT="${DSH_APK_TOOLCHAIN:-$HOME/.cache/dsh-apk}"
JAVA_HOME="$ROOT/jdk"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

[ -x "$JAVA_HOME/bin/javac" ] || { echo "缺少 JDK: $JAVA_HOME/bin/javac" >&2; exit 1; }

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

echo "== javac"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -d "$OUT" "$DIR"/*.java

echo "== run"
"$JAVA_HOME/bin/java" -cp "$OUT" DspProbe
