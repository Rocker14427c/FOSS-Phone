# Experimental live call voice changer — implementation stage

**Status (2026-09-26): built, tested and published as v1.14.0-alpha1-voice-demo; device replacement remains unverified.**
The working v1.13.0-rc1 recording release is unchanged. No phone installation, SSH,
root command or audio-routing experiment was performed on the user's device.

[Download the experimental APK and matching Magisk module](https://github.com/Rocker14427c/FOSS-Phone/releases/tag/v1.14.0-alpha1-voice-demo).
The published artifacts were built from `775e835f3de41deea73a96416e49aaf518a00dcc`.
Subsequent documentation-only consolidation does not change those binaries.

## Branch history cleanup

The eight session-branch recording/workflow/fix/documentation commits were consolidated
into `19c76db2`, preserving the exact tree of the former tip `56e74f30`. The voice demo, owner workflow activation and AGP resource-generation fix
are consolidated in a separate coherent commit. Default-branch history and published release tags/assets
were not changed. No repeated workflow-troubleshooting commits were pushed.

## Selected references and licensing

1. **[Basic Call Player (BCP), chenxiaolong](https://github.com/chenxiaolong/BCP/tree/57977e5526b6ad3983d7db9c8a64236e6f1eff15)** —
   same author as BCR, GPLv3, archived technology demo.
   Reviewed `PlayerThread.kt`, permissions, manifest, README and LICENSE. It finds
   `AudioDeviceInfo.TYPE_TELEPHONY`, chooses it as an AudioTrack output, and sends audio
   to the other call party on devices implementing that port. It is a **playback**
   reference, not a complete microphone-replacement voice changer. `TelephonyPcmOutput`
   now adapts BCP's AudioTrack construction/routing directly, feeding it live processed
   microphone PCM instead of decoded file audio. Downlink extraction and exclusive
   redirection use Android SystemApis. No separate BCP app, service or decoder is needed.
   GPL license and adaptation notices are included in APK assets.
2. **[Soundpipe, Paul Batchelor](https://github.com/PaulBatchelor/Soundpipe/tree/3efb43bdabd0ed23b17c694292b5a79f1692a3ea)** — MIT.
   `modules/pshift.c` is the pitch-processing reference. Its interpolated two-delay-tap
   pitch core is adapted to Kotlin PCM16 with a much smaller sample-rate-sized ring,
   fixed output length, upward/downward pitch presets, switching and a transition ramp. The upstream MIT
   copyright/license is preserved in `app/src/main/assets/licenses/Soundpipe-MIT.txt`
   and thus included in APK assets. No native/NDK library, neural model or external
   audio app is required. Robot is an additional local ring-modulation effect.
3. **[AOSP Android 16 framework](https://github.com/aosp-mirror/platform_frameworks_base/tree/android-16.0.0_r1)** —
   reviewed `AudioManager`, `AudioService`, `ApplicationInfo` and `SystemConfig`:
   `isPstnCallAudioInterceptable`, `getCallUplinkInjectionAudioTrack`,
   `getCallDownlinkExtractionAudioRecord`, `MODE_CALL_REDIRECT`, privileged permission
   checks and per-system-package hidden-API exemptions. Native routing was also reviewed
   in LineageOS frameworks/av lineage-23.0; neither source proves Axion's vendor HAL behavior.

Voicesmith and TarsosDSP were considered as DSP references, but were not bundled:
adding a large FFT/native processing stack is unnecessary for the initial low-resource
call-routing test. No generic microphone-effects project was treated as proof of SIM-call injection.

## Device evidence versus unknowns

User's read-only report: Realme Narzo 50A, Axion 2.7 / Android 16 exposes
`AUDIO_DEVICE_OUT_TELEPHONY_TX` (port 10), `AUDIO_DEVICE_IN_TELEPHONY_RX` (port 16),
and the `CALL_ASSISTANT` strategy selects Telephony TX. The user confirms both integrated
recording and **BCP file playback heard on the other phone** work on this device.
BCP's remote playback is now device-confirmed by the user, not merely inferred from
port discovery. It does **not** prove simultaneous live microphone capture, exclusive
replacement of the original microphone path, or our complete two-way bridge.

## Implemented path

```
built-in mic -> VOICE_COMMUNICATION AudioRecord -> PCM16 DSP -> BCP-style AudioTrack -> Telephony TX
Telephony RX -> call downlink AudioRecord -> unchanged PCM16 -> voice-communication AudioTrack -> earpiece
```

This is not an effect on recorded mixed VOICE_CALL PCM, and uplink never deliberately
falls back to local speaker playback. `MODE_CALL_REDIRECT` requests replacement of
native telephony audio routing. Some vendor HALs may refuse it, fail to expose PCM,
or retain the dry microphone path. Only an actual consenting remote-party test can
establish that it works. The built-in **silence check** is specifically for finding
that last failure; Android microphone mute is never used as a workaround.

### Prototype boundaries

- Hidden and disabled in normal builds. Google Play flavor always disables it and
  removes its interception/mode/microphone permissions.
- Explicit consent for each trial. No remembered enable switch or automatic activation.
- Off restores normal calling by withdrawing this app's mode request, not by forcing a
  stale MODE_IN_CALL. All streams are released independently even after partial failures.
- Silence check: up to **5 seconds of active streaming**. Other trials: up to **30 seconds**.
  Switching Girl-like / Boy-like / Robot / Child-Chipmunk / Unchanged voice during a trial does not extend
  the deadline. Silence is a separate trial, never switched into or out of mid-stream.
- One worker, mono 16 kHz PCM16, 20 ms reused frame buffers, bounded non-blocking reads
  and partial writes. DSP does not allocate per frame. Startup, routing and PCM-stall
  checks stop on failure; the main-thread deadline can withdraw the mode request even
  if the audio worker is stuck in a vendor operation.
- Only one answered SIM call with known number, built-in mic and earpiece. Emergency
  numbers, network-identified emergencies, emergency callback mode, conferences, multiple
  calls, held calls, muted calls and unavailable/unknown call information are rejected.
- Stop on call/detail changes, mute, route changes, disconnect, call-service destruction,
  or leaving the call screen. No additional foreground or background service. Keep the
  screen visible during initial tests; lock/proximity behavior is not device-verified.
- **Recording and the voice trial are mutually exclusive for now.** Both features remain
  in the same app, but neither starts while the other's capture/finalization is active.
  Recording is not silently stopped, and does not silently resume when a trial ends.
- Girl-like (+5 semitones), Boy-like (-4 semitones), Robot (90 Hz ring modulation),
  Child/Chipmunk (+9 semitones).
  These are lightweight stylized effects, **not natural female/child neural conversion**.
  Pitch processing adds up to about 80 ms of delay history; total route latency, acoustic
  echo behavior, speech quality, CPU and battery use have not been measured on the phone.
- **Not voice-identity/privacy protection:** Off, cancellation and failure restore normal
  unmodified voice. An unsupported HAL might transmit dry voice even during a trial.

## In-call control

The small FX button has a **48dp touch target** and a short label showing the current
preset. It is placed at the top-right instead of adding a fourth row to the main
call-control grid. Tap to choose Off, Girl-like, Boy-like, Robot or Child/Chipmunk.
The same dialog includes Silence check and Unchanged voice diagnostics. Long-press
FX for Off. It is hidden for incoming/unanswered/ended calls and when the dialpad is
shown. Selection checks call eligibility again, not just when the button is drawn.

## Build/package separation protects the working recorder

An explicit lab build uses package **`org.fossify.phone.voice_trial.debug`**, launcher
label **FOSS Phone Voice Trial**, Magisk module ID **`fossify_phone_voice_trial`**, and
`system/priv-app/FossifyPhoneVoiceTrial/FossifyPhoneVoiceTrial.apk`.
It does not overwrite the original package, data or `fossify_phone_call_audio` module.
This also avoids attempting to update the installed recording APK with a different
CI-generated debug certificate. Future updates to the *trial* package still need a
matching key or a deliberate uninstall of that trial package only.

Both build and packaging opt-ins are required. In a provisioned JDK/Android SDK environment:

```sh
EXPERIMENTAL_VOICE_CHANGER=1 tools/build-recording.sh
```

The script runs Python packaging tests and Foss JVM tests, assembles Foss/Play debug
variants, verifies the APK certificate, embeds the exact APK, and compares the embedded
bytes. Outputs go to a new, empty `build/voice-trial-artifacts` directory. The local script does not
publish; the activated GitHub workflow separately publishes only after all checks pass. The script is configured; consult the build status below before treating artifacts as tested.

Equivalent key flags for manual build/package steps:

```sh
./gradlew :app:testFossDebugUnitTest :app:assembleFossDebug :app:assembleGplayDebug \
  -PexperimentalVoiceChanger=true
python3 tools/build_call_recording_module.py PATH_TO_LAB_APK NEW_OUTPUT.zip \
  --experimental-voice-changer --aapt2 "$ANDROID_HOME/build-tools/36.0.0/aapt2"
```

The module packager rejects an original-package APK in voice mode and rejects an
isolated voice APK in normal recording mode. Opt-in adds CALL_AUDIO_INTERCEPTION and
MODIFY_PHONE_STATE plus a **scoped system-package** hidden-API allowlist. Default
recording modules get none of these additions. No global hidden_api_policy change,
SELinux change, Zygisk/LSPosed injection, boot service or vendor mixer patch is used.

The voice workflow template is `ci/build-and-release-voice-demo.yml`. It builds with
`EXPERIMENTAL_VOICE_CHANGER=1`, verifies the actual isolated APK/module pair, and uses
`vVERSION-voice-demo`, never the v1.13.0-rc1 recording tag. The owner has
activated it as `.github/workflows/build-and-release-voice-demo.yml`.

## Build activation status

The owner activated `.github/workflows/build-and-release-voice-demo.yml` in commit
`e6f63302`. GitHub detected it and ran the voice job
[36259169221](https://github.com/Rocker14427c/FOSS-Phone/actions/runs/36259169221).
SDK setup and Python module tests succeeded. Gradle configuration then failed because
AGP 9 requires explicit `buildFeatures.resValues = true` for generated resources.
That setting was fixed. The next run,
[36259333901](https://github.com/Rocker14427c/FOSS-Phone/actions/runs/36259333901),
**succeeded in 4m52s**: Python tests, FossDebug JVM tests, FossDebug/GplayDebug APK builds,
certificate verification, byte-for-byte embedded APK verification, artifact uploads
and prerelease publication all passed. The APK is 33,422,906 bytes and the module ZIP
is 28,577,163 bytes. Both were verified as uploaded release assets.

The older `build-and-release-recorder.yml` remains enabled as well, so a push starts
both workflows. `RELEASE_KIND=voice-demo` deliberately blocks the older workflow at
packaging, preventing an effects-disabled recorder APK from being published as this
voice demo. The owner can disable the old recorder workflow in the Actions UI to avoid
redundant builds. Only **Build and release voice demo** publishes the demo package.

The GitHub App still cannot edit workflow files or manually dispatch runs; the active
owner-uploaded workflow is preserved unchanged. Source pushes trigger it automatically.

## Validation evidence

- **26 actual Kotlin/JVM core checks passed:** bit-exact bypass, immediate digital
  silence, pitch-frequency change for all three pitch presets, robot spectral sidebands, chunk-size
  invariance, rate/count validation, switching, partial/zero/failed PCM I/O, stall deadlines,
  mode cleanup including failed entry, retryable withdrawal and cancellation races, and fixed trial deadlines.
- The same checks are wired into `VoiceCoreTest` (JUnit) for the normal Gradle test task.
  `tools/test-voice-core.sh` runs them without an Android SDK when kotlinc/JDK are present.
  With an embeddable compiler, set `KOTLIN_STDLIB` to its actual stdlib JAR; this avoids
  relying on a full Kotlin distribution's runtime-bundling layout. This mode passed locally.
- **11 Python module tests passed**, including the existing recording tests, opt-in grants,
  isolated package/module paths, exact APK bytes, scoped XML, and absence of global patches.
- Audio engine/access adapter compiled against Android 36's public API jar. A prior controller check used lightweight *app-dependency stubs*; this is not
  an actual app integration build. JDK 17 plus a development Kotlin 2.4 compiler were used
  for local checks, not the exact Gradle toolchain. No fake APK or device result was generated.
- Changed XML parsed; shell scripts syntax-checked; whitespace diff checked.
- **Full Gradle build/resource merge and FossDebug unit tests passed in CI** using
  the real Android dependencies; this supersedes the earlier local stub-only checks.
- **Still required on device:** permission/hidden-API access, both-way audibility, absence of dry voice, return to normal
  sound, emergency/hold/route/lock behavior and recorder regression checks.

## Optional device test with the published demo

Build verification is complete; it is not evidence of remote audibility or original-voice suppression.

1. Keep the working recording APK/module and recordings. Install only a verified isolated
   trial APK and its exact matching trial module; reboot, then explicitly choose the trial
   app as default dialer for testing. No separate BCP, Soundpipe or recorder app is needed.
2. Use a nonessential SIM call to a consenting person on another phone. Earpiece only;
   stop recording and BCP playback first. Have the other person ready to report what they hear.
3. Select **Silence check**, continue speaking for the brief trial, and confirm the other
   person cannot hear your voice while you still hear them. Original voice audible means
   **failed replacement**: stop testing effects, do not mark the device supported.
4. Test Unchanged voice: both parties should hear each other, then normal audio returns.
5. Tap the compact **FX / Voice** button at the top-right of the answered-call screen.
   Test Girl-like, Boy-like, Robot and Child/Chipmunk. Confirm the *remote party*, not just you, hears the
   change. Switch presets, choose Off (or long-press FX), and verify normal audio. Do not test real emergencies.
6. Verify ordinary recording separately after returning to the original dialer. Do not
   claim simultaneous recording/effects support, Bluetooth/speaker support or battery savings.
7. If sound fails to return, end the test call. Restore the original default dialer;
   disable/remove only `fossify_phone_voice_trial` and reboot if necessary. The working
   recorder module/app were not replaced by the trial package.

Promotion from timed trials to an ordinary ongoing-call feature is blocked on these
hardware results, not merely on a successful compilation.
