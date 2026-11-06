#!/usr/bin/env bash
set -euo pipefail

# Android SDK platform 35, build-tools 35.0.0, Python 3, zip, and JDK 17+.
# ECJ_JAR is optional when the Java installation lacks javac.
root="$(cd "$(dirname "$0")" && pwd)"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$sdk" ]]; then echo "Set ANDROID_SDK_ROOT to your Android SDK." >&2; exit 1; fi
platform="${RING_ATLAS_PLATFORM_JAR:-$sdk/platforms/android-35/android.jar}"
tools="${RING_ATLAS_BUILD_TOOLS:-$sdk/build-tools/35.0.0}"
out="${1:-$root/out/RingAtlas-Android.apk}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes" "$work/dex" "$(dirname "$out")"
mapfile -d '' sources < <(find "$root/src" -name '*.java' -print0 | sort -z)
python3 - "$platform" "$work/android-compile.jar" <<'PY'
import sys
from zipfile import ZipFile
with ZipFile(sys.argv[1]) as sdk, ZipFile(sys.argv[2], 'w') as compiler:
    for name in sdk.namelist():
        if name.endswith('.class') and not name.startswith(('java/', 'javax/', 'sun/', 'jdk/', 'org/w3c/', 'org/xml/sax/')):
            compiler.writestr(name, sdk.read(name))
PY
if [[ -n "${ECJ_JAR:-}" ]]; then
    java -jar "$ECJ_JAR" -17 -proc:none -warn:none -cp "$work/android-compile.jar" -d "$work/classes" "${sources[@]}"
else
    javac --release 17 -proc:none -cp "$work/android-compile.jar" -d "$work/classes" "${sources[@]}"
fi
"$tools/aapt2" compile --dir "$root/res" -o "$work/resources.zip"
"$tools/aapt2" link -I "$platform" --manifest "$root/AndroidManifest.xml" -o "$work/unsigned.apk" "$work/resources.zip"
mapfile -d '' classes < <(find "$work/classes" -name '*.class' -print0 | sort -z)
"$tools/d8" --release --min-api 31 --lib "$platform" --output "$work/dex" "${classes[@]}"
(cd "$work/dex" && zip -q -X -j ../unsigned.apk classes.dex)
"$tools/zipalign" -f -p 4 "$work/unsigned.apk" "$work/aligned.apk"
if [[ -n "${RING_ATLAS_KEYSTORE:-}" && -n "${RING_ATLAS_KEYSTORE_PASSWORD_FILE:-}" ]]; then
    "$tools/apksigner" sign --ks "$RING_ATLAS_KEYSTORE" --ks-key-alias "${RING_ATLAS_KEY_ALIAS:-ring-atlas}" \
        --ks-pass "file:$RING_ATLAS_KEYSTORE_PASSWORD_FILE" --v4-signing-enabled false --out "$out" "$work/aligned.apk"
    "$tools/apksigner" verify "$out"
    printf 'Signed APK: %s\n' "$out"
else
    cp "$work/aligned.apk" "$out"
    printf 'Unsigned APK: %s. Sign it before installing.\n' "$out"
fi
