# Fossify Phone
<img alt="Logo" src="graphics/icon.webp" width="120" />

<a href='https://play.google.com/store/apps/details?id=org.fossify.phone'><img alt='Get it on Google Play' src='https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png' height=80/></a> <a href="https://f-droid.org/packages/org.fossify.phone/"><img src="https://fdroid.gitlab.io/artwork/badge/get-it-on-en.svg" alt="Get it on F-Droid" height=80/></a> <a href="https://apt.izzysoft.de/fdroid/index/apk/org.fossify.phone"><img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png" alt="Get it on IzzyOnDroid" height=80/></a>

Empower your calls, and safeguard your data. Fossify Phone redefines the mobile app experience with unmatched privacy and efficiency. Free from ads and intrusive permissions, it's designed for seamless and secure everyday communication.

📱 **YOUR PRIVACY, OUR PRIORITY:**  
Welcome to the Fossify Phone App, where your digital privacy is paramount. Switch to a mobile experience that respects your data, ensuring your personal information remains secure and private.

🚀 **SEAMLESS PERFORMANCE:**  
The Fossify Phone App offers a fluid and responsive mobile interface, enhancing your phone's performance while safeguarding your privacy. Experience a lag-free, smooth user experience, optimized for efficiency and speed.

🌐 **OPEN-SOURCE ASSURANCE:**  
With the Fossify Phone App, transparency is at your fingertips. Built on an open-source foundation, our app allows you to review our code on GitHub, fostering trust and a community committed to privacy.

🖼️ **TAILOR-MADE CUSTOMIZATION:**  
Customize your mobile experience with the Fossify Phone App. Adjust your app settings for a personalized interface, from thematic designs to functional preferences. Enjoy a user interface that's intuitive and uniquely yours.

🔋 **EFFICIENT RESOURCE MANAGEMENT:**  
The Fossify Phone App is designed for optimal resource usage, contributing to extended battery life. It's light on your phone's resources, ensuring your device runs efficiently with minimized battery drain.

Download the Fossify Phone App now and step into a mobile world where privacy seamlessly blends with functionality. Your journey towards a safer, personalized mobile experience starts here.

## Integrated call recording

Calling and recording run in **one Phone app**. The recording backend follows BCR's raw-audio
approach: an in-process worker reads `AudioRecord` / `VOICE_CALL` PCM, detects digital silence,
and finalizes lossless WAV files off the call UI thread. No BCR app or other recorder is needed.

**A supported privileged/system installation is required.** Rooting the phone, installing the
APK normally, or making it the default dialer does not grant `CAPTURE_AUDIO_OUTPUT`. The app
now refuses recording without that grant instead of falling back to a potentially silent
microphone recording. ROM/vendor support for both voices is still required.

Enable **Automatically record calls** in Settings or use the in-call recording control. The existing call service temporarily runs in the foreground to keep capture/finalization alive; one file covers the call session,
including swaps/conferences, with held audio skipped. Recordings can be played, shared and
deleted in **Manage call recordings**. Older M4A recordings are still supported. Choose **Light**, **Balanced**, or **High detail**: approximately 1, 2, or 6 MB/minute of WAV.
Light defaults on Android low-RAM devices; Balanced defaults elsewhere. One worker uses reusable
buffers and batched reads. No extra recording service or always-running recorder is added.
Battery savings require device measurements.

- [Installation, opt-in Magisk module builder, rollback and device testing](docs/call-recording.md)
- [Review of the four recent commits and BCR architecture](docs/call-recording-review.md)

The module contains this same dialer APK and its permission allowlist—not another recording app.
The Google Play flavor keeps recording disabled. Notify participants yourself and follow local
consent laws: the optional local beep is not guaranteed to reach the other party.

The user reports recording works on the Realme Narzo 50A / Axion 2.7 Android 16.
Broader route compatibility and battery use still require device testing.

## Experimental live voice effects

The demo ports **BCP**'s telephony AudioTrack output (BCP playback is now confirmed by
the user on their phone) and adapts the MIT-licensed **Soundpipe** pitch core for live PCM.
A compact in-call **FX** button offers Girl-like, Boy-like, Robot and Child/Chipmunk. This is a disabled-by-default,
short-duration prototype—not a verified voice-changing release. The opt-in lab APK
and module use separate identities to preserve the working recorder. No extra effects
app is required; simultaneous recording/effects and other audio routes are not supported yet.

The [v1.14.0-alpha1 voice demo](https://github.com/Rocker14427c/FOSS-Phone/releases/tag/v1.14.0-alpha1-voice-demo)
passed CI and includes the real APK plus matching Magisk ZIP. Live voice replacement
still needs remote-caller testing; successful BCP playback alone does not prove it.
See [reference revisions, architecture, tests and remaining hardware gates](docs/voice-changer.md).

➡️ Explore more Fossify apps: https://www.fossify.org<br>
➡️ Open-Source Code: https://www.github.com/FossifyOrg<br>
➡️ Join the community on Reddit: https://www.reddit.com/r/Fossify<br>
➡️ Connect on Telegram: https://t.me/Fossify

<div align="center">
<img alt="App image" src="fastlane/metadata/android/en-US/images/phoneScreenshots/1_en-US.png" width="30%">
<img alt="App image" src="fastlane/metadata/android/en-US/images/phoneScreenshots/2_en-US.png" width="30%">
<img alt="App image" src="fastlane/metadata/android/en-US/images/phoneScreenshots/3_en-US.png" width="30%">
</div>
