#!/usr/bin/env bash
# ReCalm — build & sign the APK with raw SDK tools (no Gradle needed).
set -euo pipefail
cd "$(dirname "$0")"

JDK="$HOME/toolchains/jdk21"
SDK="$HOME/android-sdk"
BT="$SDK/build-tools/36.0.0"
PLAT="$SDK/platforms/android-35/android.jar"
export PATH="$JDK/bin:$PATH"

rm -rf build
mkdir -p build/classes build/dex build/gen

echo "[1/6] aapt2 compile resources"
"$BT/aapt2" compile --dir res -o build/res.zip

echo "[2/6] aapt2 link (manifest + resources + assets)"
"$BT/aapt2" link -o build/app.base.apk \
  -I "$PLAT" \
  --manifest AndroidManifest.xml \
  -R build/res.zip \
  --auto-add-overlay \
  -A assets \
  --java build/gen \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code 13 --version-name 2.8

echo "[3/6] javac"
javac -source 11 -target 11 -Xlint:-options -nowarn \
  -classpath "$PLAT" \
  -d build/classes \
  build/gen/com/personal/reclaim/R.java \
  src/com/personal/reclaim/MainActivity.java \
  src/com/personal/reclaim/BlockerService.java \
  src/com/personal/reclaim/ReelsShieldService.java \
  src/com/personal/reclaim/BootReceiver.java

echo "[4/6] d8 dex"
"$BT/d8" --release --lib "$PLAT" --min-api 24 --output build/dex \
  build/classes/com/personal/reclaim/*.class

echo "[5/6] package classes.dex + zipalign"
( cd build/dex && zip -qjX ../app.base.apk classes.dex )
"$BT/zipalign" -f 4 build/app.base.apk build/app.aligned.apk

echo "[6/6] sign with personal keystore"
if [ ! -f reclaim.keystore ]; then
  keytool -genkeypair -keystore reclaim.keystore -alias reclaim \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass reclaimpersonal -keypass reclaimpersonal \
    -dname "CN=ReCalm Personal, OU=Personal, O=Personal"
fi
"$BT/apksigner" sign --ks reclaim.keystore --ks-key-alias reclaim \
  --ks-pass pass:reclaimpersonal --key-pass pass:reclaimpersonal \
  --out ReCalm.apk build/app.aligned.apk

"$BT/apksigner" verify --print-certs ReCalm.apk | head -5
echo ""
echo "BUILD OK -> $(pwd)/ReCalm.apk ($(du -h ReCalm.apk | cut -f1))"
