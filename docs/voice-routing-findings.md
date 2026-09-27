# Narzo 50A routing comparison — 2026-09-27

## Evidence and limits

Reviewed the current-state audio-policy, AudioFlinger track and patch sections of
user-supplied `fx-off.txt` and `fx-on.txt` through the user's fallback download links.
The attachment paths were unavailable in this workspace. Raw dumps, unrelated app
history and download links are intentionally not included in the repository.

Capture times: **14:52:07 IST (off)** and **14:52:34 IST (on)** on 2026-09-27.
The on report contains active Voice Trial streams started around 14:52:27. This is a
useful active-trial snapshot, not a report taken after the trial had already stopped.
The requested capture used Unchanged voice; the reports do not expose the selected
Kotlin preset or independently establish that zero PCM was sent during the earlier
Silence check. Historical track entries must not be treated as current active paths
or assumed to correspond to a particular preset.

The user separately reports that original speech remains clearly audible during
Silence check, and that an effect arrives alongside original speech with roughly
0.4–0.7 seconds additional delay. That remains the failed device qualification result.
The reports do not contain a remote recording or an end-to-end latency measurement.

## Observed comparison

| Item | FX off | FX on |
|---|---|---|
| Policy phone state | `AUDIO_MODE_IN_CALL` | `AUDIO_MODE_CALL_REDIRECT` |
| Direct built-in mic (device 13) → Telephony TX (device 10) | Policy patch 1011 / AudioFlinger handle 4860 | Absent from current policy and AudioFlinger patch lists |
| Direct Telephony RX (device 16) → earpiece (device 2) | Policy patch 1009 / AudioFlinger handle 4852 | Absent from current patch list |
| Policy Audio sources | 2 native call sources | 0 native call sources |
| App microphone capture | No policy inputs | Active, built-in mic, 16 kHz mono PCM16, VOICE_COMMUNICATION / VOIP_TX |
| App downlink capture | No policy inputs | Active, Telephony RX, 16 kHz mono PCM16, VOICE_DOWNLINK |
| Active TX client | Native mic source | Voice Trial AudioTrack, 16 kHz mono, VOICE_COMMUNICATION |
| Output profile for TX | `incall_music_uplink` | `incall_music_uplink`, INCALL_MUSIC flag, 48 kHz stereo PCM32 |
| Earpiece relay | No active app relay | Active Voice Trial track on `voip_rx` |

In the on report, the five policy patches are:

- Output mix 13 → earpiece.
- Output mix 29 → earpiece.
- Output mix 37 → Telephony TX.
- Telephony RX → input mix 510 (app capture).
- Built-in mic → input mix 518 (app capture).

AudioFlinger's current patch list agrees. Its current microphone track is not marked
silenced, and both app playback tracks and both app recording tracks are active.
The microphone session has AEC, noise suppression and AGC enabled; these are not
selective dry-voice suppression controls. Stream activity is not proof of clean
remote replacement or perceptually correct downlink audio.

## What this rules out — and what it does not

- This is **not simply an unaccepted call-redirection mode request** in the captured trial.
- Android's managed native mic→TX patch is **not still present** in this snapshot.
  Releasing that same framework patch again is not an evidence-based fix.
- The app's required capture/playback routes are active. Port discovery is no longer
  the only evidence that those streams can open on this device.
- The BCP-style playback track still uses the vendor's in-call music output profile.
  That fact alone does not prove where the original speech is being mixed.
- Taken with the user's failed Silence check, this points investigation toward
  vendor/HAL/modem routing outside the displayed framework patch list. It does not
  identify a specific register, mixer control, or guaranteed application-only fix.
  Acoustic leakage and the exact state during the separate Silence check have not
  been independently ruled out by these two text snapshots.
- The privileged AOSP uplink API has different CALL_ASSISTANT/redirection attributes
  from the demo's BCP-style output. Trying it is a bounded experiment, not evidence
  that it bypasses this same vendor output profile or suppresses direct speech.

## Delay evidence

The active injected AudioTrack reports:

- 16,000 samples/s, mono.
- Frame capacity: 1,725 samples (about 107.8 ms of capacity).
- Frames ready: **1,664 samples**, about **104 ms queued at this instant**.
- AudioFlinger track latency estimate: **125.86 ms** (`k`-marked estimate).
- Policy TX profile latency: 85 ms; earpiece relay profile latency: 105 ms.

These are different, potentially overlapping estimates/capacities, not additive
end-to-end measurements. In particular, do not add 104 ms + 125.86 ms + 85 ms as
independent delays, or add the earpiece relay delay directly to the outbound path.
The microphone buffer's capacity is not its current queue depth: the captured mic
track shows zero frames ready. Likewise, historical underruns are not proof of a
current stall; the active outgoing track reports zero underruns in its current row.

There is measurable buffering to investigate after suppression. These numbers do
not fully explain or independently verify the user's 0.4–0.7 second estimate. The
pitch processor has additional bounded delay history, whereas this bypass capture
was requested specifically to separate routing from the pitch effect.

## Next gate

Do not ship a 'fixed' build based solely on a smaller buffer or a changed usage flag.
Do not apply guessed mixer writes, global microphone mute, persistent properties or
vendor XML changes. No such change has been made.

The vendor identity/string report has now been received; findings and the remaining
implementation-verification gate are below. No repeat of the string capture is needed.
Binary strings only establish that a name appears in a file; they do not prove the
control is supported, safe, independent of PCM capture, or reversibly queryable.
Any later selective-mute experiment needs explicit approval, known original state,
reliable restoration and a fresh successful remote Silence check before effects.


## Vendor string report received — 2026-09-27

The user supplied `audio-vendor.txt`; its complete text was reviewed. The report
finishes with `INSPECTION_COMPLETE` and identifies platform **mt6768**. Both
`/vendor/lib/hw/audio.primary.mt6768.so` and
`/vendor/lib64/hw/audio.primary.mt6768.so` contain the following names:

| Name family | Observed names | What is not established |
|---|---|---|
| Speech-call parameter candidates | `Set_SpeechCall_UL_Mute`, `Speech_UL_Mute`, `Speech_Mic_Mute` | Accepted syntax, handler mapping, readback and live semantics |
| Speech driver functions | `SpeechDriverNormal::SetUplinkMute(bool)`, `SpeechDriverNormal::SetUplinkSourceMute(bool)` | Which path each function actually mutes |
| Call controller functions | `AudioALSASpeechPhoneCallController::setUlMute(bool)`, `setMicMute(bool)` | Whether the first reaches source-only mute without touching PCM capture |
| Background/injection mixer | `Set_BGS_UL_Mute`, `SpeechPcmMixerBGSPlayer::setPcmMixerUlMute(bool)` and UL gain symbol | Independence from speech-source mute on the live modem path |
| Recovery-related strings | `vendor.audiohal.recovery.mic_mute_on`, `force unmute mic after phone call closed` | Reliable restoration for the candidate selective control, especially on crash |

This is a concrete device-specific lead for selective suppression, not a verified
fix. The same files also expose `SpeechDriverDummy` mute symbols: symbol presence
cannot prove which backend is selected or that a function does useful work.

Do **not** infer that `Set_SpeechCall_UL_Mute` calls `SetUplinkSourceMute` merely from
both names appearing in the report. Do not set a recovery property directly: a
property string is not evidence of a supported control API. Background-uplink mute
could instead silence the very injected effect we need to preserve.

The next step is offline inspection of copies of the installed 32-bit and 64-bit
primary HAL libraries, without executing or installing them. Review the parameter
parser, controller/driver calls, value interpretation, available getters and
reset/cleanup paths. Both architectures are requested because the string report
alone does not identify which service architecture is active. Copies are for private
analysis only, outside Git; they must not be bundled into a module or release.

Even a promising call graph does not establish live modem behavior. Before any
phone-side write experiment: require explicit approval, determine original state,
provide bounded restoration (including failures), and verify independent PCM input,
injected output, downlink and normal-call recovery. No vendor control has been
changed, no library patched, and no corrected APK/module built in this investigation.


## Library retrieval and upload cleanup

The user supplied the archive in a temporary branch commit. Both expected members
were retrieved and validated as ELF files, then kept outside the Git repository for
private offline analysis. The archive is 5,170,176 bytes; SHA-256:
`618d578b2078353f164e23e0ec6b94076d7cd4dfcdc85ac2186ef81cab27cda9`.

At the user's request, the upload-only commit was removed from the session branch
with an exact force-with-lease. Remote and local branch tips returned to `a8f0f89e`;
the remote archive path now returns 404. Existing source work was verified unchanged.
This is removal from branch history, not guaranteed purging of GitHub's cached or
otherwise referenced upload commit. No vendor binaries are part of the current
source tree or intended release assets.

Preliminary static inspection of the **64-bit** library confirms distinct compiled
state and dispatch for `SetUplinkMute` versus `SetUplinkSourceMute`. The controller's
`setUlMute` virtual call resolves to source mute in the normal driver's vtable.
The public parameter-handler mapping, live driver selection, independent microphone
capture and reliable state restoration are **not yet verified**. The 32-bit library
has been retained but not yet cross-checked. No phone control has been changed and
no replacement build is claimed fixed.
