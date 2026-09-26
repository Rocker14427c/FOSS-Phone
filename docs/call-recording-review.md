# Review of the four recent author commits

Reviewed author: `Rocker14427c`. The working branch originally ended at `90033fb`; GitHub's
fourth recent author entry is the merge `66b6e11`, whose second parent is that commit. It is
not a fourth independent implementation change on this branch.

| Commit | Assessment | Action |
| --- | --- | --- |
| `438c7680cdc9` — add privileged call recording | Correctly identifies `CAPTURE_AUDIO_OUTPUT` / `VOICE_CALL`, but silently falls back to MIC. Recorder startup does not prove capture; Android can encode silence. No PCM inspection, background recording lifetime or asynchronous finalization. | Replaced the entire `MediaRecorder` backend with in-process `AudioRecord` PCM capture. Removed microphone fallback. |
| `155bbc6eedba` — “production ready” | Useful private-storage, recordings UI, manual-stop and notification improvements. The production-ready claim is not supported: `stop()` only checks encoder success, nonempty files can be silent, rename failure still returns success, filenames can collide within a second, and the beep is not guaranteed to reach the remote caller. Capture also has no foreground-service lifetime. | Retained useful dialer/UI integration; replaced capture/lifecycle/storage-success handling. Added UUID names, direct sample checks, explicit failure results, temporary microphone foreground lifetime on the existing call service, and truthful beep text. |
| `90033fb0ee27` — release metadata/workflow | Version bump itself is fine. `ci/build-release-apk.yml` is only a template: GitHub does not execute workflows outside `.github/workflows`. Its ephemeral signing fallback is unsuitable for stable updates. No device evidence justifies releasing the earlier engine as reliable two-way recording. | Kept version metadata to avoid breaking updates. Prepared `ci/build-and-release-recorder.yml` for tests and debug APK/module artifacts; activation is blocked by GitHub workflow permission. The old release template is not silently activated or used to publish a release. |
| `66b6e11de54a` — merge PR #2 | Contains the changes above, not a separate recording design. Not an ancestor of this session's original tip. | No duplicate merge revert and no history rewrite. |

**Updated history plan, requested by the owner:** replace the three implementation commits in
this session branch's ancestry with a clean implementation starting at `990cdaccde27`. Retain
useful UI code in the replacement, not the old capture engine. The fourth entry is a merge on
the default branch, not an ancestor here; it cannot be deleted by rewriting this session branch.
No other branch or existing GitHub release is rewritten/deleted. Existing saved recordings
remain readable. New prereleases must not claim untested ROM compatibility.

## BCR source review

Reference revision: [`chenxiaolong/BCR@c55b8ba5ab35a6dad35f205f265ab4f39f468b50`](https://github.com/chenxiaolong/BCR/tree/c55b8ba5ab35a6dad35f205f265ab4f39f468b50).
Relevant files:

- [`RecorderThread.kt`](https://github.com/chenxiaolong/BCR/blob/c55b8ba5ab35a6dad35f205f265ab4f39f468b50/app/src/main/java/com/chiller3/bcr/RecorderThread.kt): `AudioRecord`, PCM16, worker thread, oversized input buffer, nonblocking reads, pure-silence inspection and discard-on-hold. The code explicitly explains that blocking reads can hang on some devices at call end.
- [`RecorderInCallService.kt`](https://github.com/chenxiaolong/BCR/blob/c55b8ba5ab35a6dad35f205f265ab4f39f468b50/app/src/main/java/com/chiller3/bcr/RecorderInCallService.kt): Telecom callbacks and foreground recording/finalization state.
- [`AudioSource.kt`](https://github.com/chenxiaolong/BCR/blob/c55b8ba5ab35a6dad35f205f265ab4f39f468b50/app/src/main/java/com/chiller3/bcr/format/AudioSource.kt): call-specific sources; the reviewed version also supports separate uplink/downlink modes.
- [`WaveFormat.kt`](https://github.com/chenxiaolong/BCR/blob/c55b8ba5ab35a6dad35f205f265ab4f39f468b50/app/src/main/java/com/chiller3/bcr/format/WaveFormat.kt): WAV/PCM with a default 16 kHz sample rate.
- [`AndroidManifest.xml`](https://github.com/chenxiaolong/BCR/blob/c55b8ba5ab35a6dad35f205f265ab4f39f468b50/app/src/main/AndroidManifest.xml): privileged capture permission, runtime microphone permission and microphone foreground-service type.

BCR is GPL-3.0-only, copyright Andrew Gunnerson and its other contributors. This implementation
is newly written for this dialer's lifecycle, using those architectural ideas, not a vendored
BCR APK/library or a wholesale copy of BCR's source. No BCR app, provider, service, broadcast
protocol or command-line executable is used at runtime.

### Deliberate scope

- One mono `VOICE_CALL` stream per overlapping-call session. The first caller label is only a
  filename hint; it does not imply that a conference/swapped recording contains only that person.
- PCM16/WAV first, not BCR's full codec/format/rule/retention feature set. Existing M4A files are
  still listed, playable and shareable.
- No separate uplink/downlink stereo mode yet, no VoIP capture and no unprivileged microphone
  workaround. If the ROM does not expose a usable mixed `VOICE_CALL` stream, recording fails
  clearly instead of pretending to capture a call.
- Privileged system installation is still mandatory. Integrating recording into the dialer does
  not remove Android's permission or vendor audio-HAL requirements.

## Verification status

Python packaging tests, module shell syntax checks, XML/resource-reference checks and
`git diff --check` are runnable in this workspace. Kotlin tests cover raw capture cancellation,
short reads, hold/resume, source errors, policy silencing, no-data timeout and WAV finalization.

The workspace has no Java/Android SDK. Attempts to install/download a toolchain were blocked by
network access, so the Kotlin tests and full APK builds could not be executed here. The CI template provides those checks once an authorized owner activates it; GitHub refused workflow upload even after reconnection. No claim is made that
this has been tested on the Realme Narzo 50A / Axion 2.7 Android 16 audio HAL.
