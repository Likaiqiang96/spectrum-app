#!/usr/bin/env bash
# 构建频谱仪 APK。无 Gradle / 无 AndroidX / 无第三方库 / 无 NDK：
# aapt2 + javac + d8 + zipalign + apksigner 就是全部管线。
#
# 环境变量：
#   DSH_APK_TOOLCHAIN  工具链根目录（默认 ~/.cache/dsh-apk）
#   BT_VERSION         build-tools 版本（默认 34.0.0）
#   APP_VERSION        versionName（默认 0.1）
#   DSH_KS_PASS        keystore / key 口令（默认 spectrum）
set -euo pipefail

PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT="${DSH_APK_TOOLCHAIN:-$HOME/.cache/dsh-apk}"
JAVA_HOME="$ROOT/jdk"
SDK="$ROOT/sdk"
BT_VERSION="${BT_VERSION:-35.0.0}"   # 34.0.0 自带 R8 8.2.2-dev 会崩，用 35 的 d8
BT="$SDK/build-tools/$BT_VERSION"
PLATFORM="$SDK/platforms/android-34/android.jar"
KS_PASS="${DSH_KS_PASS:-spectrum}"
KS_ALIAS="${DSH_KS_ALIAS:-spectrum}"
KS_FILE="$PROJECT/keystore/spectrum.keystore"
APP_VERSION="${APP_VERSION:-0.1}"

BUILD="$PROJECT/build"
DIST="$PROJECT/dist"

for tool in "$JAVA_HOME/bin/javac" "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner"; do
  [ -x "$tool" ] || { echo "missing tool: $tool" >&2; exit 1; }
done
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

echo "== clean"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$BUILD/gen" "$DIST"

echo "== icons"
python3 "$PROJECT/tools/make-icons.py" "$PROJECT/app/res" >/dev/null

echo "== compile resources"
"$BT/aapt2" compile --dir "$PROJECT/app/res" -o "$BUILD/res.zip"

echo "== link (also generates R.java)"
"$BT/aapt2" link \
  -o "$BUILD/app-unsigned.apk" \
  -I "$PLATFORM" \
  --manifest "$PROJECT/app/AndroidManifest.xml" \
  -R "$BUILD/res.zip" \
  --java "$BUILD/gen" \
  --min-sdk-version 24 \
  --target-sdk-version 27 \
  --version-code 1 \
  --version-name "$APP_VERSION" \
  --auto-add-overlay

[ -f "$BUILD/gen/com/spectrum/app/R.java" ] || { echo "R.java was not generated" >&2; exit 1; }

echo "== javac"
javac -encoding UTF-8 --release 8 -nowarn \
  -classpath "$PLATFORM" \
  -d "$BUILD/classes" \
  $(find "$PROJECT/app/src" "$BUILD/gen" -name '*.java')

echo "== d8"
"$BT/d8" --lib "$PLATFORM" --min-api 24 --output "$BUILD/dex" \
  $(find "$BUILD/classes" -name '*.class')

echo "== package dex"
(cd "$BUILD/dex" && zip -q -X "$BUILD/app-unsigned.apk" classes.dex)

echo "== zipalign"
"$BT/zipalign" -f -p 4 "$BUILD/app-unsigned.apk" "$BUILD/app-aligned.apk"

if [ ! -f "$KS_FILE" ]; then
  echo "== keystore"
  mkdir -p "$(dirname "$KS_FILE")"
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS_FILE" -alias "$KS_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=Spectrum, OU=Local, O=DSH, L=Local, ST=Local, C=CN" >/dev/null
fi

OUT="$DIST/spectrum-$APP_VERSION.apk"
rm -f "$OUT" "$OUT.idsig"
echo "== sign"
"$BT/apksigner" sign \
  --ks "$KS_FILE" --ks-key-alias "$KS_ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --v1-signing-enabled true --v2-signing-enabled true \
  --v4-signing-enabled false \
  --out "$OUT" "$BUILD/app-aligned.apk"

echo "== verify"
"$BT/apksigner" verify --print-certs "$OUT" | head -3
"$BT/aapt2" dump badging "$OUT" | grep -E "^(package|launchable-activity|uses-permission|sdkVersion|targetSdkVersion|application-label)" || true
ls -lh "$OUT"
echo
echo "APK: $OUT"
