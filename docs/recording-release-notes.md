## Integrated recording — test release

Calling, recording, playback and sharing are in **one Phone APK**. No BCR app is needed.
The capture design uses BCR-style `AudioRecord` / `VOICE_CALL` PCM, not a microphone fallback.

### Build verification

[Build run 36237438282](https://github.com/Rocker14427c/FOSS-Phone/actions/runs/36237438282) passed
for source commit `0102a7e9d939f858bdfbcf2015ffeb26ca6ab1e1`: Python packaging tests,
FossDebug unit tests, FossDebug/GplayDebug builds, APK signature verification and exact
comparison of the APK embedded in the module. This is not a real-device audio or battery test.

### Choose what suits your phone

- **Light:** smallest files and least processing/data movement; about **1 MB/minute**.
- **Balanced:** clear speech; about **2 MB/minute**. Recommended on most phones.
- **High detail:** up to **6 MB/minute**, only useful if the call source has more detail.

All choices save WAV to avoid extra codec work. Light is the default on Android low-RAM devices;
otherwise Balanced is the default. Battery improvements have not been measured on a phone.

### Low-overhead design

Reuses the existing Telecom call service—**no second recording service**. One worker is active
only while capturing/saving. Reusable small buffers, batched nonblocking reads, no timer jobs or
boot recorder. The service's started/foreground lifetime is released after saving; Telecom may
still keep the normal call service bound while a call is ongoing. Held audio is skipped, stop is
asynchronous, silent files are rejected, and partial capture errors are reported.

### Install

1. Back up recordings. Install **FOSS-Phone-test.apk** and select this test app as your default
   Phone app. Allow microphone and notifications.
2. In **Magisk → Modules → Install from storage**, install **FOSS-Phone-Magisk.zip** and reboot.
   The ZIP contains the **exact same APK**, installed with its privileged permission allowlist.
3. Confirm `CAPTURE_AUDIO_OUTPUT: granted=true` in `adb shell dumpsys package org.fossify.phone.debug`.
4. Choose recording quality in Settings. Make a consenting test call, have each person speak,
   then replay after hanging up to verify both voices.

Disable/remove the module and reboot to roll back. Know your root manager's recovery/module-disable
procedure before installing any system-app module. Do not flash this ZIP through recovery.
KernelSU/APatch compatibility has not been established.

### Important limitations

- **Not verified on Realme Narzo 50A / Axion 2.7 Android 16.** Tests/build checks do not prove
  that a device's vendor audio HAL supplies both voices. Bluetooth/Wi-Fi calling may differ.
- Privileged installation and ROM support are mandatory. A normally installed rooted APK is
  insufficient. No SELinux disabling or root recording daemon is used.
- This prerelease is **debug-signed**, package **org.fossify.phone.debug**, not an in-place update
  to the old `org.fossify.phone` release. It can coexist with that app; choose the new default
  dialer yourself. CI debug keys can differ across builds, so future test updates may need a
  fresh installation. Export private recordings first; uninstalling may delete them.
- `SHA256SUMS.txt`, `APK-CERTIFICATE.txt` and `BUILD-INFO.txt` accompany the assets.
- [Updated installation and rollback guide](https://github.com/Rocker14427c/FOSS-Phone/blob/8226a94/docs/call-recording.md).
- Follow consent laws. The optional local beep is not guaranteed to reach the other person.
