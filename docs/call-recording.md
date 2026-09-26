# Integrated call recording

Calling, recording, playback, sharing and deletion are all in **this Phone APK**. There is no
runtime dependency on the BCR app. The new backend follows BCR's raw-call-audio architecture;
see the [commit and BCR source review](call-recording-review.md) for the exact revision and
what was retained/replaced.

## Required installation

This feature requires:

1. A `core`/`foss` build installed as a privileged system app with
   `android.permission.CAPTURE_AUDIO_OUTPUT` actually granted.
2. Microphone permission (`RECORD_AUDIO`) granted by the user.
3. This app selected as the default Phone app so its existing Telecom `InCallService` receives
   call lifecycle events. Allow notifications so recording status remains visible.
4. A ROM/vendor audio HAL that exposes usable `VOICE_CALL` audio.

A rooted phone with a **normally installed APK** does not meet requirement 1. Root-manager
approval, default-dialer status, a microphone grant or a plain `pm grant` command does not
replace the privileged installation. There is intentionally **no microphone fallback**: on
modern Android it can produce a file containing neither participant's voice.

The target use case includes a Realme Narzo 50A on Axion 2.7 / Android 16, but that device/ROM
has **not been tested here**. Some ROMs/routes expose no mixed call audio or only one direction.
This implementation cannot fix missing vendor audio routing just by requesting permission.

## Build and install the same APK as a privileged module

The opt-in module builder packages **this dialer APK**, not BCR, under `system/priv-app`, plus
its package-specific allowlist under `system/etc/permissions` on the same partition. It does
not disable SELinux, grant all permissions, patch the HAL or run a root audio daemon.

1. Build a signed `foss`/`core` APK from this branch. For local testing:

   ```sh
   ./gradlew :app:testFossDebugUnitTest :app:assembleFossDebug
   ```

   Debug builds use `org.fossify.phone.debug`; releases use `org.fossify.phone`. Keep signing
   keys consistent with the installed version. Do not uninstall an existing install just to
   bypass a signature mismatch: uninstalling can delete private recordings. Back up important
   recordings and retain the currently installed APK before changing system-app integration.
2. Install the APK normally first and confirm that it opens and works as your default dialer.
   Allow the microphone and notifications. Recording will explain that privileged access is
   still missing. The module must contain this **same signed APK**.
3. On a computer with Python 3.9+ and Android SDK build-tools:

   ```sh
   python3 tools/build_call_recording_module.py \
     /path/to/signed-foss-phone.apk \
     build/fossify-call-audio.zip \
     --aapt2 "$ANDROID_HOME/build-tools/36.0.0/aapt2"
   ```

   Adjust the build-tools version to your installation. The script reads the package ID from
   the APK, verifies the requested capture permission and recording feature flag, rejects the
   `gplay` flavor, and refuses to overwrite an existing ZIP.
4. Copy the ZIP to the phone. In **Magisk → Modules → Install from storage**, install it and
   reboot. This installer targets Magisk, not recovery. KernelSU/APatch installation and
   system-mount mechanisms have not been verified; do not assume compatibility.
5. Verify the actual grant with ADB:

   ```sh
   adb shell pm path org.fossify.phone
   adb shell dumpsys package org.fossify.phone
   ```

   Substitute `.debug` as appropriate. Look for system/updated-system-app status,
   `PRIVILEGED`, and specifically
   `android.permission.CAPTURE_AUDIO_OUTPUT: granted=true` under **install permissions**.
   A line under **requested permissions** is not enough. An updated system app's code path
   can still be under `/data/app`; the granted permission is decisive.
6. Enable **Automatically record calls** in Settings or use the in-call recording button.
   After a consenting test call, play the recording after hanging up and check both voices.

### Building on GitHub

`ci/build-and-release-recorder.yml` is currently an **inactive template**: GitHub rejected
uploading it under `.github/workflows` because the Arena GitHub App lacks `workflows` permission,
even after reconnecting. No new APK/module release has been built or published in this session.

To activate it, an authorized repository owner must copy this template to
`.github/workflows/call-recording-checks.yml` **on `arena/01a0dcd1-foss-phone`** using GitHub's UI,
or restore workflow-write access to the Arena connection. Its branch push trigger will then run;
manual dispatch is also supported. Do not put it on a different branch, since publication is
restricted to this session branch. It tests PCM handling, builds `foss` and `gplay`, and packages the `foss` **debug APK
and its matching module ZIP** as one artifact. On this session branch, successful checks publish a prerelease with matching APK/module, checksums and signing-certificate information. Existing releases are never overwritten. CI-generated
debug signing keys can differ between runs: these artifacts are for testing, not stable
updates to a release-signed installation.

### Rollback

Disable/remove **Fossify Phone privileged call audio** in Magisk and reboot. Keeping the
matching normal APK installed first makes rollback simpler; reselect your preferred dialer
if necessary. Know your root manager's module-disable/recovery procedure before installing a
system-app module in case your ROM fails to boot. The module does not permanently rewrite a
system partition. Rebuild the module when updating its bundled APK and avoid duplicate
system-app modules for the same package.

## Capture and file behavior

- A worker thread reads **mono PCM16 from `AudioRecord` / `VOICE_CALL`**, using nonblocking reads.
  Settings offers **Light** (8 kHz), **Balanced** (16 kHz), or **High detail** (48 kHz). Fallbacks
  only reduce the chosen rate, never increase Light mode's workload. A higher setting cannot
  improve the original call signal. No MIC/VOICE_COMMUNICATION or external app fallback exists.
- Android low-RAM devices default to Light; other devices default to Balanced. Settings changes
  are snapshotted for the next recording, never applied halfway through a file.
- **No extra recording service:** the existing Telecom `CallService` takes a temporary started,
  microphone-foreground lifetime while one worker captures/finalizes. `BIND_INCALL_SERVICE` and
  a per-session nonce protect start requests. Start/record/save states are distinct; stopping
  never waits for device input or file I/O on the UI thread. After finalization, foreground
  mode and the started lifetime end; Telecom can keep the usual bound call service for a call.
  There is no recorder at boot, periodic background job, or sticky process-death restart.
- Buffers are reused. Light/Balanced/High poll at 100/80/50 ms respectively instead of the earlier
  20 ms loop. Input buffers cover at least four read batches to tolerate scheduling delays.
  One raw PCM worker avoids codec work and additional threads. These are design reductions,
  **not measured battery or low-end-device performance claims**.
- One file covers overlapping calls, swaps and conferences. When no call is active but calls
  are held, the stream is drained without saving held audio. Disconnecting the last live call
  requests stop immediately, rather than waiting for the call-screen activity to disappear.
- Manual stop and capture failures suppress automatic restart for that call session. A user
  can explicitly retry. A new call can queue an automatic start while an old file finishes.
- PCM is stored as lossless `.wav` in app-private `Recordings/`. Light uses **0.96 MB/minute**,
  Balanced **1.92 MB/minute**, and High up to **5.76 MB/minute**. Lower-rate fallbacks use less. No compression formats or
  separate uplink/downlink stereo mode are included in this first replacement backend.
- Temporary `.inprogress` files use UUID names. WAV sizes are finalized and synced before the
  file is renamed into the recordings list. No nonzero samples means no published recording.
  Existing `.m4a` recordings remain accessible, and sharing uses the appropriate MIME type.
- Explicit platform silencing for several seconds, read errors, and ten seconds of *no frames*
  are reported as capture failures. An ordinary quiet pause still returns zero-valued frames
  and is not treated as a hung source. Hold resets these checks. All-silent input is detected
  from actual samples at finalization, not from encoded file size or a successful API call.
- Audio captured before an error can be saved as a partial recording, with a warning. Failed
  finalization/rename is not announced as a successful save. Nonzero samples still cannot prove
  intelligibility or that both participants are present; device testing remains necessary.

Recordings may include personal information in their contents and caller-label filenames.
The optional beep is **local**, not a guaranteed remote-party notification. Notify participants
yourself and follow local consent laws.

## Device validation and troubleshooting

Test both participants speaking separately with speakerphone **off** and the optional beep
disabled. Replay after hangup. Repeat incoming/outgoing calls, speaker/Bluetooth, Wi-Fi calling,
screen off, hold/resume, swap/conference, manual stop, auto-record, rapid successive calls and
very short calls. Unsupported routes may still fail even when ordinary cellular recording works.

If privileged permission is missing after reboot, inspect the module mount, package ID,
signature and allowlist. If permission is granted but `VOICE_CALL` fails or remains one-sided,
collect diagnostics for the ROM maintainer. Review/redact numbers and other private data before
sharing:

```sh
adb logcat -d -s CallRecordingManager CallService CallAudioRecorder AudioRecord AudioPolicyManager
adb shell dumpsys audio
```

Contributor checks:

```sh
python3 -m unittest discover -s tools -p 'test_*.py' -v
./gradlew :app:testFossDebugUnitTest :app:assembleFossDebug :app:assembleGplayDebug
```

A real-device regression pass and successful APK build are still required before describing
this implementation as production-ready.
