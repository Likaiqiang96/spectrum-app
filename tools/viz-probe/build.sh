#!/usr/bin/env bash
# 构建 Visualizer 可行性探针 APK（无 Gradle / 无 AndroidX，aapt2+javac+d8+zipalign+apksigner）。
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${DSH_APK_TOOLCHAIN:-$HOME/.cache/dsh-apk}"
JAVA_HOME="$ROOT/jdk"
SDK="$ROOT/sdk"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
KS_PASS="vizprobe"
KS_ALIAS="vizprobe"
KS_FILE="$DIR/keystore/vizprobe.keystore"

BUILD="$DIR/build"
DIST="$DIR/dist"

for tool in "$JAVA_HOME/bin/javac" "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner"; do
  [ -x "$tool" ] || { echo "missing tool: $tool" >&2; exit 1; }
done
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$BUILD/gen" "$DIST"

echo "== compile res"
"$BT/aapt2" compile --dir "$DIR/app/res" -o "$BUILD/res.zip"

echo "== link"
"$BT/aapt2" link \
  -o "$BUILD/app-unsigned.apk" \
  -I "$PLATFORM" \
  --manifest "$DIR/app/AndroidManifest.xml" \
  -R "$BUILD/res.zip" \
  --java "$BUILD/gen" \
  --min-sdk-version 24 \
  --target-sdk-version 27 \
  --version-code 1 \
  --version-name 0.1 \
  --auto-add-overlay

echo "== javac"
javac -encoding UTF-8 -source 8 -target 8 -nowarn \
  -classpath "$PLATFORM" \
  -d "$BUILD/classes" \
  $(find "$DIR/app/src" "$BUILD/gen" -name '*.java')

echo "== d8"
"$BT/d8" --lib "$PLATFORM" --min-api 24 --output "$BUILD/dex" \
  $(find "$BUILD/classes" -name '*.class')

echo "== package dex"
(cd "$BUILD/dex" && zip -q -X "$BUILD/app-unsigned.apk" classes.dex)

echo "== zipalign"
"$BT/zipalign" -f -p 4 "$BUILD/app-unsigned.apk" "$BUILD/app-aligned.apk"

if [ ! -f "$KS_FILE" ]; then
  mkdir -p "$(dirname "$KS_FILE")"
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS_FILE" -alias "$KS_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=VizProbe, OU=Local, O=DSH, L=Local, ST=Local, C=CN" >/dev/null
fi

OUT="$DIST/viz-probe-0.1.apk"
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
"$BT/aapt2" dump badging "$OUT" | grep -E "^(package|launchable-activity|uses-permission|sdkVersion|targetSdkVersion)" || true
ls -lh "$OUT"
