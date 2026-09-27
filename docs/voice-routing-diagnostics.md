# Read-only routing diagnosis after failed Silence check

## What is established

The user reports original speech is clearly audible during the demo's 5-second
Silence check, and Girl-like output arrives alongside the original with an estimated
0.4–0.7 second delay. The current demo fails the dry-microphone suppression gate on
the reported Realme Narzo 50A / Axion 2.7 setup. Changing pitch or reducing buffers
cannot by itself eliminate that direct path.

No phone commands have been run by the assistant. The procedure below is optional,
read-only diagnosis, **not a fix**. The original two-dump capture has now produced
usable reports from the phone; the vendor-inspection command below is not yet phone-tested. It does not
record audio, run mixer writes, change properties, edit vendor files, change SELinux,
install packages or weaken security policy. It only saves diagnostic text locally.
Reports may contain app/device identifiers; review them before sharing privately.
Do not publish an unrestricted bugreport, call logs, credentials or recordings.

## Capture result received

The user supplied both reports on 2026-09-27. They show an active CALL_REDIRECT trial
and removal of Android's managed direct mic→TX patch. See the
[comparison and interpretation](voice-routing-findings.md). **Do not repeat these
same call captures now**; the next step is the optional vendor inspection below.

## Original capture procedure (for reference)

Use a consenting nonessential test call, built-in microphone/earpiece, BCP playback
and recording stopped. Keep the working recorder app/module installed. Stop if normal
calling is affected. Do not test emergencies.

1. With **FX off**, switch to Termux during the call and run:

   ```sh
   umask 077
   su -c 'date; echo POLICY; dumpsys -t 3 media.audio_policy; echo FLINGER; dumpsys -t 3 media.audio_flinger; echo CAPTURE_COMPLETE' > "$HOME/fx-off.txt" 2>&1
   ```

   This requests root for read-only service dumps. If root is refused, stop rather than
   changing security settings. `CAPTURE_COMPLETE` means the shell reached the end, not
   that both dumps succeeded; permission and timeout errors must be checked too.

2. Start a delayed second snapshot:

   ```sh
   umask 077
   (sleep 15; su -c 'date; echo POLICY; dumpsys -t 3 media.audio_policy; echo FLINGER; dumpsys -t 3 media.audio_flinger; echo CAPTURE_COMPLETE' > "$HOME/fx-on.txt" 2>&1) &
   ```

3. Immediately switch back to the call screen (do not close Termux), select
   **FX → Unchanged voice — routing test → Start test**, and keep the call screen
   visible for 25 seconds. Complete selection within approximately 10 seconds of
   starting the command. This is a diagnostic use of the 30-second bypass trial,
   not another attempt to make a preset work. Do not use the 5-second Silence check
   for this capture: it will finish before the delayed dumps.

   Leaving the call screen stops the demo, so inspecting Termux while it is capturing
   would invalidate the comparison. The two service dumps are sequential, not an
   atomic snapshot; their mode/stream state needs checking against the active trial.

4. Turn FX off and end the test call. Review `~/fx-off.txt` and `~/fx-on.txt`, then share
   them privately if comfortable. State whether the Unchanged voice trial visibly
   remained active during capture and whether any error appeared. If Android killed
   the background command, root approval interrupted the trial, or the trial had
   already ended, the snapshot is not evidence of active redirection.

These commands create/overwrite only those two diagnostic files in Termux's private
home. The root commands themselves only read service state. They do not upload
anything. Never substitute mixer/setprop commands into this procedure.

## What to examine before proposing a routing change

- Requested/effective audio mode and the primary HAL's reported mode where available.
- Whether a direct built-in-microphone → Telephony TX patch survives the trial.
- App microphone input, injected output and downlink routes versus their actual devices.
- Output thread flags, sample rates, reported latency and buffering. The user's delay
  estimate is not proof of a specific buffer defect.
- Whether the policy-visible patch disappears while vendor/HAL routing still supplies
  direct speech. Absence of a framework patch alone does not prove suppression.

The demo uses BCP-style VOICE_COMMUNICATION playback to Telephony TX. AOSP's privileged
`getCallUplinkInjectionAudioTrack` instead uses CALL_ASSISTANT/redirection attributes;
that difference is a candidate for investigation, not a demonstrated suppression fix.
Likewise, global microphone mute may silence the app's own capture and must not be
used blindly. Selective vendor routing changes need device evidence, explicit user
approval, restoration logic and a successful remote Silence check before effects.

Reference review: Android 16 AudioManager/AudioService and LineageOS lineage-23.0
AudioPolicyManager. These are reference sources, not the installed Axion/vendor code.
The latter disconnects its managed native telephony sources when leaving IN_CALL,
but this does not establish how the phone's vendor HAL implements the transition.

## Completed step: identify vendor speech controls (for reference)

End the call first. This reads a platform property and selected text strings from
installed vendor audio libraries. It **does not execute the libraries or apply any
of the control names found**. It creates only `~/audio-vendor.txt`. Review the text
before sharing privately. No need to change modules, permissions or audio settings.

```sh
umask 077
su -c '
echo PLATFORM
getprop ro.board.platform
if ! command -v strings >/dev/null 2>&1; then
    echo "strings utility unavailable; stop here, do not install anything"
    exit 1
fi
for f in /vendor/lib*/hw/audio.primary*.so /vendor/lib*/libaudio*.so /vendor/lib*/libmtkaudio*.so; do
    [ -f "$f" ] || continue
    echo "=== $f ==="
    strings "$f" | grep -iE "speech.*mute|mute.*speech|uplink.*mute|mute.*uplink|mic.*mute|mute.*mic|bgs.*gain|bgs.*mute|call.?redir|call.?screen" | head -n 80
done
echo INSPECTION_COMPLETE
' > "$HOME/audio-vendor.txt" 2>&1
```

The 80-line cap is per library. Missing matches do not prove the vendor has no such
control: names may live in another library, be stripped, or use another interface.
If the file contains a root denial, missing-tool error, no library headings, or
read errors, report that instead of trying alternate write commands. If it contains
candidate controls, their semantics and recoverability still require verification;
never run a `Set_*` name just because it appears in the report.


## Next optional step: copy installed HAL libraries for offline review

The vendor string report has been received and shows separate speech-driver and
background-mixer mute names. **Do not repeat the string capture or toggle these
names.** Their implementation mapping and recovery behavior are still unknown.

With no call active, this command creates a private archive of just the two installed
vendor libraries. It reads the originals; it does not replace, patch or execute them:

```sh
umask 077
su -c 'cd /vendor && tar -chf - lib/hw/audio.primary.mt6768.so lib64/hw/audio.primary.mt6768.so' > "$HOME/audio-hal-review.tar"
```

`-h` follows symlinks so the archive contains library bytes rather than just link
names. If tar reports an error, stop and share that error; do not assume the archive
is complete. Otherwise attach `audio-hal-review.tar` privately for analysis. It
contains only the two specified library files, not recordings or call logs. Do not
commit these proprietary binaries to the project or redistribute them in releases.
The command has been syntax-checked here, not executed on the phone.

Offline review should establish the parameter handler → controller → driver mapping,
which mute state is read/written, and how state is restored. It cannot by itself
prove that the live modem honors the requested behavior. Any live control change
remains a separate, explicitly approved experiment, not part of this read-only step.
