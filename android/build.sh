#!/usr/bin/env bash
# Builds and signs android/build/chowa-remote.apk with Android SDK command-line tools (no Gradle).
#
# Environment overrides:
#   ANDROID_HOME          Android SDK root (default: $HOME/Library/Android/sdk)
#   BUILD_TOOLS_VERSION   SDK build-tools version (default: 35.0.0)
#   PLATFORM              SDK platform providing android.jar (default: android-34)
#   JAVA_HOME             JDK 17 root (default: /opt/homebrew/opt/openjdk@17 if present)
#   KEYSTORE              signing keystore (default: $HOME/.android/debug.keystore,
#                         created with the standard debug credentials if missing)
#   KEYSTORE_PASSWORD     keystore password (default: android)
#   KEY_ALIAS             key alias (default: androiddebugkey)
#   KEY_PASSWORD          key password (default: KEYSTORE_PASSWORD)
#   VERSION_CODE / VERSION_NAME
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BUILD_TOOLS="$SDK/build-tools/${BUILD_TOOLS_VERSION:-35.0.0}"
ANDROID_JAR="$SDK/platforms/${PLATFORM:-android-34}/android.jar"
if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@17 ]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@17
fi
if [ -n "${JAVA_HOME:-}" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi
KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
export KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-android}"
KEY_ALIAS="${KEY_ALIAS:-androiddebugkey}"
export KEY_PASSWORD="${KEY_PASSWORD:-$KEYSTORE_PASSWORD}"
OUT="$ROOT/build"

for tool in "$BUILD_TOOLS/aapt2" "$BUILD_TOOLS/d8" "$BUILD_TOOLS/zipalign" "$BUILD_TOOLS/apksigner" "$ANDROID_JAR"; do
    [ -e "$tool" ] || { echo "missing: $tool" >&2; exit 1; }
done
command -v javac >/dev/null || { echo "missing: javac (JDK 17)" >&2; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/assets" "$OUT/dex"
cp "$ROOT/../web/index.html" "$OUT/assets/index.html"

"$BUILD_TOOLS/aapt2" compile --dir "$ROOT/res" -o "$OUT/res.zip"
"$BUILD_TOOLS/aapt2" link \
    -I "$ANDROID_JAR" \
    --manifest "$ROOT/AndroidManifest.xml" \
    -A "$OUT/assets" \
    --java "$OUT/gen" \
    --min-sdk-version 28 \
    --target-sdk-version 28 \
    --version-code "${VERSION_CODE:-1}" \
    --version-name "${VERSION_NAME:-1.0}" \
    -o "$OUT/unsigned.apk" \
    "$OUT/res.zip"

# javac argfiles split on whitespace unless each path is quoted.
find "$ROOT/src" "$OUT/gen" -name '*.java' | sed 's/.*/"&"/' > "$OUT/sources.txt"
javac --release 8 -classpath "$ANDROID_JAR" -Xlint:-options -d "$OUT/classes" @"$OUT/sources.txt"

(cd "$OUT/classes" && jar cf "$OUT/classes.jar" .)
"$BUILD_TOOLS/d8" --release --min-api 28 --lib "$ANDROID_JAR" --output "$OUT/dex" "$OUT/classes.jar"
(cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes.dex)

"$BUILD_TOOLS/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -storepass:env KEYSTORE_PASSWORD -keypass:env KEY_PASSWORD \
        -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US"
fi
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-pass env:KEYSTORE_PASSWORD \
    --ks-key-alias "$KEY_ALIAS" --key-pass env:KEY_PASSWORD \
    --out "$OUT/chowa-remote.apk" "$OUT/aligned.apk"

echo "$OUT/chowa-remote.apk"
