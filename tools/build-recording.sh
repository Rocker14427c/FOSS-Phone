#!/usr/bin/env bash
# Build real tested artifacts; never package a stale APK or publish an unbuilt module.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-}}"
if [[ -z "$ANDROID_HOME" ]]; then
    echo "Android SDK missing. Build with tools/android-build/Dockerfile or set ANDROID_HOME." >&2
    exit 1
fi
export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"
tools="$ANDROID_HOME/build-tools/36.0.0"
command -v java >/dev/null || { echo "JDK 17 is required." >&2; exit 1; }
command -v python3 >/dev/null || { echo "Python 3.9+ is required." >&2; exit 1; }
for executable in aapt2 apksigner; do
    [[ -x "$tools/$executable" ]] || { echo "Install Android build-tools;36.0.0." >&2; exit 1; }
done
gradle_args=(-PexperimentalVoiceChanger=false)
module_args=()
guide=docs/call-recording.md
package=org.fossify.phone.debug
apk_entry=system/priv-app/FossifyPhone/FossifyPhone.apk
default_output="$root/build/recording-artifacts"
case "${EXPERIMENTAL_VOICE_CHANGER:-0}" in
    0) ;;
    1)
        gradle_args=(-PexperimentalVoiceChanger=true)
        module_args=(--experimental-voice-changer)
        guide=docs/voice-changer.md
        package=org.fossify.phone.voice_trial.debug
        apk_entry=system/priv-app/FossifyPhoneVoiceTrial/FossifyPhoneVoiceTrial.apk
        default_output="$root/build/voice-trial-artifacts"
        echo "Building isolated EXPERIMENTAL voice trial; not a validated voice-changing release."
        ;;
    *) echo "EXPERIMENTAL_VOICE_CHANGER must be 0 or 1." >&2; exit 1 ;;
esac
out=${OUTPUT_DIR:-"$default_output"}
mkdir -p "$out"
if [[ -n "$(find "$out" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
    echo "Choose an empty OUTPUT_DIR; existing artifacts will not be overwritten: $out" >&2
    exit 1
fi
python3 -m unittest discover -s tools -p 'test_*.py' -v
./gradlew :app:testFossDebugUnitTest :app:assembleFossDebug :app:assembleGplayDebug "${gradle_args[@]}" --no-daemon --stacktrace
apk=$(find app/build/outputs/apk/foss/debug -maxdepth 1 -name '*.apk' -print -quit)
[[ -n "$apk" ]] || { echo "The build did not produce an APK." >&2; exit 1; }
"$tools/apksigner" verify --print-certs "$apk" > "$out/APK-CERTIFICATE.txt"
cp "$apk" "$out/FOSS-Phone-test.apk"
python3 tools/build_call_recording_module.py "$apk" "$out/FOSS-Phone-Magisk.zip" --aapt2 "$tools/aapt2" "${module_args[@]}"
python3 - "$out" "$apk_entry" <<'PY'
import sys
from pathlib import Path
from zipfile import ZipFile
out = Path(sys.argv[1])
with ZipFile(out / 'FOSS-Phone-Magisk.zip') as module:
    assert module.read(sys.argv[2]) == (out / 'FOSS-Phone-test.apk').read_bytes()
PY
cp "$guide" "$out/INSTALL.md"
printf 'Package: %s\nSigning: debug test key; not a stable release update\n' "$package" > "$out/BUILD-INFO.txt"
(cd "$out" && sha256sum *.apk *.zip > SHA256SUMS.txt)
printf 'Build and tests completed. APK and matching Magisk module: %s\n' "$out"
