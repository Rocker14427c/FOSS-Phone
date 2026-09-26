# FOSS Phone — experimental live voice demo

**This is a device-test demo, not a verified voice-replacement release.** The user
confirmed that BCP plays a selected audio file to the other caller on the Narzo 50A /
Axion Android 16. Live capture, processed injection, original-microphone suppression
and two-way audio in this implementation still need testing together.

## Build verification

[CI run 36259333901](https://github.com/Rocker14427c/FOSS-Phone/actions/runs/36259333901)
passed tests, real APK builds, certificate checks and exact APK/module verification.
Published source: `775e835f3de41deea73a96416e49aaf518a00dcc`.

The bundled `INSTALL.md` is a build-time snapshot whose build-status section predates
that successful run. Use the [current installation guide](https://github.com/Rocker14427c/FOSS-Phone/blob/arena/01a0dcd1-foss-phone/docs/voice-changer.md)
for updated status; the device-test restrictions remain unchanged.

## What's new

- Small **FX / Voice** button in the top-right of the answered-call screen.
- **Girl-like**, **Boy-like**, **Robot**, **Child / Chipmunk**, and **Off**.
- Switch presets while a demo is running. Long-press FX for Off.
- BCP-style telephony output fed by live microphone PCM, not a prerecorded patch file.
- Separate incoming-audio relay intended to preserve the other person's voice.
- No BCP dependency, external effect app, neural model, extra service or vendor mixer patch.

Girl-like/Boy-like are pitch effects, not natural AI gender/identity conversion.

## Protect your working recorder

This APK is **FOSS Phone Voice Trial**, package `org.fossify.phone.voice_trial.debug`.
Its module ID is `fossify_phone_voice_trial`. It does not replace the working
`org.fossify.phone.debug` app or `fossify_phone_call_audio` module.

1. Keep the working recording app/module and its recordings. Do **not** uninstall them.
2. Install the demo APK and its matching demo Magisk ZIP, then reboot.
3. Select **FOSS Phone Voice Trial** as the default phone app for testing and grant
   microphone permission. Do not play audio through BCP at the same time as this test.
4. Call a consenting person on a second phone. Use the earpiece; stop recording first.
5. Tap **FX → Silence check** and keep speaking. For the 5-second check, the other
   person must hear silence while you can still hear them. If your original voice
   remains audible, stop: this ROM has not provided clean microphone replacement.
6. Test Unchanged voice, then each preset. Have the other person confirm what they hear.

## Limits and recovery

- Other demos last **30 seconds**; changing presets does not extend the timer.
- One answered SIM call only. Earpiece and built-in microphone only.
- Recording and voice effects cannot run simultaneously in this first demo.
- Leaving the call screen, hold, mute, call/detail changes or route changes stops effects.
- Off/failure returns to normal, unmodified voice. This is **not** voice privacy protection.
- If sound does not return, end the test call, restore your original default phone app,
  and disable/remove **only the voice-trial module** before rebooting if necessary.
- CI debug signatures can change between builds. Updating the trial app may require
  removing only the trial app first; back up any recordings made in that trial app.

See the current installation guide above for source references and detailed test gates.
