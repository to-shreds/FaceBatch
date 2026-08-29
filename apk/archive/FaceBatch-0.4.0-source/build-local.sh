#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
TOOLS="$ROOT/tools"
APP="$ROOT/app/src/main"
BUILD="$ROOT/build/local"
OUT="$ROOT/dist"
AAPT2="${AAPT2:-$TOOLS/aapt2}"
ANDROID_JAR="${ANDROID_JAR:-$TOOLS/android-35.jar}"
R8_JAR="${R8_JAR:-$TOOLS/r8.jar}"
ZIPALIGN="${ZIPALIGN:-$TOOLS/zipalign}"
APKSIGNER_JAR="${APKSIGNER_JAR:-$TOOLS/apksigner.jar}"
KEYSTORE="${FACEBATCH_KEYSTORE:-}"
STOREPASS="${FACEBATCH_STOREPASS:-}"
KEYPASS="${FACEBATCH_KEYPASS:-$STOREPASS}"
ALIAS="${FACEBATCH_KEY_ALIAS:-facebatch}"

if [[ -z "$KEYSTORE" || -z "$STOREPASS" || -z "$KEYPASS" ]]; then
  echo "Set FACEBATCH_KEYSTORE, FACEBATCH_STOREPASS, and optionally FACEBATCH_KEYPASS and FACEBATCH_KEY_ALIAS." >&2
  echo "A new key is intentionally not generated because it would break update compatibility." >&2
  exit 2
fi
if [[ ! -f "$KEYSTORE" ]]; then
  echo "Signing keystore not found: $KEYSTORE" >&2
  exit 2
fi

export LD_LIBRARY_PATH="$TOOLS/lib64${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
rm -rf "$BUILD" "$OUT"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$OUT"

"$AAPT2" compile --dir "$APP/res" -o "$BUILD/resources.zip"
"$AAPT2" link \
  -o "$BUILD/resources.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$APP/AndroidManifest.xml" \
  --java "$BUILD/gen" \
  --min-sdk-version 29 \
  --target-sdk-version 34 \
  --version-code 10 \
  --version-name 0.4.0 \
  --auto-add-overlay \
  "$BUILD/resources.zip"

mapfile -t JAVA_FILES < <(find "$APP/java" "$BUILD/gen" -name '*.java' -type f | sort)
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
  -bootclasspath "$ANDROID_JAR" \
  -d "$BUILD/classes" \
  "${JAVA_FILES[@]}"

jar cf "$BUILD/classes.jar" -C "$BUILD/classes" .
java -cp "$R8_JAR" com.android.tools.r8.D8 \
  --release \
  --min-api 29 \
  --lib "$ANDROID_JAR" \
  --output "$BUILD/dex" \
  "$BUILD/classes.jar"

cp "$BUILD/resources.apk" "$BUILD/FaceBatch-unsigned.apk"
(cd "$BUILD/dex" && zip -q -j -u "$BUILD/FaceBatch-unsigned.apk" classes*.dex)
"$ZIPALIGN" -f -p 4 "$BUILD/FaceBatch-unsigned.apk" "$BUILD/FaceBatch-aligned.apk"

java -cp "$APKSIGNER_JAR" com.android.apksigner.ApkSignerTool sign \
  --ks "$KEYSTORE" \
  --ks-key-alias "$ALIAS" \
  --ks-pass "pass:$STOREPASS" \
  --key-pass "pass:$KEYPASS" \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --v4-signing-enabled false \
  --out "$OUT/FaceBatch-0.4.0.apk" \
  "$BUILD/FaceBatch-aligned.apk"

java -cp "$APKSIGNER_JAR" com.android.apksigner.ApkSignerTool verify \
  --verbose --print-certs "$OUT/FaceBatch-0.4.0.apk" \
  > "$OUT/signature-verification.txt"
java -cp "$APKSIGNER_JAR" com.android.apksigner.ApkSignerTool verify \
  --verbose --min-sdk-version 21 "$OUT/FaceBatch-0.4.0.apk" \
  > "$OUT/signature-all-schemes.txt"
"$ZIPALIGN" -c -p -v 4 "$OUT/FaceBatch-0.4.0.apk" > "$OUT/alignment.txt"
"$AAPT2" dump badging "$OUT/FaceBatch-0.4.0.apk" > "$OUT/badging.txt"
"$AAPT2" dump permissions "$OUT/FaceBatch-0.4.0.apk" > "$OUT/permissions.txt"
unzip -t "$OUT/FaceBatch-0.4.0.apk" > "$OUT/zip-test.txt"
sha256sum "$OUT/FaceBatch-0.4.0.apk" > "$OUT/sha256.txt"

echo "Built $OUT/FaceBatch-0.4.0.apk"
