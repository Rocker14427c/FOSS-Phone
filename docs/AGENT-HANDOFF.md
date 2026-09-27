# FOSS Phone — handoff to the next agent

**Prepared 27 September 2026. Read this before changing the phone or publishing another build.**

## 1. Executive summary: what works and what does not

The user wants one lightweight cellular dialer with integrated call recording and live
voice effects heard by the other caller. Device: **Realme Narzo 50A, Axion 2.7 official
custom ROM, Android 16, MediaTek MT6768**.

- **Recording:** implemented, built and published; the user confirms it works on their phone.
- **Voice-effect injection:** implemented, built and published as a separate experimental
  trial identity. The user confirms the other caller hears the changed voice.
- **Voice replacement: FAILED.** The other caller hears original speech first, then the
  changed copy approximately **0.4–0.7 seconds later** (user estimate, not instrumented).
- **Silence check: FAILED by user report.** Original speech remains clearly audible during
  the 5-second check. Do not describe this as a working voice changer/privacy feature.
- Actual off/on dumps show Android enters `CALL_REDIRECT` and removes its managed native
  mic→Telephony TX patch. Investigate the remaining vendor/HAL/modem path; do not simply
  claim the framework patch is still present.
- Vendor binaries have been obtained. Preliminary **64-bit static inspection** found
  distinct source-mute and uplink-mute state/dispatch. Parameter mapping, independent PCM
  capture, backend selection and reliable restoration are still unresolved.
- **No suppression fix or latency fix has been implemented. No corrected APK exists.**
- The assistant never established a working phone connection, installed/flashed anything,
  or changed phone routing. The user performed installation/tests/read-only captures.

The immediately useful next work is the HAL call-graph/recovery investigation and an
explicitly approved, reversible device experiment—not more pitch presets.

## 2. Repository, branches, releases and exact source identities

Repository: <https://github.com/Rocker14427c/FOSS-Phone>

**Continue from `arena/01a0dcd1-foss-phone`, not `main`.** Main does not contain the later
working recorder implementation or live voice demo.

At handoff preparation, runtime source HEAD is `a8f0f89e894dfa04cdea6586f4cf8715509160b2`.
This handoff and the later diagnostic findings are documentation-only additions after
that runtime source. Check the live branch tip rather than assuming a historic SHA is HEAD.

| Item | Exact identity / outcome |
|---|---|
| Working recorder release | [`v1.13.0-rc1`](https://github.com/Rocker14427c/FOSS-Phone/releases/tag/v1.13.0-rc1), version code 25 |
| Recorder binary source | `0102a7e9d939f858bdfbcf2015ffeb26ca6ab1e1` |
| Recorder CI | [36237438282](https://github.com/Rocker14427c/FOSS-Phone/actions/runs/36237438282), successful |
| Experimental voice release | [`v1.14.0-alpha1-voice-demo`](https://github.com/Rocker14427c/FOSS-Phone/releases/tag/v1.14.0-alpha1-voice-demo), code 26 |
| Voice binary source | `775e835f3de41deea73a96416e49aaf518a00dcc` |
| Voice CI | [36259333901](https://github.com/Rocker14427c/FOSS-Phone/actions/runs/36259333901), successful in 4m52s |
| Consolidated recorder commit | `19c76db2`, preserving the earlier working tree; parent `990cdacc` |
| Consolidated voice/source-doc commit | `a8f0f89e`, parent `19c76db2`; runtime source matches the published voice build, later changes there were documentation |

The voice release has `FOSS-Phone-test.apk` (33,422,906 bytes), matching
`FOSS-Phone-Magisk.zip` (28,577,163 bytes), `SHA256SUMS.txt`, `APK-CERTIFICATE.txt`,
`BUILD-INFO.txt`, and `INSTALL.md`. Check checksums/signatures from that release, not
filenames alone: both releases use the same generic asset names.

### The older branch the user asked about

`arena/01a0d398-foss-phone` points to `90033fb0ee27967bea8cd3492f83e42065e8de7e`.
It predates this session. Its tip is authored under the user's identity with an
`arena-agent` co-author, so it is earlier Arena-assisted work, not a branch created
during this session. This session started from that tip on a different branch.

Verified on 27 September 2026:

- [PR #2](https://github.com/Rocker14427c/FOSS-Phone/pull/2) merged that older branch into
  `main` on 24 September 2026, merge commit `66b6e11de54a68445c2d7bcc45e33c4c43042496`.
- Old branch and main have the **identical file tree**:
  `843b67ed5966626443f47cf03d1db8350e9372fd`.
- Tag `v1.12.0` points to the exact old branch tip `90033fb...`.
- No open PRs existed at this check.

**Deleting the older branch ref is safe for preserving Git code/history at these tips.**
Keep `main`, the release tags and especially `arena/01a0dcd1-foss-phone`. Branch deletion
can affect an old workspace/session link; no claim is made about another platform's
session-retention behavior. No branch was deleted by this assistant. Recheck tips if
someone has pushed since this report.

### History cleanup and the temporary HAL upload

The user requested fewer unnecessary session commits. Eight recorder/workflow/fix commits
were consolidated to one recorder commit, and voice work to one additional feature commit.
Published release tags stayed pinned to their actual binary-source commits; main was not
rewritten. Distinct tagged source commits therefore remain even though the branch is tidy.

The user temporarily uploaded `audio-hal-review.tar` in commit
`8fd3495e359847fc29c04249ee87b66b0f68ea71`, directly on the current branch. That commit
added only the archive. After retrieving/validating it, the assistant used an exact
force-with-lease to restore the branch to `a8f0f89e`, as explicitly requested. The archive
path returned 404 at the branch ref. **Do not re-add vendor binaries to Git or releases.**
Removing a branch commit does not purge all GitHub cached/direct-commit/Actions references.

## 3. User requirements and operational boundaries

- Calling, recording and eventual voice effects in **one app**, no BCR/BCP runtime dependency.
- Preserve the working recorder, recordings, original module and ordinary calling.
- Low resource use: no neural models, heavyweight FFT stack, extra always-on audio service
  or unsupported battery/performance claims. The user wants real builds, not setup loops.
- Requested in-call mini FX control and Girl, Boy, Robot, plus a fourth popular effect.
  Fourth chosen: **Child / Chipmunk**. Girl-like/Boy-like are stylized pitch effects,
  **not natural AI voice/gender conversion**.
- The remote cellular caller must hear the effect. Local playback or changed recording
  files alone do not satisfy the request.
- Separate trial package/module is for development isolation, not a separate permanent
  effects-app dependency. The user should keep their original working installation.
- Privileged module builder targets Magisk layout. Root-manager identity was not reliably
  established in this conversation; do not claim KernelSU/APatch/other managers tested.
- Read-only connection permission is not permission to flash modules, edit vendor files,
  change mixer values, weaken SELinux, alter SSH settings, or run unrestricted root actions.
- User initiates consenting nonessential test calls. Never test real emergency calls.
- Do not store credentials or publish phone logs, proprietary libraries or live tunnel details.

## 4. Recording implementation delivered

The earlier recording backend was replaced with BCR-style raw call PCM capture,
streaming WAV output and one-service lifecycle integration. No BCR APK is required.

Key files under `app/src/main/kotlin/org/fossify/phone/`:

- `recording/CallAudioRecorder.kt`: privileged call-audio worker.
- `recording/PcmCaptureLoop.kt`: reused-buffer PCM capture loop.
- `recording/PcmWaveWriter.kt`: streaming WAV creation/finalization.
- `recording/RecordingQuality.kt`: Light/Balanced/High detail choices; low-RAM default.
- `helpers/CallRecordingManager.kt`: permissions, recording state and coordination.
- `services/CallService.kt`: existing call service handles recording foreground lifecycle.
- Activities/settings/config/notifications/receivers integrate automatic/manual capture,
  quality choice and recording playback/sharing/deletion.

Light/Balanced/High detail approximate sizes: 1/2/6 MB per minute. No battery savings
were measured. Old M4A recordings remain playable. Missing `CAPTURE_AUDIO_OUTPUT`
is an explicit failure, not a silent microphone-only fallback. A normally installed
APK on a rooted phone does not by itself grant privileged call capture.

Working recorder package: `org.fossify.phone.debug`.
Working recorder module ID: `fossify_phone_call_audio`.
The user confirmed this published recording release works; this is user evidence,
not assistant-operated phone validation.

## 5. Live voice demo: architecture, files and exact limits

### Audio graph

```text
built-in mic → VOICE_COMMUNICATION AudioRecord → PCM16 effect
             → BCP-style VOICE_COMMUNICATION AudioTrack → Telephony TX
Telephony RX → privileged downlink AudioRecord → unchanged earpiece AudioTrack
```

`MODE_CALL_REDIRECT` is requested before streams start. The engine checks acceptance,
actual routed device IDs, mute/capture state and PCM progress. That is **not** proof of
exclusive replacement; the remote Silence check was deliberately the suppression gate.

All files below are under `app/src/main/kotlin/org/fossify/phone/voice/`:

| File | Purpose |
|---|---|
| `VoiceEffectProcessor.kt` | In-place bounded PCM processing; silence, bypass, three pitch effects and Robot |
| `TelephonyPcmOutput.kt` | BCP-style public AudioTrack construction and preferred Telephony TX device |
| `CallAudioAccess.kt` | Privileged permission/port/mode checks and reflection for Android SystemApi downlink |
| `CallVoiceEngine.kt` | 16 kHz mono bidirectional bridge, 20 ms frames, route checks and cleanup |
| `PcmPipe.kt` | Nonblocking partial-read/write handling with one pending frame and stall detection |
| `VoiceTrial.kt` | Finite trial deadlines; switching cannot extend them |
| `AudioModeLease.kt` | Synchronized ownership/withdrawal; failed withdrawal can be retried |
| `VoiceChangerManager.kt` | Main-thread OFF/STARTING/TRIAL/STOPPING coordination and call/lifecycle watchdogs |

UI: `CallActivity.kt`, `activity_call.xml`, `ic_voice_effect_vector.xml` and strings.
Top-right 48dp button plus 72dp label; tap chooser, long-press Off. The chooser has
four presets plus Off, Silence check and Unchanged voice routing diagnostics.

Effects: Girl-like **+5 semitones**, Boy-like **−4**, Robot **90 Hz ring modulation**,
Child/Chipmunk **+9**. Soundpipe-derived two-tap pitch shifting uses 40 ms windows,
at most about 80 ms history and a 5 ms switching ramp. Silence is immediately zero,
without a ramp. No deliberate dry-signal mix is implemented in the DSP.

Runtime restrictions:

- Android 13+ for trials; privileged module, required permissions, default-dialer setup.
- Opt-in build only; normal build and Google Play flavor leave the feature disabled.
- One active, known-number, nonemergency SIM call. No conference/held/multiple calls.
- Built-in microphone and earpiece only; no speaker, Bluetooth or wired-route support.
- Call unmuted, recording and BCP playback stopped, UI resumed to start/switch.
- Silence lasts 5 seconds of active streaming; other trials 30 seconds.
- Recording and effects mutually exclusive; recording is not silently resumed.
- Off, leaving the call screen, route/mute/call changes, failures or deadline stop effects.
- Revoke this app's mode request **before** releasing native streams. Do not force a stale
  IN_CALL state. Normal voice returns on Off/failure: this is not voice privacy protection.
- No vendor mute parameter or selective-mute implementation exists in the current engine.

### Isolated install identities

Trial package: `org.fossify.phone.voice_trial.debug`.
Label: **FOSS Phone Voice Trial**. Module: `fossify_phone_voice_trial`.
APK entry: `system/priv-app/FossifyPhoneVoiceTrial/FossifyPhoneVoiceTrial.apk`.

Opt-in module grants include `CALL_AUDIO_INTERCEPTION` and `MODIFY_PHONE_STATE`, plus
scoped `hidden-api-whitelisted-app` sysconfig. There is no global hidden-API policy
change, SELinux weakening, vendor patch, Zygisk hook or boot audio daemon.
Debug signing is ephemeral in CI. A future trial update might require a trial-only
uninstall with recording backups. **Never uninstall the working recorder to solve a
trial signature conflict.**

## 6. References and licensing

Project is GPLv3. Reviewed implementation references:

- [BCR](https://github.com/chenxiaolong/BCR/tree/c55b8ba5ab35a6dad35f205f265ab4f39f468b50)
  for raw privileged call recording architecture.
- [BCP](https://github.com/chenxiaolong/BCP/tree/57977e5526b6ad3983d7db9c8a64236e6f1eff15),
  GPLv3, for Telephony TX AudioTrack injection. The user separately confirmed BCP file
  audio is heard remotely. BCP does **not** establish live mic replacement or suppression.
- [Soundpipe](https://github.com/PaulBatchelor/Soundpipe/tree/3efb43bdabd0ed23b17c694292b5a79f1692a3ea),
  MIT, `modules/pshift.c`, adapted to bounded Kotlin PCM processing.
- AOSP framework `android-16.0.0_r1`: AudioManager, AudioService, SystemConfig;
  LineageOS frameworks/av `lineage-23.0`: reference native routing. These are not proof
  of the installed Axion/vendor HAL behavior.

License texts/adaptation notices are in `app/src/main/assets/licenses/`.
TarsosDSP/Voicesmith were considered, not adopted or bundled. Do not claim their
source/licenses were fully reviewed as part of this implementation.

## 7. Device diagnosis already completed — do not restart collection blindly

See [voice-routing-findings.md](voice-routing-findings.md) for the detailed evidence
and [voice-routing-diagnostics.md](voice-routing-diagnostics.md) for the collection commands.

The user supplied:

1. `fx-off.txt` — 27 September, 14:52:07 IST.
2. `fx-on.txt` — 14:52:34 IST, active trial started around 14:52:27.
3. `audio-vendor.txt` — MT6768 vendor names/functions.
4. `audio-hal-review.tar` — exact installed 32/64-bit primary HAL libraries.

### Off/on evidence

- Mode changes from `AUDIO_MODE_IN_CALL` to `AUDIO_MODE_CALL_REDIRECT`.
- Native mic device 13 → TX device 10 patch 1011/AF4860 is present off, absent on.
- Native RX device 16 → earpiece device 2 patch 1009/AF4852 also disappears.
- Native policy audio sources: 2 off, 0 on.
- App microphone (VOICE_COMMUNICATION/VOIP_TX) and downlink captures are active at 16 kHz.
- App TX playback is active on **`incall_music_uplink` / INCALL_MUSIC**, hardware
  format 48 kHz stereo PCM32. Earpiece relay uses `voip_rx`.
- AudioFlinger agrees with the policy patch list. Current mic track is not marked silenced.
- Microphone AEC/NS/AGC are enabled; these are not direct-speech suppression controls.

This rules out a simply refused mode request in that snapshot. Combined with the user's
failed Silence check, it directs investigation below the displayed framework patch list.
It does not prove a particular mixer control, backend, or hardware impossibility.
The requested dump used **Unchanged voice**, not simultaneous verification of zero
outgoing PCM during the earlier Silence check. Acoustic leakage was not independently
ruled out; use separated test phones for later confirmation. Do not mistake historical
track logs for active streams or identify their presets without evidence.

### Delay evidence

Active TX AudioTrack: 1,725-sample capacity; **1,664 frames ready at 16 kHz ≈ 104 ms queued**;
AudioFlinger latency estimate 125.86 ms; policy TX profile latency 85 ms.
These measurements overlap: **do not add them** into an invented end-to-end total.
Mic capture queue was zero frames ready; its 100 ms capacity is not proof of 100 ms
queued audio. Current TX track reports zero underruns. User-reported total additional
delay still needs instrumentation. Smaller buffers do not fix original-voice leakage.

### Vendor and preliminary binary evidence

Both installed `audio.primary.mt6768.so` libraries contain speech-call, background-mixer,
normal-driver and dummy-driver mute names. Symbol strings are not proof of active support.
The archive is available in the **private handoff bundle**, not the Git repository.

Checksums:

| File | SHA-256 |
|---|---|
| Original tar, 5,170,176 bytes | `618d578b2078353f164e23e0ec6b94076d7cd4dfcdc85ac2186ef81cab27cda9` |
| `lib/hw/audio.primary.mt6768.so`, 2,542,925 bytes | `8f52575718ed688b29460af2310f4802e06192e18429e021b1aa8dfb981a1ece` |
| `lib64/hw/audio.primary.mt6768.so`, 2,625,009 bytes | `745afa24f31b31c622dc0591cbea2fc279dde486fe19cfd86c01daa3c5b62f9a` |

Preliminary 64-bit disassembly findings (virtual addresses, not file offsets):

- `AudioALSASpeechPhoneCallController::setUlMute(bool)` at `0x139b00` writes controller
  byte `+0x57`, gets the active speech driver, invokes vptr slot `+0x198`.
- Normal-driver vtable starts `0x2223f8`, address point `+0x10`; table entry `+0x1a8`
  resolves to `SetUplinkSourceMute(bool)` at `0xffd18`.
- Controller passes `vendor.audiohal.recovery.ul_mute_on` to `set_uint32_to_mixctrl`.
  Despite the property-like name, this observed call is **not evidence to use setprop**.
- `SetUplinkSourceMute` updates driver byte `+0x17`, invokes virtual dispatch with
  message argument `0x2f08`.
- Distinct `SetUplinkMute` at `0xffae8` updates byte `+0x16`, dispatch argument `0x2f02`.
- This establishes distinct compiled state/dispatch and the normal-vtable mapping;
  it does **not** yet map `Set_SpeechCall_UL_Mute` to the controller or prove mixing order.

Offline tooling was Python `pyelftools` + `capstone`; ELF uses Android APS2 packed RELA,
RELR in-place addends and ordinary PLT relocations. A basic disassembly annotation helper
was used, not a complete control/data-flow analyzer. Validate important findings with
raw instructions/vtables/relocations or a mature disassembler. No vendor code was executed.
32-bit code was retained but **not cross-checked yet**.

Still unresolved: public parameter parser→controller mapping; boolean/value semantics;
usable getter/readback; which backend/service architecture is live; whether suppression
leaves PCM capture and injected playback working; restoration on Off/error/death/end-call.
Never toggle `Set_BGS_UL_Mute` or global mic mute as a guessed fix; those could silence
the processed voice too. Do not write recovery properties or assume a success return
means the vendor honored an unknown parameter.

## 8. Build, test and release procedure

Toolchain configured: JDK 17; SDK platform 36; build-tools 36.0.0; Gradle 9.7.1;
AGP 9.4.1; Kotlin plugin 2.4.10; compile/target 36, app min 26, voice trial min 33.
`buildFeatures.resValues = true` is required by this AGP configuration.

```sh
python3 -m unittest discover -s tools -p 'test_*.py' -v
# If a compatible Kotlin compiler/JDK is available:
tools/test-voice-core.sh
# Full real build with Android SDK/JDK; OUTPUT_DIR must be empty/new:
EXPERIMENTAL_VOICE_CHANGER=1 OUTPUT_DIR=build/new-voice-artifacts tools/build-recording.sh
```

Full build script tests FossDebug, builds FossDebug and GplayDebug, verifies the APK
certificate, builds the module and compares embedded APK bytes exactly. Main scripts:
`tools/build-recording.sh`, `tools/build_call_recording_module.py`,
`tools/android-build/Dockerfile`. Docker environment is defined; successful end-to-end
build evidence is the GitHub runner, not a claimed completed local Docker build.

Validation achieved:

- Recorder build: 24 recording Kotlin tests / 5 Python packaging tests and real builds.
- Voice core: 26 Kotlin checks (DSP frequencies/sidebands, silence/bypass, buffers,
  partial writes, stalls, mode-lease cleanup and deadlines), wired into JUnit.
- Packaging now: 11 Python tests across recorder/trial packaging and wrong-flavor guard.
- Voice CI passed real dependencies, resources, unit tests, both APK builds,
  certificate/embedded-byte verification, artifacts and prerelease publication.
- None of those tests establish hardware dry-voice suppression, recovery or performance.

### Workflow pitfalls already resolved / still relevant

- Owner activated `.github/workflows/build-and-release-voice-demo.yml`; placement is
  correct and it ran successfully. **Do not ask the owner to activate it again.**
- SDK setup already fixed to request `platform-tools`, not obsolete `tools`.
- First voice run failed because AGP resValues generation was disabled; source fixed it.
- Both old recorder and new voice workflows remain. `RELEASE_KIND=voice-demo` deliberately
  blocks legacy recorder packaging to avoid publishing an effects-disabled APK as demo.
- Existing voice tag is immutable by workflow policy: it refuses overwriting its assets.
  For a real next release, increment version/code/changelog and use a new prerelease tag.
  A source push at the same version can pass build/tests and fail existing-tag publication.
- Docs-only handoff uses `[skip ci]`; no new binary is built by that commit.
- This session's GitHub integration could push source but could not edit workflow files
  or manually dispatch (403). Do not bypass credentials restrictions; owner UI edits
  were used. A different agent should assess its own authorized capabilities.
- `gh` redirected Actions log downloads failed EOF in this environment. Fresh signed
  job-log URLs were readable through web fetching; do not reuse expired URLs.

## 9. Phone access: no working connection was achieved

Read-only SSH attempts failed before authentication. The user subsequently supplied
manual root diagnostic outputs. No remote phone pairing or command session succeeded.
A temporary allowlisted HTTPS bridge prototype passed mocked tests but live external
connectivity failed (preview-token barrier, tunnel failures). It was stopped and the
unpublished prototype removed. Do not advertise or resurrect it as tested remote access.

The user's earlier SSH connection procedure and diagnostic source links are in the
**private packet's PHONE-ACCESS.md**, not public source. No password is stored or included.
A new agent must establish an actual authorized connection and verify it with read-only
checks before claiming access. Do not modify SSH configuration or `authorized_keys`.
User permission to investigate is not blanket installation/flashing/routing approval.

Sandbox HTTP/TLS downloads frequently failed. Text web fetching read the diagnostic
reports, but could not deliver the binary tar. The temporary GitHub upload finally
allowed binary retrieval. Attachment paths also failed to materialize in some turns.
Do not ask the user to repeat all those upload/encoding steps: the private packet now
contains the verified tar. Local caches/outside-repo files were not reliably available
across turns; download the packet rather than relying on an old absolute path.

## 10. Recommended next-agent sequence

1. Clone the **current work branch**; read this handoff and findings. Keep known-working
   recorder artifacts as rollback. Verify archive hashes from the private packet.
2. Establish authorized read-only phone access, if needed. Determine actual audio HAL
   service architecture/build and installed package/module state without changing it.
3. Complete the HAL parameter→controller→driver trace and identify state readback and
   reset semantics. Check 32-bit as well as 64-bit. Distinguish normal vs dummy backend.
4. Design a bounded, explicitly approved selective-source-mute experiment. Preserve
   original state; restoration must handle Off, cancellation, call end, error and process
   failure. Do not silently add a persistent mixer/property hack to the recording module.
5. Instrument selected preset, mode, routes and zero-output status (no call audio/numbers
   in logs). Prove remote silence while retaining microphone PCM and audible downlink,
   using consenting participants with separated phones. Then verify unchanged voice.
6. Only after suppression succeeds, enable effects and measure capture/output backlog,
   HAL buffering and end-to-end latency. Tune based on measurements, not buffer capacity.
7. Test recovery, ordinary recording separately, call/route/background transitions and
   low-end performance. Do not claim simultaneous recording/effects or other routes yet.
8. Build/test a new isolated prerelease with preserved licenses and correct identities;
   get explicit approval for installation. Report actual outcomes, including failures.

### A short prompt to give the next agent

> Continue Rocker14427c/FOSS-Phone on arena/01a0dcd1-foss-phone. Read docs/AGENT-HANDOFF.md
> and docs/voice-routing-findings.md first. Recording v1.13.0-rc1 works on my Narzo 50A /
> Axion 2.7 Android 16. The voice demo reaches the remote caller but leaks original
> speech and adds delay; Silence check failed. Android CALL_REDIRECT is active and its
> direct mic→TX patch disappears. The private packet contains my actual MT6768 HAL
> libraries. Finish the selective-source-mute mapping/readback/recovery investigation;
> do not guess mixer writes or declare the existing demo fixed. Establish authorized
> phone access before claiming it; get my approval before installing or changing routing.
