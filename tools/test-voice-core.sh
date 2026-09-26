#!/usr/bin/env bash
# Requires a local Kotlin compiler/JDK, but no Android SDK, network, or phone.
set -euo pipefail
cd "$(dirname "$0")/.."
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
args=(-jvm-target 17)
# Embeddable compilers can compile normally but may not locate a distribution's runtime JAR.
# Supply its real stdlib explicitly instead of asking the compiler to create a fat JAR.
if [[ -n "${KOTLIN_STDLIB:-}" ]]; then
  [[ -f "$KOTLIN_STDLIB" ]] || { echo "KOTLIN_STDLIB does not exist." >&2; exit 1; }
  args+=(-no-stdlib -no-reflect -classpath "$KOTLIN_STDLIB")
else
  args+=(-include-runtime)
fi
kotlinc "${args[@]}" \
  app/src/main/kotlin/org/fossify/phone/voice/{VoiceEffectProcessor,PcmPipe,VoiceTrial,AudioModeLease}.kt \
  app/src/test/kotlin/org/fossify/phone/voice/VoiceCoreChecks.kt \
  tools/voice-tests/VoiceCoreMain.kt -d "$work/voice-tests.jar"
if [[ -n "${KOTLIN_STDLIB:-}" ]]; then
  java -cp "$work/voice-tests.jar:$KOTLIN_STDLIB" VoiceCoreMainKt
else
  java -jar "$work/voice-tests.jar"
fi
